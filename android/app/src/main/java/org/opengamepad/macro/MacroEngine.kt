package org.opengamepad.macro

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opengamepad.protocol.Buttons
import org.opengamepad.protocol.ControllerState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

data class KeyboardState(val modifiers: Int = 0, val keys: List<Int> = emptyList())

/**
 * Radial menu on screen: [highlighted] is the slice the stick points at, if any.
 * [trail] names the root menu and each folder opened since, the current level last.
 */
data class RadialView(
    val menu: RadialMenu,
    val highlighted: Int?,
    val trail: List<String> = emptyList(),
    val sticky: Boolean = false,
)

/** A pick from a menu registered with [MacroEngine.registerDynamicRadial]. */
data class RadialPick(
    /** Id the dynamic menu was registered with. */
    val menuId: String,
    /** Slices chosen from that menu: the folders opened first, the picked slice last. */
    val path: List<RadialSlice>,
) {
    val slice: RadialSlice get() = path.last()

    /** [RadialSlice.key] of each step (the label when the key is empty). */
    val keys: List<String> get() = path.map { it.key.ifEmpty { it.label } }
}

/** Slice under a direction (x right, y up) on a wheel of [count] slices; slice 0 is up, clockwise. */
fun radialSliceAt(x: Float, y: Float, count: Int): Int {
    val angle = (atan2(x.toDouble(), y.toDouble()) + 2 * PI) % (2 * PI)
    return (angle / (2 * PI / count)).roundToInt() % count
}

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

    /** One level of an open wheel: [id] is null for a runtime-only [RadialSlice.folder]. */
    private class Level(val id: String?, val menu: RadialMenu, val via: RadialSlice?)

    /** An open wheel with its back stack; [macro] is the combo that opened it, if any. */
    private class Session(val macro: Macro?, val sticky: Boolean, val levels: MutableList<Level>)

    private var session: Session? = null

    /** Opens a folder after the stick rests on it ([RadialMode.HOLD] only). */
    private var dwell: Job? = null

    /** After a level change the stick is ignored until it leaves this direction (radians). */
    private var ignoreAngle: Double? = null

    /** Confirm/back presses made on an open wheel: hidden from the game until released. */
    private var swallowed = 0

    private class Dynamic(val provider: () -> RadialMenu?, val onPick: (RadialPick) -> Unit)

    private val dynamic = mutableMapOf<String, Dynamic>()

    // Output injected by running actions
    private val injectedButtons = mutableListOf<Int>()
    private var injectedLt = 0
    private var injectedRt = 0
    private val stickOverrides = mutableMapOf<Stick, Pair<Float, Float>>()
    private val pressedModifiers = mutableListOf<Int>()
    private val pressedKeys = mutableListOf<Int>()

    /**
     * Registers a menu built at runtime. [id] must start with [DYNAMIC_RADIAL_PREFIX]; macros
     * ([Macro.radial]) and folders ([RadialSlice.submenu]) can then point at it. [provider] runs
     * each time the menu opens (null = nothing to show); its slices may be folders, through
     * [RadialSlice.submenu] or an inline [RadialSlice.folder]. A pick inside it is reported to
     * [onPick]; the slice's own actions, if any, still run.
     */
    fun registerDynamicRadial(id: String, provider: () -> RadialMenu?, onPick: (RadialPick) -> Unit) {
        require(id.startsWith(DYNAMIC_RADIAL_PREFIX)) { "dynamic radial ids start with $DYNAMIC_RADIAL_PREFIX" }
        dynamic[id] = Dynamic(provider, onPick)
    }

    fun unregisterDynamicRadial(id: String) {
        dynamic.remove(id)
    }

    val dynamicRadialIds: Set<String> get() = dynamic.keys.toSet()

    /**
     * Opens a radial menu (from the configuration or dynamic) without a combo, e.g. from an
     * on-screen button. A [RadialMode.HOLD] menu stays open until [releaseRadial]; a
     * [RadialMode.STICKY] one until A, B or a tap closes it. Returns false if [id] is unknown.
     */
    fun openRadial(id: String): Boolean = open(id, null).also { publish() }

    /** Ends a [RadialMode.HOLD] menu opened with [openRadial]: runs the highlighted slice or cancels. */
    fun releaseRadial() {
        val s = session ?: return
        if (s.macro == null && !s.sticky) releaseHold()
        publish()
    }

    /** Closes the open wheel without running anything. */
    fun closeRadial() {
        close()
        publish()
    }

    /** A slice tapped on the touchscreen: confirms it like A. */
    fun tapSlice(index: Int) {
        if (session == null) return
        confirm(index)
        publish()
    }

    /** Goes back one level, closing at the root (like B). */
    fun radialBack() {
        back()
        publish()
    }

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
        swallowed = swallowed and state.buttons
        if (session != null) {
            updateRadial() // A confirms what the stick points at in this same frame
            swallowed = swallowed or (rising and WHEEL_BUTTONS)
            if (rising and Buttons.B != 0) back()
            else if (rising and Buttons.A != 0) _radial.value?.highlighted?.let(::confirm)
        }
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
        val s = session
        if (s != null && s.sticky && s.macro == macro) {
            // The combo again on its sticky wheel: confirm, or close with nothing highlighted.
            held[macro] = null
            _radial.value?.highlighted?.let(::confirm) ?: close()
            return
        }
        // Registered before launching: on Main.immediate the body starts right away.
        held[macro] = null
        held[macro] = scope.launch {
            if (macro.trigger.holdMs > 0) delay(macro.trigger.holdMs)
            fire(macro)
        }
    }

    private suspend fun fire(macro: Macro) {
        when {
            macro.radial != null -> if (open(macro.radial, macro)) {
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
        val s = session
        if (s != null && s.macro == macro) {
            if (!s.sticky) releaseHold()
        } else if (macro.repeat || macro.trigger.holdMs > 0) {
            // Stops turbo, or a hold that did not last long enough.
            job?.cancel()
        }
    }

    /** End of a [RadialMode.HOLD] wheel: a highlighted action slice runs, anything else cancels. */
    private fun releaseHold() {
        val s = session ?: return
        val index = _radial.value?.highlighted
        val slice = index?.let { s.levels.last().menu.slices.getOrNull(it) }
        if (slice != null && !slice.isFolder) confirm(index) else close()
    }

    private fun resolve(id: String): RadialMenu? =
        config.radials[id] ?: dynamic[id]?.let { runCatching { it.provider() }.getOrNull() }

    private fun open(id: String, macro: Macro?): Boolean {
        val menu = resolve(id) ?: return false
        close()
        session = Session(macro, menu.mode == RadialMode.STICKY, mutableListOf(Level(id, menu, null)))
        ignoreAngle = null
        show(null)
        return true
    }

    private fun show(highlighted: Int?) {
        val s = session ?: return
        _radial.value = RadialView(
            menu = s.levels.last().menu,
            highlighted = highlighted,
            trail = s.levels.map { level -> level.via?.label ?: level.menu.id.removePrefix(DYNAMIC_RADIAL_PREFIX) },
            sticky = s.sticky,
        )
    }

    private fun close() {
        dwell?.cancel()
        dwell = null
        session = null
        _radial.value = null
    }

    private fun back() {
        val s = session ?: return
        if (s.levels.size <= 1) return close()
        dwell?.cancel()
        s.levels.removeAt(s.levels.lastIndex)
        ignoreAngle = currentAngle(s)
        show(null)
    }

    private fun openFolder(index: Int) {
        val s = session ?: return
        val slice = s.levels.last().menu.slices.getOrNull(index) ?: return
        if (s.levels.size >= MAX_DEPTH) return
        val child = slice.folder ?: slice.submenu?.let(::resolve) ?: return
        dwell?.cancel()
        s.levels += Level(if (slice.folder != null) null else slice.submenu, child, slice)
        ignoreAngle = currentAngle(s)
        show(null)
    }

    /** Runs or opens a slice of the current level. */
    private fun confirm(index: Int) {
        val s = session ?: return
        val slice = s.levels.last().menu.slices.getOrNull(index) ?: return
        if (slice.isFolder) return openFolder(index)

        // Reported to the nearest dynamic menu the pick was made in.
        val owner = s.levels.indexOfLast { it.id != null && it.id in dynamic }
        val pick = if (owner >= 0) {
            val id = s.levels[owner].id!!
            RadialPick(id, s.levels.drop(owner + 1).mapNotNull { it.via } + slice)
        } else null
        close()
        if (slice.actions.isNotEmpty()) scope.launch { run(slice.actions) }
        pick?.let { dynamic[it.menuId]?.onPick?.invoke(it) }
    }

    private fun currentAngle(s: Session): Double? {
        val (x, y) = stickOf(raw, s.levels.last().menu.stick)
        if (hypot(x, y) < RADIAL_DEADZONE) return null
        return atan2(x.toDouble(), y.toDouble())
    }

    private fun updateRadial() {
        val view = _radial.value ?: return
        val s = session ?: return
        val count = view.menu.slices.size
        if (count == 0) return
        val (x, y) = stickOf(raw, view.menu.stick)
        if (hypot(x, y) < RADIAL_DEADZONE) {
            // Keep the last choice when the stick recenters.
            ignoreAngle = null
            return
        }
        ignoreAngle?.let { ignored ->
            val diff = abs(atan2(x.toDouble(), y.toDouble()) - ignored) % (2 * PI)
            if (min(diff, 2 * PI - diff) < IGNORE_ARC) return
            ignoreAngle = null
        }
        val index = radialSliceAt(x, y, count)
        if (index == view.highlighted) return
        _radial.value = view.copy(highlighted = index)

        dwell?.cancel()
        dwell = null
        if (!s.sticky && view.menu.slices[index].isFolder) {
            val level = s.levels.last()
            dwell = scope.launch {
                delay(FOLDER_DWELL_MS)
                if (session === s && s.levels.last() === level && _radial.value?.highlighted == index) {
                    openFolder(index)
                    publish()
                }
            }
        }
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
        out = out.copy(buttons = out.buttons and swallowed.inv())
        // The sticks that drive an open radial menu do not reach the game.
        session?.levels?.forEach { out = withStick(out, it.menu.stick, 0f, 0f) }

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
        close()
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
        const val FOLDER_DWELL_MS = 400L
        private const val MAX_DEPTH = 8
        private const val IGNORE_ARC = PI / 6
        private const val WHEEL_BUTTONS = Buttons.A or Buttons.B
    }
}
