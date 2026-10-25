package org.opengamepad.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException

/** A PC companion or Switch sysmodule found on the local network. */
data class FoundHost(val name: String, val host: String, val port: Int) {
    fun target() = ConnectTarget(listOf(host), port, openGamepad = true)
}

/**
 * LAN discovery, two ways at once:
 * - UDP: "OGP-DISCOVER" to UDP 47801 (broadcast and every subnet host); companions and
 *   the Switch sysmodule answer "OGP-HERE <tcp port> <name>";
 * - TCP: a bare connect to port 47800 on every subnet host, for networks or phones that
 *   drop UDP broadcasts or incoming UDP answers. No hello is sent, so no controller is created.
 */
object Discovery {
    const val PORT = 47801
    private const val TCP_PORT = 47800
    private const val REQUEST = "OGP-DISCOVER"
    private const val ANSWER = "OGP-HERE "

    suspend fun search(timeoutMs: Long = 1500): List<FoundHost> = withContext(Dispatchers.IO) {
        coroutineScope {
            val udp = async { searchUdp(timeoutMs) }
            val tcp = async { probeTcp() }
            val named = udp.await().associateBy { it.host }
            // Hosts that only answered on TCP keep their address as name.
            (named.values + tcp.await().filter { it !in named }.map { FoundHost(it, it, TCP_PORT) })
                .sortedBy { it.host }
        }
    }

    private fun searchUdp(timeoutMs: Long): List<FoundHost> {
        val found = LinkedHashMap<String, FoundHost>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250
            val request = REQUEST.toByteArray()
            for (target in broadcastAddresses() + broadcastAddresses() + subnetHosts()) {
                runCatching { socket.send(DatagramPacket(request, request.size, target, PORT)) }
            }
            val buffer = ByteArray(256)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                parse(String(packet.data, 0, packet.length), packet.address.hostAddress ?: continue)
                    ?.let { found[it.host] = it }
            }
        }
        return found.values.toList()
    }

    /** Addresses that accept a TCP connection on the OpenGamepad port. */
    private suspend fun probeTcp(): List<String> = coroutineScope {
        val limit = Semaphore(64)
        subnetHosts().map { address ->
            async {
                limit.withPermit {
                    runCatching {
                        Socket().use { it.connect(InetSocketAddress(address, TCP_PORT), 400) }
                        address.hostAddress
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Subnet broadcasts of the active interfaces (e.g. 192.168.1.255) plus the global one:
     * many phones do not route 255.255.255.255 to Wi-Fi.
     */
    private fun broadcastAddresses(): List<InetAddress> {
        val subnets = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
        }.getOrDefault(emptyList())
        return (subnets + InetAddress.getByName("255.255.255.255")).distinct()
    }

    /** Every other address of the local IPv4 subnets (only for /22 or smaller networks). */
    private fun subnetHosts(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address && it.networkPrefixLength in 22..30 }
            .flatMap { address ->
                val own = address.address.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                val hostBits = 32 - address.networkPrefixLength
                val network = own and (0xFFFFFFFFL shl hostBits)
                (1 until (1L shl hostBits) - 1).map { network + it }
                    .filter { it != own }
                    .map { ip -> InetAddress.getByAddress(ByteArray(4) { i -> (ip shr (24 - 8 * i)).toByte() }) }
            }
    }.getOrDefault(emptyList())

    internal fun parse(text: String, host: String): FoundHost? {
        if (!text.startsWith(ANSWER)) return null
        val rest = text.removePrefix(ANSWER).trim()
        val port = rest.substringBefore(' ').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val name = rest.substringAfter(' ', "").ifBlank { host }
        return FoundHost(name, host, port)
    }
}
