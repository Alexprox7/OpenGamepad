package org.opengamepad.amiibo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Amiibo messages between the app and the Switch sysmodule (TCP, OpenGamepad extension).
 *
 * - 18 list request (phone → console): u16 offset.
 * - 19 list (console → phone): u16 total, u16 offset, u8 flags (bit 0: emuiibo running),
 *   u8 count, count × (u8 length, UTF-8 path relative to sdmc:/emuiibo/amiibo).
 * - 20 activate (phone → console): u8 source; source 0 = UTF-8 path from the list;
 *   source 1 = u8 id[8], u8 uid[7], u8 name length, name, u8 group length, group.
 * - 21 status (console → phone): u8 result, u32 rc, UTF-8 text.
 * - 22 clear (phone → console): takes the amiibo off the virtual reader.
 */
object AmiiboProtocol {
    private const val SOURCE_CONSOLE = 0
    private const val SOURCE_PHONE = 1
    private const val MAX_TEXT = 63

    fun listRequest(offset: Int): ByteArray = byteArrayOf((offset and 0xFF).toByte(), (offset shr 8).toByte())

    fun activateConsole(path: String): ByteArray = byteArrayOf(SOURCE_CONSOLE.toByte()) + path.toByteArray()

    fun activatePhone(identity: AmiiboIdentity, name: String, group: String): ByteArray {
        val nameBytes = clip(name)
        val groupBytes = clip(group)
        return byteArrayOf(SOURCE_PHONE.toByte()) + identity.id + identity.uid +
            byteArrayOf(nameBytes.size.toByte()) + nameBytes +
            byteArrayOf(groupBytes.size.toByte()) + groupBytes
    }

    /** UTF-8 bytes of [text], at most [MAX_TEXT], never cutting a character in half. */
    private fun clip(text: String): ByteArray {
        var out = text.toByteArray()
        var end = text.length
        while (out.size > MAX_TEXT) out = text.substring(0, --end).toByteArray()
        return out
    }

    class ListPage(val total: Int, val offset: Int, val emuiibo: Boolean, val paths: List<String>)

    fun parseList(payload: ByteArray): ListPage? {
        if (payload.size < 6) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val total = buf.short.toInt() and 0xFFFF
        val offset = buf.short.toInt() and 0xFFFF
        val flags = buf.get().toInt()
        val count = buf.get().toInt() and 0xFF
        val paths = ArrayList<String>(count)
        repeat(count) {
            if (!buf.hasRemaining()) return null
            val length = buf.get().toInt() and 0xFF
            if (buf.remaining() < length) return null
            val bytes = ByteArray(length).also(buf::get)
            paths += String(bytes)
        }
        return ListPage(total, offset, flags and 1 != 0, paths)
    }

    enum class Result { ACTIVE, CLEARED, NO_EMUIIBO, ERROR, NOT_FOUND }

    class Status(val result: Result, val rc: Int, val text: String)

    fun parseStatus(payload: ByteArray): Status? {
        if (payload.size < 5) return null
        val result = Result.entries.getOrNull(payload[0].toInt()) ?: Result.ERROR
        val rc = ByteBuffer.wrap(payload, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return Status(result, rc, String(payload, 5, payload.size - 5))
    }
}
