package org.opengamepad

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import org.opengamepad.bt.BluetoothGamepad
import java.util.Locale

class OpenGamepadApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        applyLanguage(Prefs(this).language)
    }

    /** One HID registration per process: it outlives the activity while Bluetooth mode is on. */
    val bluetooth: BluetoothGamepad by lazy { BluetoothGamepad(this) }

    /** Texts come from here: the app itself (system language) or a copy set to the chosen language. */
    var localized: Context = this
        private set

    /** [tag]: "" for the system language, otherwise a BCP 47 tag such as "it" or "zh-Hant". */
    fun applyLanguage(tag: String) {
        localized = if (tag.isEmpty()) this else createConfigurationContext(
            Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
        )
    }

    companion object {
        /** Set before any activity or service starts; null only in plain JVM unit tests. */
        var instance: OpenGamepadApp? = null
            private set

        /** Languages offered in the settings, named in their own language; "" = system. */
        val LANGUAGES = listOf("en" to "English", "it" to "Italiano", "zh-Hans" to "简体中文", "zh-Hant" to "繁體中文", "ja" to "日本語")
    }
}
