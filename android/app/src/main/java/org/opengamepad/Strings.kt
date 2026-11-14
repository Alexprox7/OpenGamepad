package org.opengamepad

import androidx.annotation.StringRes

/**
 * Text in the system language (values/ is English, the fallback; also it, zh-Hans, zh-Hant, ja).
 * Works outside the UI too, through the application context.
 */
fun str(@StringRes id: Int, vararg args: Any): String =
    // shortcut: plain JVM unit tests have no Android resources, so they get a placeholder.
    OpenGamepadApp.instance?.localized?.getString(id, *args) ?: "#$id"
