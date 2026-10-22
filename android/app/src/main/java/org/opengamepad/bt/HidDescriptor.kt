package org.opengamepad.bt

import org.opengamepad.protocol.Buttons
import org.opengamepad.protocol.ControllerState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Composite HID device: report 1 = gamepad, report 2 = keyboard (used by macros to
 * send PC keys). Hosts cache the descriptor when pairing, so changing it requires
 * forgetting and pairing the phone again.
 */
object HidDescriptor {
    const val GAMEPAD_REPORT_ID = 1
    const val KEYBOARD_REPORT_ID = 2

    val DESCRIPTOR: ByteArray = bytes(
        // ---- Gamepad ----
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x05,             // Usage (Game Pad)
        0xA1, 0x01,             // Collection (Application)
        0x85, GAMEPAD_REPORT_ID,
        0x05, 0x09,             //   Usage Page (Button)
        0x19, 0x01, 0x29, 0x10, //   Buttons 1..16
        0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x10,
        0x81, 0x02,             //   Input (Data, Var, Abs)
        0x05, 0x01,             //   Usage Page (Generic Desktop)
        0x09, 0x39,             //   Usage (Hat switch)
        0x15, 0x00, 0x25, 0x07,
        0x35, 0x00, 0x46, 0x3B, 0x01, // Physical 0..315
        0x65, 0x14,             //   Unit (degrees)
        0x75, 0x04, 0x95, 0x01,
        0x81, 0x42,             //   Input (Data, Var, Abs, Null state)
        0x65, 0x00, 0x45, 0x00, //   Unit none, physical max reset
        0x75, 0x04, 0x95, 0x01,
        0x81, 0x03,             //   Input (Const) padding
        0x09, 0x30, 0x09, 0x31, //   X, Y (left stick)
        0x09, 0x32, 0x09, 0x35, //   Z, Rz (right stick)
        0x16, 0x00, 0x80, 0x26, 0xFF, 0x7F, // -32768..32767
        0x75, 0x10, 0x95, 0x04,
        0x81, 0x02,
        0x05, 0x02,             //   Usage Page (Simulation Controls)
        0x09, 0xC5, 0x09, 0xC4, //   Brake (LT), Accelerator (RT)
        0x15, 0x00, 0x26, 0xFF, 0x00,
        0x75, 0x08, 0x95, 0x02,
        0x81, 0x02,
        0xC0,                   // End Collection

        // ---- Keyboard ----
        0x05, 0x01,
        0x09, 0x06,             // Usage (Keyboard)
        0xA1, 0x01,
        0x85, KEYBOARD_REPORT_ID,
        0x05, 0x07,             //   Usage Page (Keyboard)
        0x19, 0xE0, 0x29, 0xE7, //   Modifiers (Ctrl, Shift, Alt, GUI)
        0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x08,
        0x81, 0x02,
        0x75, 0x08, 0x95, 0x01,
        0x81, 0x03,             //   Reserved byte
        0x19, 0x00, 0x29, 0xE7, //   Keys 0..231 (F13-F24 included)
        0x15, 0x00, 0x26, 0xE7, 0x00, // 2-byte logical max: 0xE7 as one byte would be negative
        0x75, 0x08, 0x95, 0x06,
        0x81, 0x00,             //   Input (Data, Array)
        0xC0,
    )

    /** HID button order (1..16) for the Xbox-style buttons. */
    private val BUTTON_ORDER = intArrayOf(
        Buttons.A, Buttons.B, Buttons.X, Buttons.Y,
        Buttons.LEFT_SHOULDER, Buttons.RIGHT_SHOULDER,
        Buttons.BACK, Buttons.START,
        Buttons.LEFT_THUMB, Buttons.RIGHT_THUMB,
        Buttons.GUIDE,
    )

    /** 13-byte gamepad report (without the report id, which sendReport adds). */
    fun gamepadReport(state: ControllerState): ByteArray {
        var hidButtons = 0
        BUTTON_ORDER.forEachIndexed { i, mask -> if (state.isPressed(mask)) hidButtons = hidButtons or (1 shl i) }
        return ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(hidButtons.toShort())
            .put(hat(state).toByte())
            // HID Y axes grow downwards, XInput's grow upwards.
            .putShort(state.leftX)
            .putShort(invert(state.leftY))
            .putShort(state.rightX)
            .putShort(invert(state.rightY))
            .put(state.leftTrigger.toByte())
            .put(state.rightTrigger.toByte())
            .array()
    }

    /** 8-byte keyboard report: modifiers, reserved, up to 6 key usages. */
    fun keyboardReport(modifiers: Int, keys: List<Int>): ByteArray {
        val report = ByteArray(8)
        report[0] = modifiers.toByte()
        keys.take(6).forEachIndexed { i, key -> report[2 + i] = key.toByte() }
        return report
    }

    private fun invert(value: Short): Short = (-(value.toInt()) - 1).coerceIn(-32768, 32767).toShort()

    /** 0 = up, clockwise to 7 = up-left; 8 (outside 0..7) = released. */
    private fun hat(state: ControllerState): Int {
        val up = state.isPressed(Buttons.DPAD_UP)
        val down = state.isPressed(Buttons.DPAD_DOWN)
        val left = state.isPressed(Buttons.DPAD_LEFT)
        val right = state.isPressed(Buttons.DPAD_RIGHT)
        return when {
            up && right -> 1
            right && down -> 3
            down && left -> 5
            left && up -> 7
            up -> 0
            right -> 2
            down -> 4
            left -> 6
            else -> 8
        }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
