package org.opengamepad.ui.editor

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
                trail = stack.filterIsInstance<Editing.Radial>().map { it.id ?: "nuova" },
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
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Macro e ruote", color = Color.White, fontSize = 24.sp)
            OutlinedButton(onClick = onClose) { Text("Chiudi") }
            OutlinedButton(onClick = onAdvanced) { Text("Avanzate (JSON)") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Finestra combinazioni:", color = Muted)
            listOf(0L, 30L, 50L, 80L, 120L).forEach { ms ->
                FilterChip(
                    selected = config.comboWindowMs == ms,
                    onClick = { onConfig(config.copy(comboWindowMs = ms)) },
                    label = { Text(if (ms == 0L) "off" else "$ms ms") },
                )
            }
        }
        Text(
            "Il primo pulsante di una combinazione (es. L3 di L3+R3) aspetta questo tempo prima di arrivare " +
                "al gioco, così premendo i due pulsanti quasi insieme il gioco non riceve L3 da solo.",
            color = Muted,
            fontSize = 12.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Macro", color = Muted, fontSize = 14.sp)
            Button(onClick = { stack = listOf(Editing.MacroAt(-1)) }) { Text("+ Nuova macro") }
        }
        if (config.macros.isEmpty()) Text("Nessuna macro.", color = Muted)
        config.macros.forEachIndexed { index, macro ->
            Card(onClick = { stack = listOf(Editing.MacroAt(index)) }) {
                Column(Modifier.weight(1f)) {
                    Text(macro.name, color = Color.White, fontSize = 17.sp)
                    Text(
                        describeButtons(macro.trigger.buttons, macro.trigger.leftTrigger, macro.trigger.rightTrigger) +
                            "  →  " + describeOutput(macro),
                        color = Muted,
                    )
                }
                Text("Modifica ›", color = Muted)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ruote radiali", color = Muted, fontSize = 14.sp)
            Button(onClick = { stack = listOf(Editing.Radial(null)) }) { Text("+ Nuova ruota") }
        }
        if (config.radials.isEmpty()) Text("Nessuna ruota.", color = Muted)
        config.radials.values.forEach { menu ->
            val opener = config.macros.firstOrNull { it.radial == menu.id }
            val parent = config.radials.values.firstOrNull { r -> r.slices.any { it.submenu == menu.id } }
            Card(onClick = { stack = listOf(Editing.Radial(menu.id)) }) {
                RadialWheel(menu, null, Modifier.size(96.dp), scale = 0.35f)
                Column(Modifier.weight(1f)) {
                    Text(menu.id, color = Color.White, fontSize = 17.sp)
                    Text(
                        "${menu.slices.size} spicchi · " +
                            (if (menu.mode == RadialMode.STICKY) "resta aperta · " else "") +
                            when {
                                opener != null -> "si apre con " +
                                    describeButtons(opener.trigger.buttons, opener.trigger.leftTrigger, opener.trigger.rightTrigger)
                                parent != null -> "sotto-ruota di \"${parent.id}\""
                                else -> "nessuna combinazione: assegnala da una macro"
                            },
                        color = Muted,
                    )
                }
                Text("Modifica ›", color = Muted)
            }
        }
    }
}

@Composable
private fun Card(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Panel, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun describeOutput(macro: Macro): String {
    macro.radial?.let { return "ruota \"$it\"" }
    val steps = macro.actions.joinToString(", ") { a ->
        when (a) {
            is Action.Press -> describeButtons(a.buttons, a.leftTrigger, a.rightTrigger)
            is Action.Keys -> describeKeys(a.modifiers, a.keys)
            is Action.Move -> "stick ${if (a.stick.name == "LEFT") "sx" else "dx"}"
            is Action.Wait -> "attesa ${a.ms} ms"
        }
    }
    return (if (macro.repeat) "turbo: " else "") + steps.ifEmpty { "nessuna azione" }
}

/** [base] when free (e.g. the label of the folder slice), otherwise "ruota N". */
private fun uniqueId(config: MacroConfig, base: String = ""): String =
    base.trim().takeIf { it.isNotEmpty() && it !in config.radials && !it.startsWith(DYNAMIC_RADIAL_PREFIX) }
        ?: generateSequence(1) { it + 1 }.map { "ruota $it" }.first { it !in config.radials }

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
