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

    /** Bluetooth profile id: GenericGamepadProfile.ID or SwitchProProfile.ID. */
    var bluetoothProfile: String
        get() = prefs.getString(KEY_BLUETOOTH_PROFILE, null) ?: org.opengamepad.bt.GenericGamepadProfile.ID
        set(value) = prefs.edit().putString(KEY_BLUETOOTH_PROFILE, value).apply()

    /** Class of device the phone had before Switch mode changed it, or -1. */
    var originalDeviceClass: Int
        get() = prefs.getInt(KEY_ORIGINAL_CLASS, -1)
        set(value) = prefs.edit().putInt(KEY_ORIGINAL_CLASS, value).apply()

    /** Motion options as JSON (MotionSettings); missing means everything off. */
    var motionJson: String?
        get() = prefs.getString(KEY_MOTION, null)
        set(value) = prefs.edit().putString(KEY_MOTION, value).apply()

    /** Phone vibration for the game's rumble (companions, Bluetooth Pro Controller). */
    var rumble: Boolean
        get() = prefs.getBoolean(KEY_RUMBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_RUMBLE, value).apply()

    var orientation: ScreenOrientation
        get() = runCatching { ScreenOrientation.valueOf(prefs.getString(KEY_ORIENTATION, null)!!) }
            .getOrDefault(ScreenOrientation.LANDSCAPE)
        set(value) = prefs.edit().putString(KEY_ORIENTATION, value.name).apply()

    /** Colour theme: dark (default) or OLED black. */
    var theme: org.opengamepad.ui.AppTheme
        get() = runCatching { org.opengamepad.ui.AppTheme.valueOf(prefs.getString(KEY_THEME, null)!!) }
            .getOrDefault(org.opengamepad.ui.AppTheme.DARK)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /** App language: "" follows the system, otherwise a tag from OpenGamepadApp.LANGUAGES. */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, null) ?: ""
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** The sensor's centring hint was shown once. */
    var motionHintShown: Boolean
        get() = prefs.getBoolean(KEY_MOTION_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_MOTION_HINT, value).apply()

    private companion object {
        const val KEY_ORIENTATION = "orientation"
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
        const val KEY_MOTION_HINT = "motion_hint"
        const val KEY_RUMBLE = "rumble"
        const val KEY_MOTION = "motion"
        const val KEY_ORIGINAL_CLASS = "original_device_class"
        const val KEY_BLUETOOTH_PROFILE = "bluetooth_profile"
        const val KEY_BLUETOOTH = "bluetooth_enabled"
        const val KEY_MACROS = "macros_v2"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LAST_TARGET = "last_target"
        const val KEY_SAVER_MODE = "saver_mode"
    }
}

/** Screen orientation of the app. [LANDSCAPE]: cameras on the left, USB-C on the right. */
enum class ScreenOrientation(val android: Int) {
    LANDSCAPE(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    REVERSE(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
    /** Turns with the phone only when Android's auto-rotate is on. */
    SYSTEM(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE),
    /** Game Boy-style vertical layout. */
    PORTRAIT(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
}
