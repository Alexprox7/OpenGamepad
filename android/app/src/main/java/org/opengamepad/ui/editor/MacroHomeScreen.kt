package org.opengamepad.ui.editor

import org.opengamepad.str
import org.opengamepad.R
import org.opengamepad.ui.Faint
import org.opengamepad.ui.Ink
import org.opengamepad.ui.KeyCaps
import org.opengamepad.ui.ListRow
import org.opengamepad.ui.LocalPalette
import org.opengamepad.ui.Muted
import org.opengamepad.ui.ScreenHeader
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import org.opengamepad.macro.Action
import org.opengamepad.macro.Macro
import org.opengamepad.macro.MacroConfig
import org.opengamepad.macro.DYNAMIC_RADIAL_PREFIX
import org.opengamepad.macro.RadialMenu
import org.opengamepad.macro.RadialMode
import org.opengamepad.protocol.ControllerState
import org.opengamepad.ui.RadialWheel

private sealed interface Editing {
    data class MacroAt(val index: Int) : Editing // -1 = new
    data class Radial(val id: String?) : Editing // null = new
}

/**
 * Entry point of the visual editor: lists macros and radial menus.
 * [dynamicRadials]: menus built at runtime, offered as wheels to open and as folder targets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MacroHomeScreen(
    config: MacroConfig,
    rawInput: StateFlow<ControllerState>,
    onConfig: (MacroConfig) -> Unit,
    onAdvanced: () -> Unit,
    onClose: () -> Unit,
    dynamicRadials: Set<String> = emptySet(),
) {
    // Screens opened on top of the list; a sub-wheel is edited on top of its parent.
    var stack by remember { mutableStateOf(listOf<Editing>()) }
    fun pop() { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.isEmpty(), onBack = onClose)
    val radialIds = config.radials.keys.toList() + dynamicRadials.sorted()

    when (val e = stack.lastOrNull()) {
        is Editing.MacroAt -> {
            MacroEditScreen(
                initial = config.macros.getOrNull(e.index) ?: newMacro(),
                radialIds = radialIds,
                rawInput = rawInput,
                onSave = { macro ->
                    val list = config.macros.toMutableList()
                    if (e.index in list.indices) list[e.index] = macro else list += macro
                    onConfig(config.copy(macros = list))
                    pop()
                },
                onDelete = if (e.index >= 0) {
                    { onConfig(config.copy(macros = config.macros.filterIndexed { i, _ -> i != e.index })); pop() }
                } else null,
                onCancel = ::pop,
            )
            return
        }
        is Editing.Radial -> key(stack.size, e.id) {
            val existing = e.id?.let { config.radials[it] }
            RadialEditScreen(
                initial = existing ?: newRadial(uniqueId(config)),
                takenIds = config.radials.keys,
                folderTargets = radialIds,
                trail = stack.filterIsInstance<Editing.Radial>().map { it.id ?: str(R.string.new_trail) },
                onSave = { menu -> onConfig(saveRadial(config, e.id, menu)); pop() },
                onDelete = if (existing != null) {
                    { onConfig(deleteRadial(config, existing.id)); pop() }
                } else null,
                onCancel = ::pop,
                onOpenSubmenu = { menu, index, target ->
                    var updated = saveRadial(config, e.id, menu)
                    val child = target ?: uniqueId(updated, menu.slices[index].label)
                    if (target == null) {
                        updated = saveRadial(updated, null, newRadial(child).copy(stick = menu.stick, mode = menu.mode))
                        val slices = menu.slices.toMutableList().also { it[index] = it[index].copy(submenu = child) }
                        updated = saveRadial(updated, menu.id, menu.copy(slices = slices))
                    }
                    onConfig(updated)
                    // A new parent now has its id: come back to it by name.
                    stack = stack.dropLast(1) + Editing.Radial(menu.id) + Editing.Radial(child)
                },
            )
            return
        }
        null -> Unit
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LocalPalette.current.background)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(str(R.string.nav_macros), onBack = onClose) {
            OutlinedButton(onClick = onAdvanced) { Text(str(R.string.advanced)) }
        }

        // Macros
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.align(Alignment.CenterVertically)) {
                Text(str(R.string.nav_macros_short), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(str(R.string.macros_sub), color = Muted, fontSize = 12.sp)
            }
            Button(onClick = { stack = listOf(Editing.MacroAt(-1)) }) { Text(str(R.string.new_macro)) }
        }
        if (config.macros.isEmpty()) EmptyHint(str(R.string.no_macros))
        config.macros.forEachIndexed { index, macro ->
            ListRow(onClick = { stack = listOf(Editing.MacroAt(index)) }) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(macro.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    KeyCaps(describeButtons(macro.trigger.buttons, macro.trigger.leftTrigger, macro.trigger.rightTrigger))
                    Text("→ " + describeOutput(macro), color = Muted, fontSize = 13.sp)
                }
                Text("›", color = Faint, fontSize = 24.sp)
            }
        }

        // Radial menus
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.align(Alignment.CenterVertically)) {
                Text(str(R.string.wheels), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(str(R.string.wheels_sub), color = Muted, fontSize = 12.sp)
            }
            Button(onClick = { stack = listOf(Editing.Radial(null)) }) { Text(str(R.string.new_wheel)) }
        }
        if (config.radials.isEmpty()) EmptyHint(str(R.string.no_wheels))
        config.radials.values.forEach { menu ->
            val opener = config.macros.firstOrNull { it.radial == menu.id }
            val parent = config.radials.values.firstOrNull { r -> r.slices.any { it.submenu == menu.id } }
            ListRow(onClick = { stack = listOf(Editing.Radial(menu.id)) }) {
                RadialWheel(menu, null, Modifier.size(96.dp), scale = 0.35f)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(menu.id, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        str(R.string.wheel_info, menu.slices.size, str(if (menu.mode == RadialMode.STICKY) R.string.mode_sticky else R.string.mode_hold)),
                        color = Muted,
                        fontSize = 13.sp,
                    )
                    when {
                        opener != null -> KeyCaps(
                            describeButtons(opener.trigger.buttons, opener.trigger.leftTrigger, opener.trigger.rightTrigger),
                        )
                        parent != null -> Text(str(R.string.sub_wheel_of, parent.id), color = Muted, fontSize = 13.sp)
                        else -> Text(str(R.string.no_combo), color = Faint, fontSize = 13.sp)
                    }
                }
                Text("›", color = Faint, fontSize = 24.sp)
            }
        }

        // Combo window
        Spacer(Modifier.height(4.dp))
        org.opengamepad.ui.Card("⏱", str(R.string.combo_window), str(R.string.combo_window_sub), Modifier.fillMaxWidth()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0L, 30L, 50L, 80L, 120L).forEach { ms ->
                    FilterChip(
                        selected = config.comboWindowMs == ms,
                        onClick = { onConfig(config.copy(comboWindowMs = ms)) },
                        label = { Text(if (ms == 0L) str(R.string.off) else "$ms ms") },
                    )
                }
            }
            Text(
                str(R.string.combo_window_note),
                color = Faint,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, color = Faint, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
}

private fun describeOutput(macro: Macro): String {
    macro.radial?.let { return str(R.string.out_wheel, it) }
    val steps = macro.actions.joinToString(", ") { a ->
        when (a) {
            is Action.Press -> describeButtons(a.buttons, a.leftTrigger, a.rightTrigger)
            is Action.Keys -> describeKeys(a.modifiers, a.keys)
            is Action.Move -> str(if (a.stick.name == "LEFT") R.string.stick_left else R.string.stick_right)
            is Action.Wait -> str(R.string.out_wait, a.ms)
        }
    }
    val text = steps.ifEmpty { str(R.string.no_action) }
    return if (macro.repeat) str(R.string.out_turbo, text) else text
}

/** [base] when free (e.g. the label of the folder slice), otherwise "ruota N". */
private fun uniqueId(config: MacroConfig, base: String = ""): String =
    base.trim().takeIf { it.isNotEmpty() && it !in config.radials && !it.startsWith(DYNAMIC_RADIAL_PREFIX) }
        ?: generateSequence(1) { it + 1 }.map { str(R.string.wheel_n, it) }.first { it !in config.radials }

