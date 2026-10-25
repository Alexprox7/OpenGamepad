package org.opengamepad.screen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.content.AttributionSource
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Bluetooth calls that need BLUETOOTH_PRIVILEGED, made from the Shizuku process (shell
 * uid, which holds that permission). There is no app context there, so the adapter is
 * created by hand with the shell's identity.
 */
// Runs only in the Shizuku shell process, where hidden API restrictions do not apply.
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "MissingPermission")
object PrivilegedBluetooth {
    private const val TAG = "PrivilegedBluetooth"
    private const val SHELL_PACKAGE = "com.android.shell"

    private val adapter: BluetoothAdapter by lazy { createAdapter() }

    /** Accepts a pending "just works" pairing request, as the Settings dialog would. */
    fun confirmPairing(address: String): Boolean = try {
        val ok = adapter.getRemoteDevice(address).setPairingConfirmation(true)
        Log.i(TAG, "setPairingConfirmation($address) -> $ok")
        ok
    } catch (e: Exception) {
        Log.e(TAG, "confirmPairing failed", e)
        false
    }

    /**
     * Changes the class of device the phone advertises (the Switch only accepts gamepads,
     * 0x002508). Returns the previous class, or -1 if the call failed: Android 14+ removed
     * get/setBluetoothClass, so there it can only be changed with root.
     */
    fun setDeviceClass(deviceClass: Int): Int = try {
        val getter = BluetoothAdapter::class.java.getMethod("getBluetoothClass")
        // BluetoothClass.hashCode() is the raw class value
        val previous = (getter.invoke(adapter) as? BluetoothClass)?.hashCode() ?: -1
        val newClass = BluetoothClass::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(deviceClass)
        val ok = BluetoothAdapter::class.java.getMethod("setBluetoothClass", BluetoothClass::class.java)
            .invoke(adapter, newClass) as Boolean
        Log.i(TAG, "setDeviceClass(0x%06x) -> %s, previous 0x%06x".format(deviceClass, ok, previous))
        if (ok) previous else -1
    } catch (e: Exception) {
        Log.e(TAG, "setDeviceClass failed", e)
        -1
    }

    private fun createAdapter(): BluetoothAdapter {
        if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            return BluetoothAdapter.getDefaultAdapter()
        }
        // App processes get the service manager from ActivityThread; app_process does not.
        runCatching {
            val init = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
            val managerClass = Class.forName("android.os.BluetoothServiceManager")
            init.getDeclaredMethod("setBluetoothServiceManager", managerClass)
                .invoke(null, managerClass.getDeclaredConstructor().newInstance())
        }
        val source = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
        val create = BluetoothAdapter::class.java.getDeclaredMethod("createAdapter", AttributionSource::class.java)
        create.isAccessible = true
        return create.invoke(null, source) as BluetoothAdapter
    }
}
