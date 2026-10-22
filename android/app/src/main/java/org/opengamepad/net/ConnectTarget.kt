package org.opengamepad.net

/**
 * One or more PC addresses (tried in order) and the TCP port of its control channel.
 * [openGamepad]: the PC runs our companion (protocol extensions enabled).
 * [usb]: reached through "adb reverse" on 127.0.0.1, so states travel over TCP.
 */
data class ConnectTarget(
    val hosts: List<String>,
    val port: Int,
    val openGamepad: Boolean = false,
    val usb: Boolean = false,
) {
    override fun toString() = if (usb) "USB" else "${hosts.firstOrNull() ?: "?"}:$port"

    companion object {
        const val COMPANION_PORT = 47800

        /** USB cable: the companion maps the phone's 127.0.0.1:47800 to the PC with adb reverse. */
        fun usb() = ConnectTarget(listOf("127.0.0.1"), COMPANION_PORT, openGamepad = true, usb = true)

        private val IPV4 = Regex("""(?<![\d.])((?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3})(?![\d.])""")
        private val NUMBER = Regex("""(?<![\d.])\d{2,5}(?![\d.])""")

        /**
         * Accepts our companion's QR ("opengamepad://connect?c=192.168.1.10_47800"), the
         * original PC app's QR ("https://c.remotegamepad.com?c=192.168.1.10_53123"; hosts
         * and port separated by '_'), "host:port" typed by hand and, as a fallback,
         * any text containing IPv4 addresses and a port.
         */
        fun parse(raw: String): ConnectTarget? {
            val text = raw.trim()
            fromQrParameter(text)?.let { return it }
            val hosts = IPV4.findAll(text).map { it.value }.distinct().toList()
            if (hosts.isEmpty()) return null
            val withoutIps = IPV4.replace(text, " ")
            val port = NUMBER.findAll(withoutIps).map { it.value.toInt() }
                .firstOrNull { it in 1024..65535 } ?: return null
            return ConnectTarget(hosts, port, openGamepad = port == COMPANION_PORT)
        }

        private fun fromQrParameter(text: String): ConnectTarget? {
            val value = Regex("""[?&]c=([^&#\s]+)""").find(text)?.groupValues?.get(1) ?: return null
            val parts = java.net.URLDecoder.decode(value, "UTF-8").split('_', ',', ';').filter { it.isNotBlank() }
            val port = parts.lastOrNull()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val hosts = parts.dropLast(1)
            if (hosts.isEmpty()) return null
            return ConnectTarget(hosts, port, openGamepad = text.startsWith("opengamepad:", ignoreCase = true))
        }
    }
}
