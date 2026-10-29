package org.opengamepad.amiibo

/**
 * Identity of an amiibo, read from the unencrypted part of a raw NTAG215 dump (.bin, 532-572
 * bytes, as made by the user from their own figures). Only these two fields are sent to the
 * console: emuiibo builds the virtual amiibo around them.
 */
class AmiiboIdentity(
    /** 8 bytes at 0x54: character (2), variant, figure type, model number (2), series, 0x02. */
    val id: ByteArray,
    /** 7-byte tag UID (bytes 0-2 and 4-7 of the dump; 3 and 8 are check bytes). */
    val uid: ByteArray,
) {
    /** The id as 16 hex digits, the way amiibo databases print it (e.g. 01000000034f0902). */
    val idHex: String get() = id.joinToString("") { "%02x".format(it) }
}

object AmiiboDump {
    const val MIN_SIZE = 532
    const val MAX_SIZE = 2048
    private const val ID_OFFSET = 0x54

    /** Null when [bytes] is not an amiibo dump. */
    fun parse(bytes: ByteArray): AmiiboIdentity? {
        if (bytes.size !in MIN_SIZE..MAX_SIZE) return null
        val id = bytes.copyOfRange(ID_OFFSET, ID_OFFSET + 8)
        // Every amiibo id ends with 0x02 and has a non-zero character or model.
        if (id[7].toInt() != 0x02 || id.take(7).all { it.toInt() == 0 }) return null
        val uid = byteArrayOf(bytes[0], bytes[1], bytes[2], bytes[4], bytes[5], bytes[6], bytes[7])
        return AmiiboIdentity(id, uid)
    }
}
