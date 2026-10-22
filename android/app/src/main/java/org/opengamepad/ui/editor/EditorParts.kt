package org.opengamepad.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opengamepad.macro.Action
import org.opengamepad.macro.Names
import org.opengamepad.macro.Stick
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

val Muted = Color(0xFF8A91A0)
val Panel = Color(0xFF30343D)

/** Order in which controller buttons are offered. */
private val BUTTON_ORDER = listOf(
    "A", "B", "X", "Y", "LB", "RB", "LT", "RT", "L3", "R3",
    "BACK", "START", "GUIDE", "UP", "DOWN", "LEFT", "RIGHT",
)
private val BUTTON_LABELS = mapOf("UP" to "↑", "DOWN" to "↓", "LEFT" to "←", "RIGHT" to "→")

fun describeButtons(mask: Int, lt: Boolean, rt: Boolean): String {
    val names = BUTTON_ORDER.filter { name ->
        when (name) {
            Names.LT -> lt
            Names.RT -> rt
            else -> mask and Names.BUTTONS.getValue(name) != 0
        }
    }
    return if (names.isEmpty()) "nessun pulsante" else names.joinToString(" + ") { BUTTON_LABELS[it] ?: it }
}

/** Controller buttons as toggle chips (LT/RT included). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonChips(mask: Int, lt: Boolean, rt: Boolean, onChange: (Int, Boolean, Boolean) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BUTTON_ORDER.forEach { name ->
            val selected = when (name) {
                Names.LT -> lt
                Names.RT -> rt
                else -> mask and Names.BUTTONS.getValue(name) != 0
            }
            FilterChip(
                selected = selected,
                onClick = {
                    when (name) {
                        Names.LT -> onChange(mask, !lt, rt)
                        Names.RT -> onChange(mask, lt, !rt)
                        else -> onChange(mask xor Names.BUTTONS.getValue(name), lt, rt)
                    }
                },
                label = { Text(BUTTON_LABELS[name] ?: name) },
            )
        }
    }
}

private val MODIFIERS = listOf("CTRL" to 0x01, "SHIFT" to 0x02, "ALT" to 0x04, "WIN" to 0x08)

private val KEY_GROUPS: List<Pair<String, List<String>>> = listOf(
    "Lettere" to ('A'..'Z').map { it.toString() },
    "Numeri" to ('0'..'9').map { it.toString() },
    "Funzione" to (1..24).map { "F$it" },
    "Speciali" to listOf("ESC", "ENTER", "SPACE", "TAB", "BACKSPACE", "CAPSLOCK", "PRINTSCREEN", "PAUSE", "SCROLLLOCK"),
    "Navigazione" to listOf("ARROWUP", "ARROWDOWN", "ARROWLEFT", "ARROWRIGHT", "INSERT", "DELETE", "HOME", "END", "PAGEUP", "PAGEDOWN"),
    "Simboli" to listOf("MINUS", "EQUAL", "LBRACKET", "RBRACKET", "BACKSLASH", "SEMICOLON", "QUOTE", "GRAVE", "COMMA", "PERIOD", "SLASH"),
)

private fun keyLabel(name: String) = when (name) {
    "ARROWUP" -> "↑"; "ARROWDOWN" -> "↓"; "ARROWLEFT" -> "←"; "ARROWRIGHT" -> "→"
    "MINUS" -> "-"; "EQUAL" -> "="; "LBRACKET" -> "["; "RBRACKET" -> "]"; "BACKSLASH" -> "\\"
    "SEMICOLON" -> ";"; "QUOTE" -> "'"; "GRAVE" -> "`"; "COMMA" -> ","; "PERIOD" -> "."; "SLASH" -> "/"
    "SPACE" -> "Spazio"; "BACKSPACE" -> "⌫"; "PRINTSCREEN" -> "Stamp"
    else -> name
}

fun describeKeys(modifiers: Int, keys: List<Int>): String {
    val names = MODIFIERS.filter { modifiers and it.second != 0 }.map { it.first } +
        keys.mapNotNull { k -> Names.KEYS.entries.firstOrNull { it.value == k }?.key?.let(::keyLabel) }
    return if (names.isEmpty()) "nessun tasto" else names.joinToString(" + ")
}

/** PC key combination: modifier chips, chosen keys (tap to remove), "+ Tasto" picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyChips(modifiers: Int, keys: List<Int>, onChange: (Int, List<Int>) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MODIFIERS.forEach { (name, bit) ->
            FilterChip(
                selected = modifiers and bit != 0,
                onClick = { onChange(modifiers xor bit, keys) },
                label = { Text(name) },
            )
        }
        keys.forEach { key ->
            val name = Names.KEYS.entries.firstOrNull { it.value == key }?.key ?: "?"
            InputChip(
                selected = true,
                onClick = { onChange(modifiers, keys - key) },
                label = { Text("${keyLabel(name)}  ✕") },
            )
        }
        if (keys.size < 6) AssistChip(onClick = { picking = true }, label = { Text("+ Tasto") })
    }
    if (picking) {
        KeyPickerDialog(
            onPick = { picked ->
                if (picked !in keys) onChange(modifiers, keys + picked)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyPickerDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        title = { Text("Scegli un tasto") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KEY_GROUPS.forEach { (group, names) ->
                    Text(group, color = Muted, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        names.forEach { name ->
                            AssistChip(onClick = { onPick(Names.KEYS.getValue(name)) }, label = { Text(keyLabel(name)) })
                        }
                    }
                }
            }
        },
    )
}

/** Numeric field for durations in milliseconds. */
@Composable
fun MsField(value: Long, onChange: (Long) -> Unit, label: String = "ms") {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(5)
            text.toLongOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(110.dp),
    )
}

