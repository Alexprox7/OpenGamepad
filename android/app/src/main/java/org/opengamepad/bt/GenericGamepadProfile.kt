package org.opengamepad.bt

import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import org.opengamepad.protocol.ControllerState

/**
 * Standard HID gamepad + keyboard: PCs, Macs, Linux, Android phones/tablets and
 * Android/Google TV use it without extra software.
 */
class GenericGamepadProfile : HidProfile {
    override val id = ID

    override val sdp = BluetoothHidDeviceAppSdpSettings(
        "OpenGamepad",
        "Gamepad Android",
        "OpenGamepad",
        (BluetoothHidDevice.SUBCLASS1_KEYBOARD.toInt() or BluetoothHidDevice.SUBCLASS2_GAMEPAD.toInt()).toByte(),
        HidDescriptor.DESCRIPTOR,
    )

    @Volatile private var link: HidLink? = null
    @Volatile private var lastReport: ByteArray? = null

    override fun attach(link: HidLink) {
        this.link = link
        lastReport = null
    }

    override fun detach() {
        link = null
    }

    override fun onState(state: ControllerState) {
        val link = link ?: return
        val report = HidDescriptor.gamepadReport(state)
        if (report.contentEquals(lastReport)) return
        lastReport = report
        link.send(HidDescriptor.GAMEPAD_REPORT_ID, report)
    }

    override fun onKeys(modifiers: Int, keys: List<Int>) {
        link?.send(HidDescriptor.KEYBOARD_REPORT_ID, HidDescriptor.keyboardReport(modifiers, keys))
    }

    override fun onGetReport(reportId: Int): ByteArray = when (reportId) {
        HidDescriptor.KEYBOARD_REPORT_ID -> HidDescriptor.keyboardReport(0, emptyList())
        else -> lastReport ?: HidDescriptor.gamepadReport(ControllerState.NEUTRAL)
    }

    companion object {
        const val ID = "generic"
    }
}
