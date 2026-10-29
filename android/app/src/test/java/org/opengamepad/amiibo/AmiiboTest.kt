package org.opengamepad.amiibo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opengamepad.macro.RadialMenu
import org.opengamepad.macro.RadialMode

class AmiiboTest {
    /** A synthetic 540-byte dump: UID 04 A1 B2 (BCC) C3 D4 E5 F6, id of 8-Bit Link (01000000 034f0902). */
    private fun dump(size: Int = 540): ByteArray = ByteArray(size).also {
        byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0x00, 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte())
            .copyInto(it, 0)
        byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x03, 0x4F, 0x09, 0x02).copyInto(it, 0x54)
    }

    @Test
    fun parsesIdentityFromDump() {
        val identity = assertNotNull(AmiiboDump.parse(dump())).let { AmiiboDump.parse(dump())!! }
        assertArrayEquals(byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte()), identity.uid)
        assertEquals("01000000034f0902", identity.idHex)
        assertNotNull(AmiiboDump.parse(dump(532)))
        assertNotNull(AmiiboDump.parse(dump(572)))
    }

    @Test
    fun rejectsFilesThatAreNotDumps() {
        assertNull(AmiiboDump.parse(dump(100)))
        assertNull(AmiiboDump.parse(ByteArray(540)))
        assertNull(AmiiboDump.parse(dump().also { it[0x5B] = 0 }))
    }

    @Test
    fun phoneActivationCarriesIdUidNameAndGroup() {
        val payload = AmiiboProtocol.activatePhone(AmiiboDump.parse(dump())!!, "8-Bit Link", "Zelda")
        assertEquals(1, payload[0].toInt())
        assertArrayEquals(dump().copyOfRange(0x54, 0x5C), payload.copyOfRange(1, 9))
        assertEquals(0xF6.toByte(), payload[15])
        assertEquals(10, payload[16].toInt())
        assertEquals("8-Bit Link", String(payload, 17, 10))
        assertEquals(5, payload[27].toInt())
        assertEquals("Zelda", String(payload, 28, 5))
        assertEquals(33, payload.size)
    }

    @Test
    fun longNamesAreClippedWithoutBreakingUtf8() {
        val name = "è".repeat(40) // 80 bytes in UTF-8
        val payload = AmiiboProtocol.activatePhone(AmiiboDump.parse(dump())!!, name, "")
        val length = payload[16].toInt()
        assertTrue(length <= 63)
        assertEquals("è".repeat(length / 2), String(payload, 17, length))
    }

    @Test
    fun parsesListPageAndStatus() {
        val entries = listOf("Daruk", "Zelda/Link")
        val body = mutableListOf<Byte>(3, 0, 0, 0, 1, entries.size.toByte())
        entries.forEach { e -> body += e.length.toByte(); body += e.toByteArray().toList() }
        val page = AmiiboProtocol.parseList(body.toByteArray())!!
        assertEquals(3, page.total)
        assertEquals(0, page.offset)
        assertTrue(page.emuiibo)
        assertEquals(entries, page.paths)

        val status = AmiiboProtocol.parseStatus(byteArrayOf(0, 0x10, 0, 0, 0) + "Daruk".toByteArray())!!
        assertEquals(AmiiboProtocol.Result.ACTIVE, status.result)
        assertEquals(0x10, status.rc)
        assertEquals("Daruk", status.text)
        assertNull(AmiiboProtocol.parseList(byteArrayOf(1, 0, 0, 0, 1, 2, 9)))
    }

    private fun RadialMenu.allWheels(): List<RadialMenu> =
        listOf(this) + slices.mapNotNull { it.folder }.flatMap { it.allWheels() }

    @Test
    fun menuWithBothSourcesHasClearFirstAndTwoFolders() {
        val menu = AmiiboMenu.build(
            phone = listOf(AmiiboMenu.PhoneItem("Mario", "", "Mario")),
            console = listOf("Daruk"),
        )
        assertEquals(AmiiboMenu.ID, menu.id)
        assertEquals(RadialMode.STICKY, menu.mode)
        assertEquals(listOf("Rimuovi", "Telefono", "Switch"), menu.slices.map { it.label })
        assertEquals(AmiiboMenu.KEY_CLEAR, menu.slices[0].key)
        assertEquals("phone:Mario", menu.slices[1].folder!!.slices.single().key)
        assertEquals("console:Daruk", menu.slices[2].folder!!.slices.single().key)
    }

    @Test
    fun singleSourceGoesStraightOnTheWheelWithFoldersAndPages() {
        val console = (1..30).map { "Amiibo %02d".format(it) } + listOf("Zelda/Link", "Zelda/Zelda", "Smash/Pikachu")
        val menu = AmiiboMenu.build(emptyList(), console)
        assertEquals("Rimuovi", menu.slices.first().label)
        // Folders come first, then single amiibo; nothing is lost and no wheel is crowded.
        assertEquals(listOf("Smash", "Zelda"), menu.slices.drop(1).take(2).map { it.label })
        assertEquals("console:Zelda/Link", menu.slices[2].folder!!.slices.first().key)
        val wheels = menu.allWheels()
        assertTrue(wheels.all { it.slices.size <= AmiiboMenu.MAX_SLICES })
        val leaves = wheels.flatMap { it.slices }.filter { it.folder == null }.map { it.key }
        assertEquals(console.size + 1, leaves.size) // + "Rimuovi"
        assertTrue(leaves.containsAll(console.map { AmiiboMenu.CONSOLE_PREFIX + it }))
    }

    @Test
    fun emptyLibraryStillOffersClear() {
        val menu = AmiiboMenu.build(emptyList(), emptyList())
        assertEquals(listOf(AmiiboMenu.KEY_CLEAR), menu.slices.map { it.key })
        assertFalse(menu.slices.first().isFolder)
    }
}