/** Saves a radial menu, renaming references when its name changed. */
private fun saveRadial(config: MacroConfig, oldId: String?, menu: RadialMenu): MacroConfig {
    val radials = LinkedHashMap<String, RadialMenu>()
    var placed = false
    for ((id, existing) in config.radials) {
        if (id == oldId) {
            radials[menu.id] = menu
            placed = true
        } else {
            radials[id] = existing
        }
    }
    if (!placed) radials[menu.id] = menu
    if (oldId == null || oldId == menu.id) return config.copy(radials = radials)
    return config.copy(
        macros = config.macros.map { if (it.radial == oldId) it.copy(radial = menu.id) else it },
        radials = retarget(radials, oldId, menu.id),
    )
}

/** Deletes a radial menu and the macros that only opened it; folders that opened it become empty slices. */
private fun deleteRadial(config: MacroConfig, id: String) =
    config.copy(macros = config.macros.filter { it.radial != id }, radials = retarget(config.radials - id, id, null))

/** Points the folders that open [from] at [to]. */
private fun retarget(radials: Map<String, RadialMenu>, from: String, to: String?): Map<String, RadialMenu> =
    radials.mapValuesTo(LinkedHashMap()) { (_, r) ->
        if (r.slices.none { it.submenu == from }) r
        else r.copy(slices = r.slices.map { if (it.submenu == from) it.copy(submenu = to) else it })
    }
