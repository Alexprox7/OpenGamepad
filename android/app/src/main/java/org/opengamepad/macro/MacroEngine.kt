package org.opengamepad.macro

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opengamepad.protocol.ControllerState
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

data class KeyboardState(val modifiers: Int = 0, val keys: List<Int> = emptyList())

/** Radial menu on screen: [highlighted] is the slice the stick points at, if any. */
data class RadialView(val menu: RadialMenu, val highlighted: Int?)

/**
 * Sits between the raw input (touch + physical controller) and the transports:
 * detects trigger combos, hides consumed buttons, injects macro output and runs
 * radial menus. Must be used from a single thread (the main thread).
 */
class MacroEngine(
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {

    var config: MacroConfig = MacroConfig.EMPTY
        set(value) {
            field = value
            guarded = value.macros
                .filter { it.trigger.consume && Integer.bitCount(it.trigger.buttons) >= 2 }
                .fold(0) { acc, m -> acc or m.trigger.buttons }
            reset()
        }

    /** Buttons that start a multi-button combo: held back for [MacroConfig.comboWindowMs]. */
    private var guarded = 0
    private val pressedAt = LongArray(16)
    private var previousButtons = 0

    private val _output = MutableStateFlow(ControllerState.NEUTRAL)
    val output: StateFlow<ControllerState> = _output

    private val _keyboard = MutableStateFlow(KeyboardState())
    val keyboard: StateFlow<KeyboardState> = _keyboard

    private val _radial = MutableStateFlow<RadialView?>(null)
    val radial: StateFlow<RadialView?> = _radial

    private var raw = ControllerState.NEUTRAL

    /** Combos currently held, with their pending/running work. */
    private val held = mutableMapOf<Macro, Job?>()

    private var radialMacro: Macro? = null

    // Output injected by running actions
    private val injectedButtons = mutableListOf<Int>()
    private var injectedLt = 0
    private var injectedRt = 0
    private val stickOverrides = mutableMapOf<Stick, Pair<Float, Float>>()
    private val pressedModifiers = mutableListOf<Int>()
    private val pressedKeys = mutableListOf<Int>()

    fun onInput(state: ControllerState) {
        val rising = state.buttons and previousButtons.inv()
        previousButtons = state.buttons
        if (rising and guarded != 0 && config.comboWindowMs > 0) {
            val now = clock()
            for (bit in 0 until 16) if (rising and (1 shl bit) != 0) pressedAt[bit] = now
            // Publish again when the window ends, so the button reaches the game if no combo formed.
            scope.launch {
                delay(config.comboWindowMs)
                publish()
            }
        }
        raw = state
        evaluateCombos()
        updateRadial()
        publish()
    }

    private fun isHeld(trigger: Trigger, s: ControllerState) =
        s.buttons and trigger.buttons == trigger.buttons &&
            (!trigger.leftTrigger || s.leftTrigger > TRIGGER_THRESHOLD) &&
            (!trigger.rightTrigger || s.rightTrigger > TRIGGER_THRESHOLD)

    private fun evaluateCombos() {
        // Longest combos first; a combo contained in an already held one is ignored.
        val active = mutableListOf<Macro>()
        for (macro in config.macros.sortedByDescending { it.trigger.size }) {
            if (!isHeld(macro.trigger, raw)) continue
            if (active.any { contains(it.trigger, macro.trigger) }) continue
            active += macro
        }

        for (macro in held.keys.toList() - active.toSet()) release(macro)
        for (macro in active) if (macro !in held) press(macro)
    }

    private fun contains(outer: Trigger, inner: Trigger) =
        outer.buttons and inner.buttons == inner.buttons &&
            (!inner.leftTrigger || outer.leftTrigger) && (!inner.rightTrigger || outer.rightTrigger)

    private fun press(macro: Macro) {
        // Registered before launching: on Main.immediate the body starts right away.
        held[macro] = null
        held[macro] = scope.launch {
            if (macro.trigger.holdMs > 0) delay(macro.trigger.holdMs)
            fire(macro)
        }
    }

    private suspend fun fire(macro: Macro) {
        val radial = macro.radial?.let { config.radials[it] }
        when {
            radial != null -> {
                radialMacro = macro
                _radial.value = RadialView(radial, null)
                updateRadial()
                publish()
            }
            macro.repeat -> while (scope.isActive && macro in held) {
                run(macro.actions)
                if (macro.actions.none { it is Action.Wait }) delay(MIN_REPEAT_MS)
            }
            // Detached: a short combo tap still completes the whole sequence.
            else -> scope.launch { run(macro.actions) }
        }
    }

    private fun release(macro: Macro) {
        val job = held.remove(macro)
        if (radialMacro == macro) {
            val view = _radial.value
            radialMacro = null
            _radial.value = null
            val slice = view?.highlighted?.let { view.menu.slices.getOrNull(it) }
            if (slice != null) scope.launch { run(slice.actions) }
        } else if (macro.repeat || macro.trigger.holdMs > 0) {
            // Stops turbo, or a hold that did not last long enough.
            job?.cancel()
        }
    }

    private fun updateRadial() {
        val view = _radial.value ?: return
        val (x, y) = stickOf(raw, view.menu.stick)
        if (hypot(x, y) < RADIAL_DEADZONE) return // keep the last choice when the stick recenters
        val count = view.menu.slices.size
        // 0 = up, growing clockwise
        val angle = (atan2(x.toDouble(), y.toDouble()) + 2 * PI) % (2 * PI)
        val index = (angle / (2 * PI / count)).roundToInt() % count
        if (index != view.highlighted) _radial.value = view.copy(highlighted = index)
    }

    private suspend fun run(actions: List<Action>) {
        for (action in actions) {
            when (action) {
                is Action.Press -> hold(action.ms, {
                    injectedButtons += action.buttons
                    if (action.leftTrigger) injectedLt++
                    if (action.rightTrigger) injectedRt++
                }, {
                    injectedButtons -= action.buttons
                    if (action.leftTrigger) injectedLt--
                    if (action.rightTrigger) injectedRt--
                })
                is Action.Keys -> hold(action.ms, {
                    pressedModifiers += action.modifiers
                    pressedKeys += action.keys
                }, {
                    pressedModifiers -= action.modifiers
                    action.keys.forEach { pressedKeys.remove(it) }
                })
                is Action.Move -> hold(action.ms, {
                    stickOverrides[action.stick] = action.x to action.y
                }, {
                    stickOverrides.remove(action.stick)
                })
                is Action.Wait -> delay(action.ms)
            }
        }
    }

    private suspend fun hold(ms: Long, down: () -> Unit, up: () -> Unit) {
        down()
        publish()
        try {
            delay(ms)
        } finally {
            up()
            publish()
        }
    }

    private fun publish() {
        var out = raw
        // Combo starters pressed a moment ago wait for the rest of the combo.
        if (config.comboWindowMs > 0 && raw.buttons and guarded != 0) {
            val now = clock()
            var waiting = 0
            for (bit in 0 until 16) {
                val mask = 1 shl bit
                if (raw.buttons and guarded and mask != 0 && now - pressedAt[bit] < config.comboWindowMs) waiting = waiting or mask
            }
            out = out.copy(buttons = out.buttons and waiting.inv())
        }
        // Hide the buttons of held combos that consume them.
        for (macro in held.keys) {
            val t = macro.trigger
            if (!t.consume) continue
            out = out.copy(
                buttons = out.buttons and t.buttons.inv(),
                leftTrigger = if (t.leftTrigger) 0 else out.leftTrigger,
                rightTrigger = if (t.rightTrigger) 0 else out.rightTrigger,
            )
        }
        // The stick that drives an open radial menu does not reach the game.
        _radial.value?.let { out = withStick(out, it.menu.stick, 0f, 0f) }

        out = out.copy(
            buttons = injectedButtons.fold(out.buttons) { acc, b -> acc or b },
            leftTrigger = if (injectedLt > 0) 255 else out.leftTrigger,
            rightTrigger = if (injectedRt > 0) 255 else out.rightTrigger,
        )
        for ((stick, xy) in stickOverrides) out = withStick(out, stick, xy.first, xy.second)
        _output.value = out

        val keyboard = KeyboardState(
            pressedModifiers.fold(0) { acc, m -> acc or m },
            pressedKeys.distinct().take(6),
        )
        if (keyboard != _keyboard.value) _keyboard.value = keyboard
    }

    private fun reset() {
        held.values.forEach { it?.cancel() }
        held.clear()
        radialMacro = null
        _radial.value = null
        injectedButtons.clear()
        injectedLt = 0
        injectedRt = 0
        stickOverrides.clear()
        pressedModifiers.clear()
        pressedKeys.clear()
        publish()
    }

    private fun stickOf(s: ControllerState, stick: Stick): Pair<Float, Float> = when (stick) {
        Stick.LEFT -> s.leftX / 32767f to s.leftY / 32767f
        Stick.RIGHT -> s.rightX / 32767f to s.rightY / 32767f
    }

    private fun withStick(s: ControllerState, stick: Stick, x: Float, y: Float) = when (stick) {
        Stick.LEFT -> s.copy(leftX = ControllerState.axis(x), leftY = ControllerState.axis(y))
        Stick.RIGHT -> s.copy(rightX = ControllerState.axis(x), rightY = ControllerState.axis(y))
    }

    companion object {
        private const val TRIGGER_THRESHOLD = 128
        private const val RADIAL_DEADZONE = 0.5f
        private const val MIN_REPEAT_MS = 30L
    }
}
