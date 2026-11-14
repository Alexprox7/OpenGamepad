import Foundation
import Network

/// Wi-Fi link to a companion or the Switch sysmodule: TCP for the handshake and feedback,
/// UDP for controller states when the host offers it.
@MainActor
final class GamepadConnection: ObservableObject {
    enum Status: Equatable {
        case idle
        case connecting(String)
        case connected(target: String, host: String)
        case failed(String)
    }

    @Published private(set) var status: Status = .idle
    @Published private(set) var player = 0
    var onFeedback: (@MainActor (Feedback) -> Void)?

    private var link: Link?

    var isConnected: Bool {
        if case .connected = status { return true }
        return false
    }

    var isSwitch: Bool {
        if case .connected(_, let host) = status { return host == "Switch" }
        return false
    }

    func connect(_ target: ConnectTarget) {
        link?.stop()
        player = 0
        status = .connecting(target.description)
        let link = Link(target: target)
        self.link = link
        link.onStatus = { [weak self, weak link] status in
            Task { @MainActor in
                guard let self, let link, self.link === link else { return }
                self.status = status
            }
        }
        link.onFeedback = { [weak self] feedback in
            Task { @MainActor in
                guard let self else { return }
                // 255 = the host has no player numbers (Linux companion).
                if feedback.led < 16 { self.player = Int(feedback.led) + 1 }
                self.onFeedback?(feedback)
            }
        }
        link.start()
    }

    func send(_ state: ControllerState) {
        link?.update(state)
    }

    func disconnect() {
        link?.stop()
        link = nil
        status = .idle
    }
}

/// Everything below runs on one serial queue.
private final class Link: @unchecked Sendable {
    private static let keepAlive: TimeInterval = 0.2
    private static let handshakeTimeout: TimeInterval = 3

    let target: ConnectTarget
    var onStatus: ((GamepadConnection.Status) -> Void)?
    var onFeedback: ((Feedback) -> Void)?

    private let queue = DispatchQueue(label: "org.opengamepad.link")
    private var tcp: NWConnection?
    private var udp: NWConnection?
    private var timer: DispatchSourceTimer?
    private var sequence: UInt16 = 1
    private var state = ControllerState()
    private var connected = false
    private var stopped = false

    init(target: ConnectTarget) {
        self.target = target
    }

    func start() {
        queue.async { self.attempt(hostIndex: 0, lastError: String(localized: "no response")) }
    }

    func update(_ newState: ControllerState) {
        queue.async {
            guard newState != self.state else { return }
            self.state = newState
            self.sendState()
        }
    }

    func stop() {
        queue.async {
            self.stopped = true
            self.teardown()
        }
    }

    private func teardown() {
        timer?.cancel()
        timer = nil
        tcp?.cancel()
        udp?.cancel()
        tcp = nil
        udp = nil
        connected = false
    }

    /// Tries the addresses of the target one after the other.
    private func attempt(hostIndex: Int, lastError: String) {
        guard !stopped else { return }
        guard hostIndex < target.hosts.count, let port = NWEndpoint.Port(rawValue: target.port) else {
            onStatus?(.failed(String(localized: "Could not connect to \(target.description): \(lastError)")))
            return
        }
        let host = target.hosts[hostIndex]
        let parameters = NWParameters.tcp
        (parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options)?.noDelay = true
        let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: parameters)
        tcp = connection

        var settled = false
        let fail: (String) -> Void = { [weak self] reason in
            guard let self, !settled else { return }
            settled = true
            connection.cancel()
            self.attempt(hostIndex: hostIndex + 1, lastError: reason)
        }
        queue.asyncAfter(deadline: .now() + Self.handshakeTimeout) { fail(String(localized: "timed out")) }

        connection.stateUpdateHandler = { [weak self] newState in
            guard let self else { return }
            switch newState {
            case .ready:
                guard !settled else { return }
                connection.send(content: Wire.hello(deviceId: Wire.deviceId), completion: .contentProcessed { _ in })
                self.awaitReply(connection) { reply in
                    guard !settled else { return }
                    guard let reply else { return fail(String(localized: "invalid handshake reply")) }
                    settled = true
                    self.onConnected(connection, host: host, reply: reply)
                }
            case .failed(let error), .waiting(let error):
                if settled && self.connected && connection === self.tcp {
                    self.lost(String(localized: "Connection lost: \(error.localizedDescription)"))
                } else {
                    fail(error.localizedDescription)
                }
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    /// The host may send feedback before the hello reply: skip it.
    private func awaitReply(_ connection: NWConnection, completion: @escaping ([UInt8]?) -> Void) {
        readMessage(connection) { [weak self] message in
            guard let message else { return completion(nil) }
            if message.type == Wire.typeHello { return completion(message.payload) }
            self?.handle(message)
            self?.awaitReply(connection, completion: completion)
        }
    }

    private func onConnected(_ connection: NWConnection, host: String, reply: [UInt8]) {
        guard reply.count >= 3 else { return lost(String(localized: "invalid handshake reply")) }
        let udpPort = UInt16(reply[0]) | UInt16(reply[1]) << 8
        let hostName = Wire.hostName(reply[2])
        if udpPort != 0, let port = NWEndpoint.Port(rawValue: udpPort) {
            let udp = NWConnection(host: NWEndpoint.Host(host), port: port, using: .udp)
            udp.start(queue: queue)
            self.udp = udp
        }
        connected = true
        onStatus?(.connected(target: "\(host):\(target.port)", host: hostName))
        receiveLoop(connection)

        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + Self.keepAlive, repeating: Self.keepAlive)
        timer.setEventHandler { [weak self] in self?.sendState() }
        timer.resume()
        self.timer = timer
        sendState()
    }

    private func receiveLoop(_ connection: NWConnection) {
        readMessage(connection) { [weak self] message in
            guard let self, connection === self.tcp else { return }
            guard let message else { return self.lost(String(localized: "Connection closed by the host")) }
            self.handle(message)
            self.receiveLoop(connection)
        }
    }

    private func handle(_ message: (type: UInt8, payload: [UInt8])) {
        if message.type == Wire.typeFeedback, message.payload.count >= 3 {
            onFeedback?(Feedback(largeMotor: message.payload[0], smallMotor: message.payload[1], led: message.payload[2]))
        }
    }

    private func lost(_ reason: String) {
        guard !stopped else { return }
        teardown()
        onStatus?(.failed(reason))
    }

    private func sendState() {
        guard connected else { return }
        let packet = Wire.frame(sequence: sequence, type: Wire.typeState, payload: state.encoded())
        sequence &+= 1
        (udp ?? tcp)?.send(content: packet, completion: .contentProcessed { _ in })
    }

    /// Host -> phone frame: u16 length | major | minor | type | payload.
    private func readMessage(_ connection: NWConnection, completion: @escaping ((type: UInt8, payload: [UInt8])?) -> Void) {
        connection.receive(minimumIncompleteLength: 2, maximumLength: 2) { data, _, _, error in
            guard error == nil, let data, data.count == 2 else { return completion(nil) }
            let bytes = [UInt8](data)
            let length = Int(bytes[0]) | Int(bytes[1]) << 8
            guard length >= 5 else { return completion(nil) }
            connection.receive(minimumIncompleteLength: length - 2, maximumLength: length - 2) { rest, _, _, error in
                guard error == nil, let rest, rest.count == length - 2 else { return completion(nil) }
                let body = [UInt8](rest)
                completion((body[2], Array(body[3...])))
            }
        }
    }
}
