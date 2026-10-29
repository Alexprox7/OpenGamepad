package org.opengamepad.motion

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opengamepad.protocol.Buttons
import org.opengamepad.protocol.ControllerState
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * DSU ("cemuhook") protocol version 1001, server side, one controller in slot 0.
 * Cemu, Ryujinx, Dolphin, Citra/Lime3DS, yuzu forks and others read motion from it.
 *
 * Packet: "DSUS" | u16 version 1001 | u16 length after the 16-byte header | u32 CRC32 of the
 * whole packet with this field zeroed | u32 server id | u32 message type | data.
 */
object DsuProtocol {
    const val VERSION = 1001
    const val HEADER_SIZE = 16
    const val TYPE_VERSION = 0x100000
    const val TYPE_PORTS = 0x100001
    const val TYPE_PAD_DATA = 0x100002
    const val PAD_PACKET_SIZE = 100
    const val PORT_PACKET_SIZE = 32

    private val SERVER_MAGIC = "DSUS".toByteArray(Charsets.US_ASCII)
    private val CLIENT_MAGIC = "DSUC".toByteArray(Charsets.US_ASCII)

    fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

    /** Writes header, length and CRC around a message of [type] whose data is [body]. */
    fun packet(serverId: Int, type: Int, body: ByteArray): ByteArray {
        val packet = ByteBuffer.allocate(HEADER_SIZE + 4 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(SERVER_MAGIC)
            .putShort(VERSION.toShort())
            .putShort((4 + body.size).toShort())
            .putInt(0)
            .putInt(serverId)
            .putInt(type)
            .put(body)
            .array()
        val crc = crc32(packet).toInt()
        ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(8, crc)
        return packet
    }

    fun versionReply(serverId: Int): ByteArray =
        packet(serverId, TYPE_VERSION, byteArrayOf((VERSION and 0xFF).toByte(), (VERSION shr 8).toByte()))

    /** Slot, state (2 connected), model (2 full gyro), connection (2 Bluetooth), MAC, battery. */
    private fun ByteBuffer.putShared(slot: Int, connected: Boolean, mac: ByteArray) {
        put(slot.toByte())
        put(if (connected) 2 else 0)
        put(if (connected) 2 else 0)
        put(if (connected) 2 else 0)
        put(if (connected) mac else ByteArray(6))
        put(if (connected) 0x05 else 0) // battery full
    }

    fun portInfo(serverId: Int, slot: Int, mac: ByteArray): ByteArray {
        val body = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        body.putShared(slot, slot == 0, mac)
        body.put(0)
        return packet(serverId, TYPE_PORTS, body.array())
    }

    /**
     * Pad data for slot 0. Buttons map by position like on the Switch (Xbox A bottom = DSU B).
     * Motion: accel in g with the DSU signs (lying flat face up reads y = -1), gyro in °/s as
     * pitch (around x), yaw (around the screen normal), roll (around y).
     */
    fun padData(serverId: Int, number: Int, mac: ByteArray, state: ControllerState, motion: MotionSample?): ByteArray {
        val s = state
        val body = ByteBuffer.allocate(PAD_PACKET_SIZE - HEADER_SIZE - 4).order(ByteOrder.LITTLE_ENDIAN)
        body.putShared(0, true, mac)
        body.put(1) // connected
        body.putInt(number)
        var b1 = 0
        if (s.isPressed(Buttons.DPAD_LEFT)) b1 = b1 or 0x80
        if (s.isPressed(Buttons.DPAD_DOWN)) b1 = b1 or 0x40
        if (s.isPressed(Buttons.DPAD_RIGHT)) b1 = b1 or 0x20
        if (s.isPressed(Buttons.DPAD_UP)) b1 = b1 or 0x10
        if (s.isPressed(Buttons.START)) b1 = b1 or 0x08
        if (s.isPressed(Buttons.RIGHT_THUMB)) b1 = b1 or 0x04
        if (s.isPressed(Buttons.LEFT_THUMB)) b1 = b1 or 0x02
        if (s.isPressed(Buttons.BACK)) b1 = b1 or 0x01
        var b2 = 0
        if (s.isPressed(Buttons.X)) b2 = b2 or 0x80 // Y (left)
        if (s.isPressed(Buttons.A)) b2 = b2 or 0x40 // B (bottom)
        if (s.isPressed(Buttons.B)) b2 = b2 or 0x20 // A (right)
        if (s.isPressed(Buttons.Y)) b2 = b2 or 0x10 // X (top)
        if (s.isPressed(Buttons.RIGHT_SHOULDER)) b2 = b2 or 0x08
        if (s.isPressed(Buttons.LEFT_SHOULDER)) b2 = b2 or 0x04
        if (s.rightTrigger > 128) b2 = b2 or 0x02
        if (s.leftTrigger > 128) b2 = b2 or 0x01
        body.put(b1.toByte())
        body.put(b2.toByte())
        body.put(if (s.isPressed(Buttons.GUIDE)) 1 else 0)
        body.put(0) // touchpad click
        body.put(stick(s.leftX)).put(stick(s.leftY)).put(stick(s.rightX)).put(stick(s.rightY))
        fun analog(mask: Int) = if (s.isPressed(mask)) 0xFF.toByte() else 0
        body.put(analog(Buttons.DPAD_LEFT)).put(analog(Buttons.DPAD_DOWN))
            .put(analog(Buttons.DPAD_RIGHT)).put(analog(Buttons.DPAD_UP))
        body.put(analog(Buttons.X)).put(analog(Buttons.A)).put(analog(Buttons.B)).put(analog(Buttons.Y))
        body.put(analog(Buttons.RIGHT_SHOULDER)).put(analog(Buttons.LEFT_SHOULDER))
        body.put(s.rightTrigger.coerceIn(0, 255).toByte()).put(s.leftTrigger.coerceIn(0, 255).toByte())
        body.put(ByteArray(12)) // two inactive touches
        if (motion != null) {
            body.putLong(motion.timestampNs / 1000)
            body.putFloat(motion.accelX)
            body.putFloat(-motion.accelZ)
            body.putFloat(-motion.accelY)
            body.putFloat(motion.gyroX)
            body.putFloat(motion.gyroZ)
            body.putFloat(motion.gyroY)
        } else {
            body.putLong(0)
            body.putFloat(0f).putFloat(-1f).putFloat(0f) // at rest, lying flat
            body.putFloat(0f).putFloat(0f).putFloat(0f)
        }
        return packet(serverId, TYPE_PAD_DATA, body.array())
    }

    /** Sticks as 0..255, 128 centered, up positive. */
    private fun stick(value: Short): Byte = ((value.toInt() shr 8) + 128).coerceIn(0, 255).toByte()

    /** Client request: returns its message type and data, or null when invalid. */
    fun parseRequest(data: ByteArray, length: Int): Pair<Int, ByteBuffer>? {
        if (length < HEADER_SIZE + 4) return null
        for (i in 0 until 4) if (data[i] != CLIENT_MAGIC[i]) return null
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val declared = buf.getShort(6).toInt() and 0xFFFF
        if (HEADER_SIZE + declared > length) return null
        val crc = buf.getInt(8)
        val copy = data.copyOf(HEADER_SIZE + declared)
        copy[8] = 0; copy[9] = 0; copy[10] = 0; copy[11] = 0
        if (crc32(copy).toInt() != crc) return null
        val type = buf.getInt(HEADER_SIZE)
        buf.position(HEADER_SIZE + 4)
        buf.limit(HEADER_SIZE + declared)
        return type to buf.slice().order(ByteOrder.LITTLE_ENDIAN)
    }
}

/**
 * Runs the DSU server on UDP [port] while started. Clients subscribe with pad-data requests and
 * must repeat them: after [SUBSCRIPTION_NS] without one, they stop receiving data.
 */
class DsuServer(private val mac: ByteArray, private val port: Int = MotionSettings.DSU_PORT) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val serverId = java.util.Random().nextInt()
    private val clients = ConcurrentHashMap<SocketAddress, Long>()
    @Volatile private var socket: DatagramSocket? = null
    private var job: Job? = null
    private var number = 0

