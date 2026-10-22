package org.opengamepad.macro

import org.opengamepad.protocol.Buttons

/** Names used in the macro configuration for controller buttons and PC keys. */
object Names {
    /** Controller buttons; LT/RT are treated as fully pressed triggers. */
    val BUTTONS: Map<String, Int> = mapOf(
        "A" to Buttons.A, "B" to Buttons.B, "X" to Buttons.X, "Y" to Buttons.Y,
        "LB" to Buttons.LEFT_SHOULDER, "RB" to Buttons.RIGHT_SHOULDER,
        "BACK" to Buttons.BACK, "START" to Buttons.START, "GUIDE" to Buttons.GUIDE,
        "L3" to Buttons.LEFT_THUMB, "R3" to Buttons.RIGHT_THUMB,
        "UP" to Buttons.DPAD_UP, "DOWN" to Buttons.DPAD_DOWN,
        "LEFT" to Buttons.DPAD_LEFT, "RIGHT" to Buttons.DPAD_RIGHT,
    )
    const val LT = "LT"
    const val RT = "RT"

    /** Modifier keys → HID modifier bit. */
    val MODIFIERS: Map<String, Int> = mapOf(
        "CTRL" to 0x01, "LCTRL" to 0x01, "SHIFT" to 0x02, "LSHIFT" to 0x02,
        "ALT" to 0x04, "LALT" to 0x04, "WIN" to 0x08, "LWIN" to 0x08,
        "RCTRL" to 0x10, "RSHIFT" to 0x20, "RALT" to 0x40, "ALTGR" to 0x40, "RWIN" to 0x80,
    )

    /** PC keys → HID keyboard usage. */
    val KEYS: Map<String, Int> = buildMap {
        ('A'..'Z').forEachIndexed { i, c -> put(c.toString(), 0x04 + i) }
        ('1'..'9').forEachIndexed { i, c -> put(c.toString(), 0x1E + i) }
        put("0", 0x27)
        put("ENTER", 0x28); put("ESC", 0x29); put("BACKSPACE", 0x2A); put("TAB", 0x2B)
        put("SPACE", 0x2C); put("MINUS", 0x2D); put("EQUAL", 0x2E)
        put("LBRACKET", 0x2F); put("RBRACKET", 0x30); put("BACKSLASH", 0x31)
        put("SEMICOLON", 0x33); put("QUOTE", 0x34); put("GRAVE", 0x35)
        put("COMMA", 0x36); put("PERIOD", 0x37); put("SLASH", 0x38); put("CAPSLOCK", 0x39)
        (1..12).forEach { put("F$it", 0x3A + it - 1) }
        (13..24).forEach { put("F$it", 0x68 + it - 13) }
        put("PRINTSCREEN", 0x46); put("SCROLLLOCK", 0x47); put("PAUSE", 0x48)
        put("INSERT", 0x49); put("HOME", 0x4A); put("PAGEUP", 0x4B)
        put("DELETE", 0x4C); put("END", 0x4D); put("PAGEDOWN", 0x4E)
        put("ARROWRIGHT", 0x4F); put("ARROWLEFT", 0x50); put("ARROWDOWN", 0x51); put("ARROWUP", 0x52)
        put("NUMLOCK", 0x53)
    }
}
