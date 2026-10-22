package org.opengamepad.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.opengamepad.protocol.ControllerState

/** Collects input from on-screen controls and from a physical controller, and merges them. */
class GamepadInput {
    private val touch = MutableStateFlow(ControllerState.NEUTRAL)
    private val physical = MutableStateFlow(ControllerState.NEUTRAL)
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

    fun resetTouch() {
        touch.value = ControllerState.NEUTRAL
        publish()
    }

    private fun publish() {
        _merged.value = ControllerState.merge(touch.value, physical.value)
    }
}
