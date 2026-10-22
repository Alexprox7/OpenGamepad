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
import org.opengamepad.macro.RadialMenu
import org.opengamepad.protocol.ControllerState
import org.opengamepad.ui.RadialWheel

private sealed interface Editing {
    data class MacroAt(val index: Int) : Editing // -1 = new
    data class Radial(val id: String?) : Editing // null = new
}

/** Entry point of the visual editor: lists macros and radial menus. */
@Composable
fun MacroHomeScreen(
    config: MacroConfig,
    rawInput: StateFlow<ControllerState>,
    onConfig: (MacroConfig) -> Unit,
    onAdvanced: () -> Unit,
    onClose: () -> Unit,
) {
    var editing by remember { mutableStateOf<Editing?>(null) }
    BackHandler(enabled = editing == null, onBack = onClose)

    when (val e = editing) {
        is Editing.MacroAt -> {
            MacroEditScreen(
                initial = config.macros.getOrNull(e.index) ?: newMacro(),
                radialIds = config.radials.keys.toList(),
                rawInput = rawInput,
                onSave = { macro ->
                    val list = config.macros.toMutableList()
                    if (e.index in list.indices) list[e.index] = macro else list += macro
                    onConfig(config.copy(macros = list))
                    editing = null
                },
                onDelete = if (e.index >= 0) {
                    { onConfig(config.copy(macros = config.macros.filterIndexed { i, _ -> i != e.index })); editing = null }
                } else null,
                onCancel = { editing = null },
            )
            return
        }
        is Editing.Radial -> {
            val existing = e.id?.let { config.radials[it] }
            RadialEditScreen(
                initial = existing ?: newRadial(uniqueId(config)),
                takenIds = config.radials.keys,
                onSave = { menu -> onConfig(saveRadial(config, e.id, menu)); editing = null },
                onDelete = if (existing != null) {
                    { onConfig(deleteRadial(config, existing.id)); editing = null }
                } else null,
                onCancel = { editing = null },
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
            Button(onClick = { editing = Editing.MacroAt(-1) }) { Text("+ Nuova macro") }
        }
        if (config.macros.isEmpty()) Text("Nessuna macro.", color = Muted)
        config.macros.forEachIndexed { index, macro ->
            Card(onClick = { editing = Editing.MacroAt(index) }) {
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
            Button(onClick = { editing = Editing.Radial(null) }) { Text("+ Nuova ruota") }
        }
        if (config.radials.isEmpty()) Text("Nessuna ruota.", color = Muted)
        config.radials.values.forEach { menu ->
            val opener = config.macros.firstOrNull { it.radial == menu.id }
            Card(onClick = { editing = Editing.Radial(menu.id) }) {
                RadialWheel(menu, null, Modifier.size(96.dp), scale = 0.35f)
                Column(Modifier.weight(1f)) {
                    Text(menu.id, color = Color.White, fontSize = 17.sp)
                    Text(
                        "${menu.slices.size} spicchi · " + (opener?.let {
                            "si apre con " + describeButtons(it.trigger.buttons, it.trigger.leftTrigger, it.trigger.rightTrigger)
                        } ?: "nessuna combinazione: assegnala da una macro"),
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

private fun uniqueId(config: MacroConfig): String =
    generateSequence(1) { it + 1 }.map { "ruota $it" }.first { it !in config.radials }

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
    val macros = if (oldId != null && oldId != menu.id) {
        config.macros.map { if (it.radial == oldId) it.copy(radial = menu.id) else it }
    } else {
        config.macros
    }
    return config.copy(macros = macros, radials = radials)
}

/** Deletes a radial menu and the macros that only opened it. */
private fun deleteRadial(config: MacroConfig, id: String) =
    config.copy(macros = config.macros.filter { it.radial != id }, radials = config.radials - id)
