package org.opengamepad.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format compatible with the Remote Gamepad PC app (phone v1.9, PC v1.22).
 *
 * Phone -> PC header (7 bytes, little-endian):
 *   u16 length (whole packet, header included) | u8 major | u8 minor | u16 sequence | u8 type
 * PC -> phone header (5 bytes): same without the sequence.
 */
object Wire {
    const val MAJOR: Byte = 1
    const val MINOR: Byte = 9
    const val HEADER_SIZE = 7
    const val RX_HEADER_SIZE = 5

    const val TYPE_HELLO = 0
    const val TYPE_STATE = 1
    const val TYPE_FEEDBACK = 2
    /** OpenGamepad extension: u8 modifiers, u8 count, count × u8 HID key usages. */
    const val TYPE_KEYBOARD = 16

    /** Appended to hello and reply when both sides are OpenGamepad. */
    val EXTENSION_MAGIC = "OGP".toByteArray(Charsets.US_ASCII)
    const val EXTENSION_VERSION: Byte = 1

    fun frame(type: Int, sequence: Int, payload: ByteArray): ByteArray {
        val total = HEADER_SIZE + payload.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(total.toShort())
            .put(MAJOR)
            .put(MINOR)
            .putShort((sequence % 65535).toShort())
            .put(type.toByte())
            .put(payload)
            .array()
    }

    /** Parses one complete packet received from the PC (length prefix included). */
    fun parse(packet: ByteArray, length: Int = packet.size): Message? {
        if (length < RX_HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val declared = buf.short.toInt() and 0xFFFF
        if (declared < RX_HEADER_SIZE || declared > length) return null
        val major = buf.get().toInt() and 0xFF
        val minor = buf.get().toInt() and 0xFF
        val type = buf.get().toInt() and 0xFF
        val payload = packet.copyOfRange(RX_HEADER_SIZE, declared)
        return Message(major, minor, type, payload)
    }
}

class Message(
    val major: Int,
    val minor: Int,
    val type: Int,
    val payload: ByteArray,
) {
    fun payloadBuffer(): ByteBuffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

    override fun toString() = "Message(v$major.$minor, type=$type, ${payload.size} bytes)"
}

/** Rumble and player-LED notification sent by the PC (type 2). */
data class Feedback(val largeMotor: Int, val smallMotor: Int, val ledNumber: Int) {
    companion object {
        fun from(message: Message): Feedback? {
            if (message.type != Wire.TYPE_FEEDBACK || message.payload.size < 3) return null
            val p = message.payload
            return Feedback(p[0].toInt() and 0xFF, p[1].toInt() and 0xFF, p[2].toInt() and 0xFF)
        }
    }
}
