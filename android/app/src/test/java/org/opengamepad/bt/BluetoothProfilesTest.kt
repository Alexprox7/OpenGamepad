package org.opengamepad.bt

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opengamepad.protocol.Buttons
import org.opengamepad.protocol.ControllerState
import java.util.concurrent.CopyOnWriteArrayList

class BluetoothProfilesTest {

    private fun buttons(report: ByteArray) =
        (report[0].toInt() and 0xFF) or ((report[1].toInt() and 0xFF) shl 8)

    @Test
    fun `generic gamepad uses the Android and Linux button slots`() {
        val a = HidDescriptor.gamepadReport(ControllerState(buttons = Buttons.A))
        val x = HidDescriptor.gamepadReport(ControllerState(buttons = Buttons.X))
        val y = HidDescriptor.gamepadReport(ControllerState(buttons = Buttons.Y))
        val start = HidDescriptor.gamepadReport(ControllerState(buttons = Buttons.START))
        val l2 = HidDescriptor.gamepadReport(ControllerState(leftTrigger = 255))
        assertEquals(1 shl 0, buttons(a))   // BTN_A
        assertEquals(1 shl 3, buttons(x))   // BTN_X
        assertEquals(1 shl 4, buttons(y))   // BTN_Y
        assertEquals(1 shl 11, buttons(start)) // BTN_START
        assertEquals(1 shl 8, buttons(l2))  // BTN_TL2
    }

    private val address = byteArrayOf(0x98.toByte(), 0xB6.toByte(), 0xE9.toByte(), 1, 2, 3)
    private val sent = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
    private val profile = SwitchProProfile(address).also { p -> p.attach { id, data -> sent += id to data; true } }

    @After
    fun tearDown() = profile.detach()

    private fun subcommand(command: Int, vararg args: Int): ByteArray {
        sent.clear()
        val data = ByteArray(48)
        data[9] = command.toByte()
        args.forEachIndexed { i, v -> data[10 + i] = v.toByte() }
        profile.onOutputReport(0x01, data)
        return sent.last { it.first == 0x21 }.second
    }

    @Test
    fun `device info reply describes a Pro Controller`() {
        val reply = subcommand(0x02)
        assertEquals(0x82, reply[12].toInt() and 0xFF)
        assertEquals(0x02, reply[13].toInt())
        assertEquals(0x03, reply[16].toInt()) // controller type: Pro Controller
        assertArrayEquals(address, reply.copyOfRange(18, 24))
    }

    @Test
    fun `SPI read returns the stick calibration centred at 2048`() {
        val reply = subcommand(0x10, 0x3D, 0x60, 0x00, 0x00, 18)
        assertEquals(0x90, reply[12].toInt() and 0xFF)
        // echo of address and size, then the data
        assertEquals(0x3D, reply[14].toInt() and 0xFF)
        assertEquals(18, reply[18].toInt())
        val data = reply.copyOfRange(19, 19 + 18)
        val centerX = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0x0F) shl 8)
        val centerY = ((data[4].toInt() and 0xF0) shr 4) or ((data[5].toInt() and 0xFF) shl 4)
        assertEquals(2048, centerX)
        assertEquals(2048, centerY)
    }

    @Test
    fun `buttons follow the Xbox positions`() {
        profile.onState(ControllerState(buttons = Buttons.A or Buttons.START, leftX = 32767))
        val report = subcommand(0x30, 0x01) // any reply carries the input state
        assertEquals(0x04, report[2].toInt() and 0xFF) // bottom button = Switch B
        assertEquals(0x02, report[3].toInt() and 0xFF) // Start = +
        val x = (report[5].toInt() and 0xFF) or ((report[6].toInt() and 0x0F) shl 8)
        assertEquals(2048 + 1400, x)
    }

    @Test
    fun `player lights become a player number`() {
        subcommand(0x30, 0x02)
        assertEquals(2, profile.player.value)
    }

    @Test
    fun `rumble is detected and cleared`() {
        val data = ByteArray(10)
        byteArrayOf(0, 0x28, 0x88.toByte(), 0x60, 0x40, 0x28, 0x88.toByte(), 0x60, 0x40).copyInto(data, 0)
        profile.onOutputReport(0x10, data)
        assertTrue(profile.rumble.value)
        byteArrayOf(0, 0x00, 0x01, 0x40, 0x40, 0x00, 0x01, 0x40, 0x40).copyInto(data, 0)
        profile.onOutputReport(0x10, data)
        assertEquals(false, profile.rumble.value)
    }
}
