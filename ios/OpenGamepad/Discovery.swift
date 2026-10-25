import Darwin
import Foundation

/// A companion or Switch sysmodule found on the local network.
struct FoundHost: Identifiable, Equatable {
    let name: String
    let host: String
    let port: UInt16

    var id: String { host }
    var target: ConnectTarget { ConnectTarget(hosts: [host], port: port) }
}

/// LAN discovery: "OGP-DISCOVER" to UDP 47801 of every host of the Wi-Fi subnet; hosts answer
/// "OGP-HERE <tcp port> <name>". iOS only lets apps with a restricted entitlement send broadcasts,
/// so the subnet is swept with unicast datagrams (allowed after the local network permission).
enum Discovery {
    static let port: UInt16 = 47801

    static func search(timeout: TimeInterval = 1.5) async -> [FoundHost] {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: searchBlocking(timeout: timeout))
            }
        }
    }

    private static func searchBlocking(timeout: TimeInterval) -> [FoundHost] {
        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { return [] }
        defer { close(fd) }
        var receiveTimeout = timeval(tv_sec: 0, tv_usec: 250_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &receiveTimeout, socklen_t(MemoryLayout<timeval>.size))

        let request = Array("OGP-DISCOVER".utf8)
        for (address, mask) in localSubnets() {
            let hostBits = 32 - mask.nonzeroBitCount
            guard (2...10).contains(hostBits) else { continue }
            let network = address & mask
            for offset in 1..<((UInt32(1) << hostBits) - 1) where network + offset != address {
                sendDatagram(fd, request, to: network + offset)
            }
        }

        var found: [String: FoundHost] = [:]
        let end = Date().addingTimeInterval(timeout)
        var buffer = [UInt8](repeating: 0, count: 256)
        while Date() < end {
            var from = sockaddr_in()
            var fromLength = socklen_t(MemoryLayout<sockaddr_in>.size)
            let count = withUnsafeMutablePointer(to: &from) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    recvfrom(fd, &buffer, buffer.count, 0, $0, &fromLength)
                }
            }
            guard count > 0 else { continue }
            let host = String(cString: inet_ntoa(from.sin_addr))
            if let answer = parse(String(decoding: buffer[0..<count], as: UTF8.self), host: host) {
                found[host] = answer
            }
        }
        return found.values.sorted { $0.host < $1.host }
    }

    static func parse(_ text: String, host: String) -> FoundHost? {
        let prefix = "OGP-HERE "
        guard text.hasPrefix(prefix) else { return nil }
        let rest = text.dropFirst(prefix.count).trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = rest.split(separator: " ", maxSplits: 1).map(String.init)
        guard let first = parts.first, let port = UInt16(first), port > 0 else { return nil }
        return FoundHost(name: parts.count > 1 ? parts[1] : host, host: host, port: port)
    }

    private static func sendDatagram(_ fd: Int32, _ bytes: [UInt8], to ip: UInt32) {
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr = in_addr(s_addr: ip.bigEndian)
        _ = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                sendto(fd, bytes, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
    }

    /// IPv4 address and netmask (host byte order) of the Wi-Fi/Ethernet interfaces.
    private static func localSubnets() -> [(address: UInt32, mask: UInt32)] {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return [] }
        defer { freeifaddrs(list) }
        var result: [(address: UInt32, mask: UInt32)] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let interface = pointer.pointee
            let flags = Int32(interface.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
                  String(cString: interface.ifa_name).hasPrefix("en"),
                  let address = interface.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
                  let mask = interface.ifa_netmask else { continue }
            let ip = address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            let netmask = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            result.append((ip, netmask))
        }
        return result
    }
}
