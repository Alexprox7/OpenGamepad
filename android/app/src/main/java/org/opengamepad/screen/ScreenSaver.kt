package org.opengamepad.screen

import android.app.Activity
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SaverMode {
    /** Black overlay + minimum brightness: on OLED the pixels are really off. */
    BLACK,

    /** Panel switched off through Shizuku (works on LCD too). Falls back to BLACK. */
    PANEL_OFF,
}

enum class SaverState { OFF, BLACK, PANEL_OFF }

/**
 * Power-saving screen while playing. Controls stay active under the black layer.
 * Ways back: volume keys, the controller combo, leaving the app, and in PANEL_OFF
 * mode also the watchdog in [ScreenControlService] and the power button.
 */
class ScreenSaver(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val shizuku: ShizukuScreen,
    private val onMessage: (String) -> Unit,
) {
    private val _state = MutableStateFlow(SaverState.OFF)
    val state: StateFlow<SaverState> = _state

    private var heartbeat: Job? = null

    fun toggle(mode: SaverMode) = if (_state.value == SaverState.OFF) enter(mode) else exit()

    fun enter(mode: SaverMode) {
        if (_state.value != SaverState.OFF) return
        _state.value = SaverState.BLACK
        setBrightness(0f)
        if (mode != SaverMode.PANEL_OFF) return

        scope.launch {
            if (shizuku.setPanel(false)) {
                if (_state.value == SaverState.OFF) {
                    // exit() ran while the call was in flight
                    shizuku.setPanel(true)
                    return@launch
                }
                _state.value = SaverState.PANEL_OFF
                startHeartbeat()
            } else {
                onMessage("Spegnimento pannello non disponibile (Shizuku): uso lo schermo nero")
            }
        }
    }

    fun exit() {
        if (_state.value == SaverState.OFF) return
        val wasPanelOff = _state.value == SaverState.PANEL_OFF
        _state.value = SaverState.OFF
        heartbeat?.cancel()
        heartbeat = null
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        if (wasPanelOff) scope.launch { shizuku.setPanel(true) }
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (shizuku.heartbeat() == false) {
                    // Panel restored by the hardware key: leave the screen saver too.
                    withContext(Dispatchers.Main) { exit() }
                    break
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun setBrightness(value: Float) {
        val window = activity.window
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 500L
    }
}