private val DIRECTIONS = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")

/** Editable list of actions: each one can be changed, moved or removed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionListEditor(actions: List<Action>, onChange: (List<Action>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (actions.isEmpty()) Text("Nessuna azione: aggiungine una qui sotto.", color = Muted)
        actions.forEachIndexed { index, action ->
            fun replace(new: Action) = onChange(actions.toMutableList().also { it[index] = new })
            fun move(delta: Int) {
                val target = index + delta
                if (target !in actions.indices) return
                onChange(actions.toMutableList().also { java.util.Collections.swap(it, index, target) })
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Panel), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${index + 1}. " + when (action) {
                            is Action.Press -> "Pulsanti controller"
                            is Action.Keys -> "Tasti PC"
                            is Action.Move -> "Stick"
                            is Action.Wait -> "Attesa"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    val ms = when (action) {
                        is Action.Press -> action.ms
                        is Action.Keys -> action.ms
                        is Action.Move -> action.ms
                        is Action.Wait -> action.ms
                    }
                    MsField(ms, { v ->
                        replace(
                            when (action) {
                                is Action.Press -> action.copy(ms = v)
                                is Action.Keys -> action.copy(ms = v)
                                is Action.Move -> action.copy(ms = v)
                                is Action.Wait -> action.copy(ms = v)
                            },
                        )
                    }, label = if (action is Action.Wait) "attesa ms" else "durata ms")
                    TextButton(onClick = { move(-1) }, enabled = index > 0) { Text("↑") }
                    TextButton(onClick = { move(1) }, enabled = index < actions.lastIndex) { Text("↓") }
                    TextButton(onClick = { onChange(actions.filterIndexed { i, _ -> i != index }) }) { Text("Elimina") }
                }
                when (action) {
                    is Action.Press -> ButtonChips(action.buttons, action.leftTrigger, action.rightTrigger) { m, lt, rt ->
                        replace(action.copy(buttons = m, leftTrigger = lt, rightTrigger = rt))
                    }
                    is Action.Keys -> KeyChips(action.modifiers, action.keys) { mods, keys ->
                        replace(action.copy(modifiers = mods, keys = keys))
                    }
                    is Action.Move -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Stick.entries.forEach { stick ->
                            FilterChip(
                                selected = action.stick == stick,
                                onClick = { replace(action.copy(stick = stick)) },
                                label = { Text(if (stick == Stick.LEFT) "Stick sinistro" else "Stick destro") },
                            )
                        }
                        DIRECTIONS.forEachIndexed { i, arrow ->
                            val angle = Math.toRadians(i * 45.0)
                            val x = sin(angle).toFloat().round2()
                            val y = cos(angle).toFloat().round2()
                            FilterChip(
                                selected = action.x == x && action.y == y,
                                onClick = { replace(action.copy(x = x, y = y)) },
                                label = { Text(arrow) },
                            )
                        }
                    }
                    is Action.Wait -> Unit
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onChange(actions + Action.Press(0, false, false, 80)) }, label = { Text("+ Pulsanti controller") })
            AssistChip(onClick = { onChange(actions + Action.Keys(0, emptyList(), 80)) }, label = { Text("+ Tasti PC") })
            AssistChip(onClick = { onChange(actions + Action.Move(Stick.LEFT, 0f, 1f, 300)) }, label = { Text("+ Stick") })
            AssistChip(onClick = { onChange(actions + Action.Wait(100)) }, label = { Text("+ Attesa") })
        }
    }
}

private fun Float.round2() = (this * 100).roundToInt() / 100f

val EMOJI = listOf(
    "❤️", "🩹", "🧪", "💣", "🔫", "🗡️", "🏹", "🛡️", "🔄", "🎒", "🗺️", "🧭",
    "💾", "📷", "⏸️", "▶️", "🔦", "🔥", "❄️", "⚡", "💀", "🎯", "🏃", "🦘",
    "👊", "✋", "🚗", "🐎", "🔊", "🔇", "⚙️", "🏠", "💬", "👍", "⭐", "🔑",
    "🚪", "⛏️", "🪓", "🍖", "💰", "🎵", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiPicker(selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            EMOJI.forEach { emoji ->
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (emoji == selected) Panel else Color.Transparent)
                        .clickable { onPick(emoji) },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 22.sp) }
            }
        }
        OutlinedTextField(
            value = selected,
            onValueChange = { onPick(it.take(4)) },
            label = { Text("Oppure scrivi un'icona/testo") },
            singleLine = true,
            modifier = Modifier.width(260.dp),
        )
    }
}

val PALETTE = listOf(
    0xFFC2373DL, 0xFFB7652AL, 0xFFB89B2AL, 0xFF2E7D4FL, 0xFF1F7A7AL, 0xFF2F5FA8L,
    0xFF6B4BB8L, 0xFFA8407FL, 0xFF55595FL, 0xFF3A3F4AL, 0xFF1A1C20L,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPalette(selected: Long, onPick: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PALETTE.forEach { color ->
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(if (color == selected) 3.dp else 1.dp, if (color == selected) Color.White else Panel, CircleShape)
                    .clickable { onPick(color) },
            )
        }
    }
}
