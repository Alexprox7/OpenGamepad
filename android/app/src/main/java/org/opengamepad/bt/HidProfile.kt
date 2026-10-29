package org.opengamepad.bt

import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import org.opengamepad.protocol.ControllerState

/** Sends one input report (without the report id byte) to the connected host. */
fun interface HidLink {
    fun send(reportId: Int, data: ByteArray): Boolean
}

/** What the phone pretends to be over Bluetooth HID. */
interface HidProfile {
    val id: String
    val sdp: BluetoothHidDeviceAppSdpSettings

    /** Bluetooth name to advertise while this profile is active; null keeps the phone's name. */
    val deviceName: String? get() = null

    /** A host connected: [link] is valid until [detach]. */
    fun attach(link: HidLink)
    fun detach()

    fun onState(state: ControllerState)
    fun onKeys(modifiers: Int, keys: List<Int>) {}

    /** Latest motion sample for profiles with an IMU; null when gyro passthrough stops. */
    fun onMotion(sample: org.opengamepad.motion.MotionSample?) {}

    /** Answer to a GET_REPORT on the control channel. */
    fun onGetReport(reportId: Int): ByteArray

    /** Output report from the host (interrupt channel or SET_REPORT). */
    fun onOutputReport(reportId: Int, data: ByteArray) {}
}
