package org.opengamepad.bt

import org.opengamepad.str
import org.opengamepad.R
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.opengamepad.protocol.ControllerState
import java.util.concurrent.Executors

/**
 * Phone as a Bluetooth HID device: no app needed on the host. What the host sees is
 * decided by the [HidProfile] (generic gamepad + keyboard, or a Switch Pro Controller).
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
    private val prefs = context.getSharedPreferences("bluetooth", Context.MODE_PRIVATE)

    /** Changing it while registered re-registers with the new profile. */
    var profile: HidProfile = GenericGamepadProfile()
        set(value) {
            if (value.id == field.id) return
            val wasOn = hid != null
            if (wasOn) stop()
            field = value
            if (wasOn) start()
        }

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
            _state.value = State.Failed(str(R.string.bt_off))
            return
        }
        if (hid != null) return
        applyName()
        _state.value = State.Registering
        val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val device = proxy as BluetoothHidDevice
                hid = device
                device.registerApp(this@BluetoothGamepad.profile.sdp, null, QOS, executor, callback)
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
                if (_state.value !is State.Off) _state.value = State.Failed(str(R.string.hid_rejected))
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
                    profile.attach { id, data -> hid?.sendReport(device, id, data) ?: false }
                    _state.value = State.Connected(name)
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host == device) {
                    host = null
                    profile.detach()
                    if (hid != null) _state.value = State.Ready
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hid?.replyReport(device, type, id, profile.onGetReport(id.toInt() and 0xFF))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            profile.onOutputReport(id.toInt() and 0xFF, data)
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            profile.onOutputReport(reportId.toInt() and 0xFF, data)
        }
    }

    /**
     * Some hosts only accept specific names (the Switch wants "Pro Controller"). The
     * phone's own name is saved first, so [restoreName] works even after a crash.
     */
    private fun applyName() {
        val adapter = adapter ?: return
        val wanted = profile.deviceName
        if (wanted == null) {
            restoreName()
            return
        }
        if (!prefs.contains(KEY_ORIGINAL_NAME)) {
            prefs.edit().putString(KEY_ORIGINAL_NAME, adapter.name).apply()
        }
        if (adapter.name != wanted) adapter.setName(wanted)
    }

    /** Puts back the phone's Bluetooth name if a profile changed it. */
    fun restoreName() {
        val original = prefs.getString(KEY_ORIGINAL_NAME, null) ?: return
        adapter?.takeIf { it.name != original }?.setName(original)
        prefs.edit().remove(KEY_ORIGINAL_NAME).apply()
    }

    /**
     * Paired devices that can act as host: computers, phones/tablets and TVs
     * (earbuds, watches, speakers and other controllers are left out).
     */
    fun pairedHosts(): List<BluetoothDevice> = adapter?.bondedDevices.orEmpty().filter { device ->
        if (device.name?.contains("Switch", ignoreCase = true) == true) return@filter true
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
        profile.onState(state)
    }

    fun sendMotion(sample: org.opengamepad.motion.MotionSample?) {
        profile.onMotion(sample)
    }

    /** Keyboard keys (HID usage codes) and modifier bits; send an empty list to release. */
    fun sendKeys(modifiers: Int, keys: List<Int>) {
        if (host != null) profile.onKeys(modifiers, keys)
    }

    fun stop() {
        val device = hid ?: return
        host?.let { device.disconnect(it) }
        device.unregisterApp()
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        hid = null
        host = null
        profile.detach()
        restoreName()
        _state.value = State.Off
    }

    companion object {
        private const val KEY_ORIGINAL_NAME = "original_name"
        private const val TAG = "BluetoothGamepad"

        private val TV_CLASSES = setOf(
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
            BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
            BluetoothClass.Device.AUDIO_VIDEO_VCR,
            BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY,
        )


        // Same values as Remote Gamepad: best effort, ~11 ms latency.
        private val QOS = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250,
            BluetoothHidDeviceAppQosSettings.MAX,
        )
    }
}
