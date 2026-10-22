package org.opengamepad.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Xbox 360 style state. Button bits match XINPUT_GAMEPAD_* / ViGEm XUSB_REPORT.
 * Sticks: -32768..32767, up is positive. Triggers: 0..255.
 */
data class ControllerState(
    val buttons: Int = 0,
    val leftX: Short = 0,
    val leftY: Short = 0,
    val rightX: Short = 0,
    val rightY: Short = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
) {
    fun isPressed(button: Int) = buttons and button != 0

    fun with(button: Int, pressed: Boolean) =
        copy(buttons = if (pressed) buttons or button else buttons and button.inv())

    /** 12-byte payload of a type-1 packet. */
    fun encode(): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(buttons.toShort())
            .putShort(leftX)
            .putShort(leftY)
            .putShort(rightX)
            .putShort(rightY)
            .put(leftTrigger.coerceIn(0, 255).toByte())
            .put(rightTrigger.coerceIn(0, 255).toByte())
            .array()

    companion object {
        val NEUTRAL = ControllerState()

        /** Combines two sources: buttons are OR-ed, for each axis the larger deflection wins. */
        fun merge(a: ControllerState, b: ControllerState) = ControllerState(
            buttons = a.buttons or b.buttons,
            leftX = stronger(a.leftX, b.leftX),
            leftY = stronger(a.leftY, b.leftY),
            rightX = stronger(a.rightX, b.rightX),
            rightY = stronger(a.rightY, b.rightY),
            leftTrigger = maxOf(a.leftTrigger, b.leftTrigger),
            rightTrigger = maxOf(a.rightTrigger, b.rightTrigger),
        )

        private fun stronger(a: Short, b: Short): Short =
            if (kotlin.math.abs(a.toInt()) >= kotlin.math.abs(b.toInt())) a else b

        /** Converts -1f..1f to the signed 16-bit stick range. */
        fun axis(value: Float): Short =
            (value.coerceIn(-1f, 1f) * if (value >= 0) 32767f else 32768f).toInt().toShort()
    }
}

object Buttons {
    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LEFT_THUMB = 0x0040
    const val RIGHT_THUMB = 0x0080
    const val LEFT_SHOULDER = 0x0100
    const val RIGHT_SHOULDER = 0x0200
    const val GUIDE = 0x0400
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000
}
