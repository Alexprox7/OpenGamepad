package org.opengamepad

import android.content.Context
import org.opengamepad.screen.SaverMode
import java.security.SecureRandom

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("opengamepad", Context.MODE_PRIVATE)

    /** Random identifier sent in the handshake, stable across launches like the original. */
    val deviceId: Long
        get() {
            if (!prefs.contains(KEY_DEVICE_ID)) {
                prefs.edit().putLong(KEY_DEVICE_ID, SecureRandom().nextLong()).apply()
            }
            return prefs.getLong(KEY_DEVICE_ID, 0L)
        }

    var lastTarget: String
        get() = prefs.getString(KEY_LAST_TARGET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_TARGET, value).apply()

    var saverMode: SaverMode
        get() = runCatching { SaverMode.valueOf(prefs.getString(KEY_SAVER_MODE, null) ?: "") }
            .getOrDefault(SaverMode.BLACK)
        set(value) = prefs.edit().putString(KEY_SAVER_MODE, value.name).apply()

    var macroJson: String
        get() = prefs.getString(KEY_MACROS, null) ?: org.opengamepad.macro.MacroConfig.EXAMPLE
        set(value) = prefs.edit().putString(KEY_MACROS, value).apply()

    /** Bluetooth gamepad mode stays on across launches once enabled. */
    var bluetoothEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLUETOOTH, false)
        set(value) = prefs.edit().putBoolean(KEY_BLUETOOTH, value).apply()

    private companion object {
        const val KEY_BLUETOOTH = "bluetooth_enabled"
        const val KEY_MACROS = "macros_v2"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LAST_TARGET = "last_target"
        const val KEY_SAVER_MODE = "saver_mode"
    }
}
