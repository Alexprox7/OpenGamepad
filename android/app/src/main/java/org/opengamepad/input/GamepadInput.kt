package org.opengamepad.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.opengamepad.protocol.ControllerState

/**
 * Collects input from on-screen controls, a physical controller and motion (tilt steering,
 * shake), and merges them. Motion updates come from the sensor thread.
 */
class GamepadInput {
    private val touch = MutableStateFlow(ControllerState.NEUTRAL)
    private val physical = MutableStateFlow(ControllerState.NEUTRAL)
    private val motion = MutableStateFlow(ControllerState.NEUTRAL)
    private val _merged = MutableStateFlow(ControllerState.NEUTRAL)
    val state: StateFlow<ControllerState> = _merged.asStateFlow()

    /** Physical-controller state only, used for gestures such as waking the screen. */
    val physicalState: StateFlow<ControllerState> = physical.asStateFlow()

    fun touch(transform: (ControllerState) -> ControllerState) {
        touch.update(transform)
        publish()
    }

    fun physical(transform: (ControllerState) -> ControllerState) {
        physical.update(transform)
        publish()
    }

    fun motion(transform: (ControllerState) -> ControllerState) {
        motion.update(transform)
        publish()
    }

    fun resetTouch() {
        touch.value = ControllerState.NEUTRAL
        publish()
    }

    @Synchronized
    private fun publish() {
        _merged.value = ControllerState.merge(ControllerState.merge(touch.value, physical.value), motion.value)
    }
}
