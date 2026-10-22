package org.opengamepad.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.opengamepad.protocol.Buttons
import org.opengamepad.protocol.ControllerState
import kotlin.math.abs
import kotlin.math.roundToInt

/** Translates Android gamepad events (e.g. GameSir G8) into the controller state. */
class PhysicalGamepad(private val input: GamepadInput) {

    fun onKey(event: KeyEvent): Boolean {
        if (!isGamepad(event.source)) return false
        if (event.keyCode in DPAD_KEYS) return onDpadKey(event)
        val button = KEY_MAP[event.keyCode] ?: return triggerKey(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> input.physical { it.with(button, true) }
            KeyEvent.ACTION_UP -> input.physical { it.with(button, false) }
        }
        return true
    }

    /** Some controllers report triggers only as L2/R2 key presses. */
    private fun triggerKey(event: KeyEvent): Boolean {
        val pressed = event.action == KeyEvent.ACTION_DOWN
        val value = if (pressed) 255 else 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> input.physical { it.copy(leftTrigger = value) }
            KeyEvent.KEYCODE_BUTTON_R2 -> input.physical { it.copy(rightTrigger = value) }
            else -> return false
        }
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!isGamepad(event.source) || event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device
        fun axis(code: Int): Float {
            val value = event.getAxisValue(code)
            val flat = device?.getMotionRange(code, event.source)?.flat ?: 0f
            return if (abs(value) <= flat) 0f else value
        }

        val hatX = axis(MotionEvent.AXIS_HAT_X)
        val hatY = axis(MotionEvent.AXIS_HAT_Y)
        val leftTrigger = maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))
        val rightTrigger = maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))

        input.physical { state ->
            var buttons = state.buttons and DPAD_MASK.inv()
            if (hatX < -0.5f) buttons = buttons or Buttons.DPAD_LEFT
            if (hatX > 0.5f) buttons = buttons or Buttons.DPAD_RIGHT
            if (hatY < -0.5f) buttons = buttons or Buttons.DPAD_UP
            if (hatY > 0.5f) buttons = buttons or Buttons.DPAD_DOWN
            // Keys reported as KEYCODE_DPAD_* are kept as well
            buttons = buttons or (keyDpad and DPAD_MASK)
            state.copy(
                buttons = buttons,
                leftX = ControllerState.axis(axis(MotionEvent.AXIS_X)),
                leftY = ControllerState.axis(-axis(MotionEvent.AXIS_Y)),
                rightX = ControllerState.axis(axis(MotionEvent.AXIS_Z)),
                rightY = ControllerState.axis(-axis(MotionEvent.AXIS_RZ)),
                leftTrigger = (leftTrigger * 255).roundToInt(),
                rightTrigger = (rightTrigger * 255).roundToInt(),
            )
        }
        return true
    }

    private var keyDpad = 0

    private fun onDpadKey(event: KeyEvent): Boolean {
        val button = DPAD_KEYS[event.keyCode] ?: return false
        keyDpad = if (event.action == KeyEvent.ACTION_DOWN) keyDpad or button else keyDpad and button.inv()
        input.physical { it.with(button, event.action == KeyEvent.ACTION_DOWN) }
        return true
    }

    companion object {
        private const val DPAD_MASK =
            Buttons.DPAD_UP or Buttons.DPAD_DOWN or Buttons.DPAD_LEFT or Buttons.DPAD_RIGHT

        fun isGamepad(source: Int) =
            source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

        private val KEY_MAP = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to Buttons.A,
            KeyEvent.KEYCODE_BUTTON_B to Buttons.B,
            KeyEvent.KEYCODE_BUTTON_X to Buttons.X,
            KeyEvent.KEYCODE_BUTTON_Y to Buttons.Y,
            KeyEvent.KEYCODE_BUTTON_L1 to Buttons.LEFT_SHOULDER,
            KeyEvent.KEYCODE_BUTTON_R1 to Buttons.RIGHT_SHOULDER,
            KeyEvent.KEYCODE_BUTTON_THUMBL to Buttons.LEFT_THUMB,
            KeyEvent.KEYCODE_BUTTON_THUMBR to Buttons.RIGHT_THUMB,
            KeyEvent.KEYCODE_BUTTON_START to Buttons.START,
            KeyEvent.KEYCODE_BUTTON_SELECT to Buttons.BACK,
            KeyEvent.KEYCODE_BACK to Buttons.BACK,
            KeyEvent.KEYCODE_BUTTON_MODE to Buttons.GUIDE,
        )

        private val DPAD_KEYS = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to Buttons.DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to Buttons.DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to Buttons.DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to Buttons.DPAD_RIGHT,
        )
    }
}