    @Volatile var state: ControllerState = ControllerState.NEUTRAL

    private val _status = MutableStateFlow("")
    /** Shown in the settings: empty when stopped. */
    val status: StateFlow<String> = _status

    fun start() {
        if (job != null) return
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: IOException) {
            Log.w(TAG, "cannot open UDP $port", e)
            _status.value = "Porta $port occupata: ${e.message}"
            return
        }
        socket = s
        _status.value = "In ascolto sulla porta $port"
        job = scope.launch {
            val buffer = ByteArray(128)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    packet.length = buffer.size
                    s.receive(packet)
                } catch (_: IOException) {
                    break // closed by stop()
                }
                handle(s, packet)
            }
        }
    }

    private fun handle(s: DatagramSocket, packet: DatagramPacket) {
        val (type, data) = DsuProtocol.parseRequest(packet.data, packet.length) ?: return
        val from = packet.socketAddress
        val replies = when (type) {
            DsuProtocol.TYPE_VERSION -> listOf(DsuProtocol.versionReply(serverId))
            DsuProtocol.TYPE_PORTS -> {
                if (data.remaining() < 4) return
                val count = data.int.coerceIn(0, 4)
                (0 until count).filter { data.hasRemaining() }.map { data.get().toInt() and 0xFF }
                    .filter { it < 4 }
                    .map { DsuProtocol.portInfo(serverId, it, mac) }
            }
            DsuProtocol.TYPE_PAD_DATA -> {
                // Flags: 0 all slots, bit 0 by slot, bit 1 by MAC. Only slot 0 exists.
                val flags = if (data.hasRemaining()) data.get().toInt() else 0
                val slot = if (data.hasRemaining()) data.get().toInt() else 0
                if (flags and 1 != 0 && slot != 0) return
                if (clients.put(from, System.nanoTime()) == null) {
                    Log.i(TAG, "client $from")
                    _status.value = "Emulatore collegato: ${packet.address.hostAddress}"
                }
                emptyList()
            }
            else -> emptyList()
        }
        for (reply in replies) runCatching { s.send(DatagramPacket(reply, reply.size, from)) }
    }

    /** Sends a pad-data packet to every subscribed client. Call from a background thread. */
    fun publish(motion: MotionSample?) {
        val s = socket ?: return
        if (clients.isEmpty()) return
        val now = System.nanoTime()
        clients.entries.removeIf { now - it.value > SUBSCRIPTION_NS }
        if (clients.isEmpty()) {
            _status.value = "In ascolto sulla porta $port"
            return
        }
        val packet = synchronized(this) { DsuProtocol.padData(serverId, number++, mac, state, motion) }
        for (client in clients.keys) {
            runCatching { s.send(DatagramPacket(packet, packet.size, client)) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
        clients.clear()
        _status.value = ""
    }

    private companion object {
        const val TAG = "DsuServer"
        const val SUBSCRIPTION_NS = 5_000_000_000L
    }
}
