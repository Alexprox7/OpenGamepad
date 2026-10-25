import Foundation

/// Xbox 360 report sent to the host: XUSB button bits, signed sticks (up is positive), 0-255 triggers.
struct ControllerState: Equatable {
    var buttons: UInt16 = 0
    var leftX: Int16 = 0
    var leftY: Int16 = 0
    var rightX: Int16 = 0
    var rightY: Int16 = 0
    var leftTrigger: UInt8 = 0
    var rightTrigger: UInt8 = 0

    func encoded() -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(12)
        out.appendLE(buttons)
        out.appendLE(UInt16(bitPattern: leftX))
        out.appendLE(UInt16(bitPattern: leftY))
        out.appendLE(UInt16(bitPattern: rightX))
        out.appendLE(UInt16(bitPattern: rightY))
        out.append(leftTrigger)
        out.append(rightTrigger)
        return out
    }
}

/// Same bits as XINPUT_GAMEPAD_*.
enum Buttons {
    static let dpadUp: UInt16 = 0x0001
    static let dpadDown: UInt16 = 0x0002
    static let dpadLeft: UInt16 = 0x0004
    static let dpadRight: UInt16 = 0x0008
    static let start: UInt16 = 0x0010
    static let back: UInt16 = 0x0020
    static let leftThumb: UInt16 = 0x0040
    static let rightThumb: UInt16 = 0x0080
    static let leftShoulder: UInt16 = 0x0100
    static let rightShoulder: UInt16 = 0x0200
    static let guide: UInt16 = 0x0400
    static let a: UInt16 = 0x1000
    static let b: UInt16 = 0x2000
    static let x: UInt16 = 0x4000
    static let y: UInt16 = 0x8000
}

struct Feedback {
    let largeMotor: UInt8
    let smallMotor: UInt8
    let led: UInt8
}

/// Wire format shared with the companions and the Switch sysmodule (see docs/PROTOCOL.md).
/// Phone -> host: u16 length | 1 | 9 | u16 sequence | u8 type | payload (little-endian).
/// Host -> phone: u16 length | major | minor | u8 type | payload.
enum Wire {
    static let typeHello: UInt8 = 0
    static let typeState: UInt8 = 1
    static let typeFeedback: UInt8 = 2
    static let defaultPort: UInt16 = 47800
    static let extensionMagic: [UInt8] = Array("OGP".utf8)

    static func frame(sequence: UInt16, type: UInt8, payload: [UInt8]) -> Data {
        var out: [UInt8] = []
        out.appendLE(UInt16(7 + payload.count))
        out.append(1)
        out.append(9)
        out.appendLE(sequence)
        out.append(type)
        out.append(contentsOf: payload)
        return Data(out)
    }

    static func hello(deviceId: UInt64) -> Data {
        var payload: [UInt8] = (0..<8).map { UInt8(truncatingIfNeeded: deviceId >> (56 - 8 * UInt64($0))) }
        payload.append(contentsOf: extensionMagic)
        payload.append(1)
        return frame(sequence: 0, type: typeHello, payload: payload)
    }

    static func hostName(_ code: UInt8) -> String {
        switch code {
        case 1: return "Windows"
        case 2: return "Linux"
        case 3: return "macOS"
        case 4: return "Switch"
        default: return "PC"
        }
    }

    /// Random id kept across launches, like the Android app.
    static var deviceId: UInt64 {
        let key = "deviceId"
        if let saved = UserDefaults.standard.string(forKey: key), let id = UInt64(saved) { return id }
        let id = UInt64.random(in: 1...UInt64.max)
        UserDefaults.standard.set(String(id), forKey: key)
        return id
    }
}

/// One or more addresses of the same host (the QR code lists every address of the PC) and a port.
struct ConnectTarget: Equatable {
    let hosts: [String]
    let port: UInt16

    var description: String { "\(hosts.joined(separator: " / ")):\(port)" }

    /// Accepts `opengamepad://connect?c=IP_IP_PORT`, `IP:port` or a bare IP.
    static func parse(_ text: String) -> ConnectTarget? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let components = URLComponents(string: trimmed), components.scheme == "opengamepad",
           let value = components.queryItems?.first(where: { $0.name == "c" })?.value {
            var parts = value.split(separator: "_").map(String.init)
            guard parts.count >= 2, let port = UInt16(parts.removeLast()) else { return nil }
            return ConnectTarget(hosts: parts, port: port)
        }
        let pieces = trimmed.split(separator: ":").map(String.init)
        guard let host = pieces.first, !host.isEmpty else { return nil }
        if pieces.count == 2 {
            guard let port = UInt16(pieces[1]) else { return nil }
            return ConnectTarget(hosts: [host], port: port)
        }
        return pieces.count == 1 ? ConnectTarget(hosts: [host], port: Wire.defaultPort) : nil
    }
}

extension Array where Element == UInt8 {
    mutating func appendLE(_ value: UInt16) {
        append(UInt8(value & 0xFF))
        append(UInt8(value >> 8))
    }
}
