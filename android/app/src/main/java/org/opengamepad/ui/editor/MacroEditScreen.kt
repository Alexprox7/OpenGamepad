package org.opengamepad.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.withTimeoutOrNull
import org.opengamepad.macro.Macro
import org.opengamepad.macro.Trigger
import org.opengamepad.protocol.ControllerState

private val HOLD_OPTIONS = listOf(0L to "Subito", 300L to "0,3 s", 500L to "0,5 s", 1000L to "1 s")

/** Edits one macro: input combination and resulting output (actions or radial menu). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MacroEditScreen(
    initial: Macro,
    radialIds: List<String>,
    rawInput: StateFlow<ControllerState>,
    onSave: (Macro) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    var macro by remember { mutableStateOf(initial) }
    var recording by remember { mutableStateOf(false) }
    val trigger = macro.trigger

    if (recording) {
        // Collects the combination pressed on the controller (or the on-screen pad):
        // everything held together, taken when all buttons are released again.
        LaunchedEffect(Unit) {
            var mask = 0
            var lt = false
            var rt = false
            withTimeoutOrNull(10_000) {
                rawInput.collect { s ->
                    mask = mask or s.buttons
                    lt = lt || s.leftTrigger > 128
                    rt = rt || s.rightTrigger > 128
                    val anyHeld = s.buttons != 0 || s.leftTrigger > 128 || s.rightTrigger > 128
                    if (!anyHeld && (mask != 0 || lt || rt)) {
                        macro = macro.copy(trigger = macro.trigger.copy(buttons = mask, leftTrigger = lt, rightTrigger = rt))
                        recording = false
                    }
                }
            }
            recording = false
        }
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
            Text("Modifica macro", color = Color.White, fontSize = 22.sp)
            Button(
                onClick = { onSave(macro) },
                enabled = trigger.size > 0 && (macro.radial != null || macro.actions.isNotEmpty()),
            ) { Text("Salva") }
            OutlinedButton(onClick = onCancel) { Text("Annulla") }
            onDelete?.let { TextButton(onClick = it) { Text("Elimina macro", color = Color(0xFFEB5757)) } }
        }
        OutlinedTextField(
            value = macro.name,
            onValueChange = { macro = macro.copy(name = it) },
            label = { Text("Nome") },
            singleLine = true,
            modifier = Modifier.width(320.dp),
        )

        Text("INPUT · combinazione che attiva la macro", color = Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(describeButtons(trigger.buttons, trigger.leftTrigger, trigger.rightTrigger), color = Color.White, fontSize = 18.sp)
            OutlinedButton(onClick = { recording = true }, enabled = !recording) {
                Text(if (recording) "Premi la combinazione sul controller…" else "Registra dal controller")
            }
        }
        ButtonChips(trigger.buttons, trigger.leftTrigger, trigger.rightTrigger) { m, lt, rt ->
            macro = macro.copy(trigger = trigger.copy(buttons = m, leftTrigger = lt, rightTrigger = rt))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            Text("Tenere premuto:", color = Muted, modifier = Modifier.align(Alignment.CenterVertically))
            HOLD_OPTIONS.forEach { (ms, label) ->
                FilterChip(
                    selected = trigger.holdMs == ms,
                    onClick = { macro = macro.copy(trigger = trigger.copy(holdMs = ms)) },
                    label = { Text(label) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = trigger.consume, onCheckedChange = { macro = macro.copy(trigger = trigger.copy(consume = it)) })
            Text("Non inviare al gioco i pulsanti della combinazione", color = Color.White)
        }

        Text("OUTPUT · cosa succede", color = Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = macro.radial == null,
                onClick = { macro = macro.copy(radial = null) },
                label = { Text("Sequenza di azioni") },
            )
            FilterChip(
                selected = macro.radial != null,
                onClick = { macro = macro.copy(radial = radialIds.firstOrNull() ?: return@FilterChip) },
                enabled = radialIds.isNotEmpty(),
                label = { Text("Apri ruota radiale") },
            )
        }
        if (macro.radial != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                radialIds.forEach { id ->
                    FilterChip(selected = macro.radial == id, onClick = { macro = macro.copy(radial = id) }, label = { Text(id) })
                }
            }
            Text("Tieni premuta la combinazione, scegli con lo stick e rilascia.", color = Muted)
        } else {
            ActionListEditor(macro.actions) { macro = macro.copy(actions = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = macro.repeat, onCheckedChange = { macro = macro.copy(repeat = it) })
                Text("Ripeti finché la combinazione è premuta (turbo)", color = Color.White)
            }
        }
    }
}

fun newMacro() = Macro(
    name = "Nuova macro",
    trigger = Trigger(0, leftTrigger = false, rightTrigger = false, holdMs = 0, consume = true),
    actions = emptyList(),
    radial = null,
    repeat = false,
)
