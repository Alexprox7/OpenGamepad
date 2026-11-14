package org.opengamepad.ui.editor

import org.opengamepad.str
import org.opengamepad.R
import org.opengamepad.ui.Danger
import org.opengamepad.ui.Ink
import org.opengamepad.ui.KeyCaps
import org.opengamepad.ui.LocalPalette
import org.opengamepad.ui.Muted
import org.opengamepad.ui.ScreenHeader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
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

// Labels follow the language: "0,3 s" in Italian, "0.3 s" in English.
private val HOLD_OPTIONS get() = listOf(0L, 300L, 500L, 1000L).map { ms ->
    ms to if (ms == 0L) str(R.string.hold_now) else java.text.NumberFormat.getInstance().format(ms / 1000.0) + " s"
}

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
            .background(LocalPalette.current.background)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(str(R.string.edit_macro), onBack = onCancel) {
            Button(
                onClick = { onSave(macro) },
                enabled = trigger.size > 0 && (macro.radial != null || macro.actions.isNotEmpty()),
            ) { Text(str(R.string.save)) }
            OutlinedButton(onClick = onCancel) { Text(str(R.string.cancel)) }
            onDelete?.let { TextButton(onClick = it) { Text(str(R.string.delete_macro), color = Danger) } }
        }
        OutlinedTextField(
            value = macro.name,
            onValueChange = { macro = macro.copy(name = it) },
            label = { Text(str(R.string.name)) },
            singleLine = true,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        )

        org.opengamepad.ui.Card("🎮", str(R.string.combo), str(R.string.combo_sub), Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.align(Alignment.CenterVertically)) {
                KeyCaps(describeButtons(trigger.buttons, trigger.leftTrigger, trigger.rightTrigger))
            }
            OutlinedButton(onClick = { recording = true }, enabled = !recording) {
                Text(if (recording) str(R.string.recording) else str(R.string.record))
            }
        }
        ButtonChips(trigger.buttons, trigger.leftTrigger, trigger.rightTrigger) { m, lt, rt ->
            macro = macro.copy(trigger = trigger.copy(buttons = m, leftTrigger = lt, rightTrigger = rt))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            Text(str(R.string.hold), color = Muted, modifier = Modifier.align(Alignment.CenterVertically))
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
            Text(str(R.string.consume), color = Ink, modifier = Modifier.weight(1f, fill = false))
        }
        }

        org.opengamepad.ui.Card("⚡", str(R.string.action), str(R.string.action_sub), Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = macro.radial == null,
                onClick = { macro = macro.copy(radial = null) },
                label = { Text(str(R.string.action_sequence)) },
            )
            FilterChip(
                selected = macro.radial != null,
                onClick = { macro = macro.copy(radial = radialIds.firstOrNull() ?: return@FilterChip) },
                enabled = radialIds.isNotEmpty(),
                label = { Text(str(R.string.open_wheel)) },
            )
        }
        if (macro.radial != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                radialIds.forEach { id ->
                    FilterChip(selected = macro.radial == id, onClick = { macro = macro.copy(radial = id) }, label = { Text(id) })
                }
            }
            Text(
                str(R.string.wheel_help),
                color = Muted,
            )
        } else {
            ActionListEditor(macro.actions) { macro = macro.copy(actions = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = macro.repeat, onCheckedChange = { macro = macro.copy(repeat = it) })
                Text(str(R.string.turbo), color = Ink, modifier = Modifier.weight(1f, fill = false))
            }
        }
        }
    }
}

fun newMacro() = Macro(
    name = str(R.string.new_macro_name),
    trigger = Trigger(0, leftTrigger = false, rightTrigger = false, holdMs = 0, consume = true),
    actions = emptyList(),
    radial = null,
    repeat = false,
)
