package org.opengamepad.net

import org.opengamepad.str
import org.opengamepad.R
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opengamepad.macro.KeyboardState
import org.opengamepad.motion.MotionSample
import org.opengamepad.protocol.ControllerState
import org.opengamepad.protocol.Feedback
import org.opengamepad.protocol.Message
import org.opengamepad.protocol.Wire
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data class Connecting(val target: String) : ConnectionStatus
    data class Connected(val target: String, val pcOs: String) : ConnectionStatus
    data class Failed(val reason: String) : ConnectionStatus
}

/**
 * Wi-Fi link to the PC: a TCP control channel (handshake, feedback) plus UDP for
 * controller states, as the original app does.
 */
class WifiConnection(private val deviceId: Long) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outgoing = Channel<ControllerState>(Channel.CONFLATED)
    // Every key change must arrive (press and release), so this one is not conflated.
    private val keyboard = Channel<KeyboardState>(Channel.UNLIMITED)
    private val writeLock = Any()
    @Volatile private var companion = false
    private val sequence = AtomicInteger(0)
    // Diagnostics (logcat tag WifiConnection): which connection sends what.
    private val sessionCounter = AtomicInteger(0)
    @Volatile private var sessionId = 0
    private val statesSent = AtomicInteger(0)
    private val motionSent = AtomicInteger(0)

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status

    private val _feedback = MutableSharedFlow<Feedback>(extraBufferCapacity = 8)
    val feedback: SharedFlow<Feedback> = _feedback

    /** Other host messages (amiibo list and status from the Switch sysmodule). */
    private val _hostMessages = MutableSharedFlow<Message>(extraBufferCapacity = 32)
    val hostMessages: SharedFlow<Message> = _hostMessages
    @Volatile private var tcpWriter: ((ByteArray) -> Unit)? = null

    private var tcp: Socket? = null
    @Volatile private var udp: DatagramSocket? = null
    private var session: Job? = null

    fun connect(target: ConnectTarget) {
        disconnect()
        session = scope.launch {
            val label = target.toString()
            _status.value = ConnectionStatus.Connecting(label)
            var lastError = str(R.string.no_address)
            for (host in target.hosts) {
                try {
                    runSession(host, target, label)
                    return@launch
                } catch (e: IOException) {
                    lastError = "$host: ${e.message ?: e.javaClass.simpleName}"
                    Log.w(TAG, "connection to $host failed", e)
                    closeSockets()
                }
            }
            _status.value = ConnectionStatus.Failed(lastError)
        }
    }

    private suspend fun runSession(host: String, target: ConnectTarget, label: String) {
        val id = sessionCounter.incrementAndGet()
        sessionId = id
        statesSent.set(0)
        motionSent.set(0)
        Log.i(TAG, "connessione $id: verso $host:${target.port}")
        val address = withContext(Dispatchers.IO) { InetAddress.getByName(host) }
        val socket = Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(address, target.port), CONNECT_TIMEOUT_MS)
        }
        tcp = socket
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()

        // Device id (big-endian, as the original); the extension block only goes to our companion.
        val hello = ByteBuffer.allocate(if (target.openGamepad) 12 else 8).putLong(deviceId)
        if (target.openGamepad) hello.put(Wire.EXTENSION_MAGIC).put(Wire.EXTENSION_VERSION)
        output.write(Wire.frame(Wire.TYPE_HELLO, 0, hello.array()))
        output.flush()

        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        var reply = readMessage(input)
        // Feedback packets can arrive before the reply: skip them (bounded by the timeout).
        while (reply.type != Wire.TYPE_HELLO) {
            Feedback.from(reply)?.let { _feedback.tryEmit(it) }
            reply = readMessage(input)
        }
        socket.soTimeout = 0
        Log.i(TAG, "connessione $id: risposta $reply")
        // The PC answers with type 0: u16 UDP port, then (v1.1+) u8 operating system.
        val body = reply.payloadBuffer()
        if (body.remaining() < 2) throw IOException(str(R.string.bad_handshake))
        val udpPort = body.short.toInt() and 0xFFFF
        val pcOs = if (body.hasRemaining()) osName(body.get().toInt()) else "Windows"
        val companion = body.remaining() >= 4 &&
            ByteArray(3).also { body.get(it) }.contentEquals(Wire.EXTENSION_MAGIC)
        val pcName = if (companion) "$pcOs, OpenGamepad" else pcOs

        this.companion = companion
        fun writeTcp(packet: ByteArray) = synchronized(writeLock) { output.write(packet); output.flush() }
        tcpWriter = if (companion) ::writeTcp else null

        scope.launch { receiveTcp(input, socket) }
        if (companion) scope.launch {
            for (state in keyboard) {
                val payload = byteArrayOf(state.modifiers.toByte(), state.keys.size.toByte()) +
                    state.keys.map { it.toByte() }.toByteArray()
                try {
                    writeTcp(Wire.frame(Wire.TYPE_KEYBOARD, sequence.getAndIncrement(), payload))
                } catch (_: IOException) {
                    break
                }
            }
        }
        if (target.usb || udpPort == 0) {
            // adb reverse carries only TCP: states go on the control channel.
            _status.value = ConnectionStatus.Connected(label, "$pcName, TCP")
            sendLoop(::writeTcp)
        } else {
            val datagram = DatagramSocket().apply { connect(InetSocketAddress(address, udpPort)) }
            udp = datagram
            _status.value = ConnectionStatus.Connected(label, pcName)
            scope.launch { receiveUdp(datagram) }
            sendLoop { packet -> datagram.send(DatagramPacket(packet, packet.size)) }
        }
    }

    /** Sends every new state immediately and repeats the last one as a keep-alive. */
    private suspend fun sendLoop(send: (ByteArray) -> Unit) {
        var last = ControllerState.NEUTRAL
        val id = sessionId
        var reportAt = System.nanoTime()
        while (scope.isActive && tcp?.isClosed == false) {
            if (System.nanoTime() - reportAt > 5_000_000_000L) {
                reportAt = System.nanoTime()
                Log.i(TAG, "connessione $id: stati ${statesSent.get()}, movimento ${motionSent.get()}")
            }
            last = withTimeoutOrNull(KEEPALIVE_MS) { outgoing.receive() } ?: last
            val packet = Wire.frame(Wire.TYPE_STATE, sequence.getAndIncrement(), last.encode())
            try {
                send(packet)
                statesSent.incrementAndGet()
            } catch (e: IOException) {
                Log.w(TAG, "connessione $id: invio fallito", e)
                fail(str(R.string.send_failed, e.message ?: ""))
                return
            }
        }
        Log.i(TAG, "connessione $id: invio stati terminato (attiva ${scope.isActive}, tcp ${tcp?.isClosed}, connessione corrente ${sessionId})")
    }

    private suspend fun receiveTcp(input: DataInputStream, socket: Socket) {
        try {
            while (scope.isActive) handleIncoming(readMessage(input))
        } catch (e: IOException) {
            // A connection replaced by a newer one ends here too: it must not close the new one.
            if (tcp === socket) fail(str(R.string.pc_closed))
        }
    }

    private fun receiveUdp(datagram: DatagramSocket) {
        val buffer = ByteArray(512)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (scope.isActive) {
                datagram.receive(packet)
                Wire.parse(buffer, packet.length)?.let(::handleIncoming)
            }
        } catch (_: IOException) {
            // closed with the session
        }
    }

    private fun handleIncoming(message: Message) {
        val feedback = Feedback.from(message)
        when {
            feedback != null -> _feedback.tryEmit(feedback)
            message.type >= Wire.TYPE_AMIIBO_LIST_REQUEST -> _hostMessages.tryEmit(message)
            else -> Log.d(TAG, "ignored $message")
        }
    }

    private fun readMessage(input: DataInputStream): Message {
        val lo = input.readUnsignedByte()
        val hi = input.readUnsignedByte()
        val length = lo or (hi shl 8)
        if (length < Wire.RX_HEADER_SIZE) throw IOException(str(R.string.bad_packet_len, length))
        val packet = ByteArray(length)
        packet[0] = lo.toByte()
        packet[1] = hi.toByte()
        input.readFully(packet, 2, length - 2)
        Log.v(TAG, "rx " + packet.joinToString(" ") { "%02x".format(it) })
        return Wire.parse(packet) ?: throw IOException(str(R.string.bad_packet))
    }

    fun send(state: ControllerState) {
        outgoing.trySend(state)
    }

    /**
     * Gyro passthrough (type 17), over UDP only and only to OpenGamepad hosts. Called from the
     * sensor thread: the datagram goes out directly, without waiting for the state loop.
     */
    fun sendMotion(sample: MotionSample) {
        val socket = udp ?: return
        if (!companion || _status.value !is ConnectionStatus.Connected) return
        val packet = Wire.frame(Wire.TYPE_MOTION, sequence.getAndIncrement(), sample.encode())
        try {
            socket.send(DatagramPacket(packet, packet.size))
            motionSent.incrementAndGet()
        } catch (_: IOException) {
            // the state loop reports broken links
        }
    }

    /**
     * An OpenGamepad request on the control channel (amiibo); dropped when the host is not an
     * OpenGamepad host or the link is down. Safe to call from the main thread.
     */
    fun sendControl(type: Int, payload: ByteArray) {
        val writer = tcpWriter ?: return
        if (_status.value !is ConnectionStatus.Connected) return
        scope.launch {
            try {
                writer(Wire.frame(type, sequence.getAndIncrement(), payload))
            } catch (_: IOException) {
                // the state loop reports broken links
            }
        }
    }

    /** PC keys from macros; only our companion understands them. */
    fun sendKeys(state: KeyboardState) {
        if (companion && _status.value is ConnectionStatus.Connected) keyboard.trySend(state)
    }

    private fun fail(reason: String) {
        Log.i(TAG, "connessione $sessionId: chiusa ($reason)")
        if (_status.value is ConnectionStatus.Connected) _status.value = ConnectionStatus.Failed(reason)
        closeSockets()
    }

    fun disconnect() {
        if (tcp != null) Log.i(TAG, "connessione $sessionId: chiusa dall'app")
        companion = false
        session?.cancel()
        session = null
        closeSockets()
        _status.value = ConnectionStatus.Idle
    }

    private fun closeSockets() {
        tcpWriter = null
        runCatching { tcp?.close() }
        runCatching { udp?.close() }
        tcp = null
        udp = null
    }

    fun release() {
        disconnect()
        scope.cancel()
    }

    private fun osName(code: Int) = when (code) {
        1 -> "Windows"
        2 -> "Linux"
        3 -> "Mac"
        4 -> "Switch"
        else -> str(R.string.unknown_os, code)
    }

    companion object {
        private const val TAG = "WifiConnection"
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val HANDSHAKE_TIMEOUT_MS = 5000
        private const val KEEPALIVE_MS = 200L
    }
}
