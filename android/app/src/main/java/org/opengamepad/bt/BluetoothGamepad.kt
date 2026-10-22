package org.opengamepad.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.opengamepad.protocol.ControllerState
import java.util.concurrent.Executors

/**
 * Phone as a Bluetooth HID gamepad: no app needed on the host (PC, TV, phone).
 * The host sees a generic HID gamepad (DirectInput), not an Xbox controller.
 * Callers must hold BLUETOOTH_CONNECT (Android 12+).
 */
@SuppressLint("MissingPermission")
class BluetoothGamepad(private val context: Context) {

    sealed interface State {
        data object Off : State
        data object Unsupported : State
        data object Registering : State
        /** Registered and waiting: pair from the host or connect to a paired host. */
        data object Ready : State
        data class Connecting(val name: String) : State
        data class Connected(val name: String) : State
        data class Failed(val reason: String) : State
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private val executor = Executors.newSingleThreadExecutor()

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var lastReport: ByteArray? = null

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    val isSupported: Boolean get() = adapter != null

    fun start() {
        val adapter = adapter
        if (adapter == null) {
            _state.value = State.Unsupported
            return
        }
        if (!adapter.isEnabled) {
            _state.value = State.Failed("Bluetooth spento")
            return
        }
        if (hid != null) return
        _state.value = State.Registering
        val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val device = proxy as BluetoothHidDevice
                hid = device
                device.registerApp(SDP, null, QOS, executor, callback)
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                host = null
                _state.value = State.Off
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) _state.value = State.Unsupported
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "app registered=$registered plugged=${pluggedDevice?.address}")
            if (!registered) {
                host = null
                if (_state.value !is State.Off) _state.value = State.Failed("registrazione HID rifiutata")
                return
            }
            _state.value = State.Ready
            // A host that was connected before reconnects by itself; otherwise try it.
            pluggedDevice?.let { hid?.connect(it) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val name = device.name ?: device.address
            Log.i(TAG, "connection $name -> $state")
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> _state.value = State.Connecting(name)
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    lastReport = null
                    _state.value = State.Connected(name)
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host == device) {
                    host = null
                    if (hid != null) _state.value = State.Ready
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val report = when (id.toInt()) {
                HidDescriptor.KEYBOARD_REPORT_ID -> HidDescriptor.keyboardReport(0, emptyList())
                else -> lastReport ?: HidDescriptor.gamepadReport(ControllerState.NEUTRAL)
            }
            hid?.replyReport(device, type, id, report)
        }
    }

    /**
     * Paired devices that can act as host: computers, phones/tablets and TVs
     * (earbuds, watches, speakers and other controllers are left out).
     */
    fun pairedHosts(): List<BluetoothDevice> = adapter?.bondedDevices.orEmpty().filter { device ->
        val btClass = device.bluetoothClass ?: return@filter true
        when (btClass.majorDeviceClass) {
            BluetoothClass.Device.Major.COMPUTER, BluetoothClass.Device.Major.PHONE -> true
            BluetoothClass.Device.Major.AUDIO_VIDEO -> btClass.deviceClass in TV_CLASSES
            BluetoothClass.Device.Major.UNCATEGORIZED -> true
            else -> false
        }
    }

    fun connect(device: BluetoothDevice) {
        hid?.connect(device)
    }

    /** Drops the current host but stays registered (visible and reconnectable). */
    fun disconnectHost() {
        host?.let { hid?.disconnect(it) }
    }

    fun send(state: ControllerState) {
        val device = host ?: return
        val report = HidDescriptor.gamepadReport(state)
        if (report.contentEquals(lastReport)) return
        lastReport = report
        hid?.sendReport(device, HidDescriptor.GAMEPAD_REPORT_ID, report)
    }

    /** Keyboard keys (HID usage codes) and modifier bits; send an empty list to release. */
    fun sendKeys(modifiers: Int, keys: List<Int>) {
        val device = host ?: return
        hid?.sendReport(device, HidDescriptor.KEYBOARD_REPORT_ID, HidDescriptor.keyboardReport(modifiers, keys))
    }

    fun stop() {
        val device = hid ?: return
        host?.let { device.disconnect(it) }
        device.unregisterApp()
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        hid = null
        host = null
        _state.value = State.Off
    }

    companion object {
        private const val TAG = "BluetoothGamepad"

        private val TV_CLASSES = setOf(
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
            BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
            BluetoothClass.Device.AUDIO_VIDEO_VCR,
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY,
        )

        private val SDP = BluetoothHidDeviceAppSdpSettings(
            "OpenGamepad",
            "Gamepad Android",
            "OpenGamepad",
            (BluetoothHidDevice.SUBCLASS1_KEYBOARD.toInt() or BluetoothHidDevice.SUBCLASS2_GAMEPAD.toInt()).toByte(),
            HidDescriptor.DESCRIPTOR,
        )

        // Same values as Remote Gamepad: best effort, ~11 ms latency.
        private val QOS = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250,
            BluetoothHidDeviceAppQosSettings.MAX,
        )
    }
}
