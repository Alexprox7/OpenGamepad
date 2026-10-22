package org.opengamepad.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opengamepad.macro.RadialMenu
import org.opengamepad.macro.RadialSlice
import org.opengamepad.macro.Stick
import org.opengamepad.ui.RadialWheel

private const val MAX_SLICES = 12

/** Edits a radial menu: live preview on the left, the selected slice on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadialEditScreen(
    initial: RadialMenu,
    takenIds: Set<String>,
    onSave: (RadialMenu) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    var menu by remember { mutableStateOf(initial) }
    var selected by remember { mutableIntStateOf(0) }
    val idError = when {
        menu.id.isBlank() -> "Serve un nome"
        menu.id != initial.id && menu.id in takenIds -> "Nome già usato"
        else -> null
    }
    fun updateSlice(transform: (RadialSlice) -> RadialSlice) {
        menu = menu.copy(slices = menu.slices.toMutableList().also { it[selected] = transform(it[selected]) })
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Left: header, preview and slice list
        Column(
            Modifier
                .width(330.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onSave(menu) }, enabled = idError == null && menu.slices.isNotEmpty()) { Text("Salva") }
                OutlinedButton(onClick = onCancel) { Text("Annulla") }
            }
            OutlinedTextField(
                value = menu.id,
                onValueChange = { menu = menu.copy(id = it.trim()) },
                label = { Text("Nome ruota") },
                isError = idError != null,
                supportingText = idError?.let { { Text(it) } },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Scegli con:", color = Muted)
                Stick.entries.forEach { stick ->
                    FilterChip(
                        selected = menu.stick == stick,
                        onClick = { menu = menu.copy(stick = stick) },
                        label = { Text(if (stick == Stick.LEFT) "stick sx" else "stick dx") },
                    )
                }
            }
            RadialWheel(
                menu,
                highlighted = selected.takeIf { it in menu.slices.indices },
                modifier = Modifier.size(300.dp),
                scale = 0.8f,
            )
            Text("Tocca uno spicchio per modificarlo", color = Muted, fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                menu.slices.forEachIndexed { i, slice ->
                    FilterChip(
                        selected = i == selected,
                        onClick = { selected = i },
                        label = { Text("${slice.icon} ${slice.label}".trim().ifEmpty { "#${i + 1}" }) },
                    )
                }
                if (menu.slices.size < MAX_SLICES) AssistChip(
                    onClick = {
                        val color = PALETTE[menu.slices.size % PALETTE.size]
                        menu = menu.copy(slices = menu.slices + RadialSlice("Nuovo", "⭐", color))
                        selected = menu.slices.lastIndex
                    },
                    label = { Text("+ Spicchio") },
                )
            }
            onDelete?.let { TextButton(onClick = it) { Text("Elimina ruota", color = Color(0xFFEB5757)) } }
        }

        // Right: the selected slice
        val slice = menu.slices.getOrNull(selected)
        Column(
            Modifier
                .fillMaxSize()
                .border(1.dp, Panel, RoundedCornerShape(12.dp))
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (slice == null) {
                Text("Aggiungi uno spicchio con \"+ Spicchio\".", color = Muted)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Spicchio ${selected + 1}", color = Color.White, fontSize = 18.sp)
                TextButton(
                    onClick = { if (selected > 0) { menu = menu.copy(slices = menu.slices.swap(selected, selected - 1)); selected-- } },
                    enabled = selected > 0,
                ) { Text("◀ Sposta") }
                TextButton(
                    onClick = { if (selected < menu.slices.lastIndex) { menu = menu.copy(slices = menu.slices.swap(selected, selected + 1)); selected++ } },
                    enabled = selected < menu.slices.lastIndex,
                ) { Text("Sposta ▶") }
                TextButton(onClick = {
                    menu = menu.copy(slices = menu.slices.filterIndexed { i, _ -> i != selected })
                    selected = (selected - 1).coerceAtLeast(0)
                }) { Text("Elimina spicchio", color = Color(0xFFEB5757)) }
            }
            OutlinedTextField(
                value = slice.label,
                onValueChange = { v -> updateSlice { it.copy(label = v) } },
                label = { Text("Etichetta") },
                singleLine = true,
                modifier = Modifier.width(260.dp),
            )
            Text("Icona", color = Muted, fontSize = 13.sp)
            EmojiPicker(slice.icon) { v -> updateSlice { it.copy(icon = v) } }
            Text("Colore di sfondo", color = Muted, fontSize = 13.sp)
            ColorPalette(slice.color) { v -> updateSlice { it.copy(color = v) } }
            Text("Azione quando lo scegli", color = Muted, fontSize = 13.sp)
            ActionListEditor(slice.actions) { v -> updateSlice { it.copy(actions = v) } }
        }
    }
}

private fun <T> List<T>.swap(a: Int, b: Int) = toMutableList().also { java.util.Collections.swap(it, a, b) }

fun newRadial(id: String) = RadialMenu(
    id = id,
    stick = Stick.RIGHT,
    slices = listOf(
        RadialSlice("Uno", "1️⃣", PALETTE[0]),
        RadialSlice("Due", "2️⃣", PALETTE[3]),
        RadialSlice("Tre", "3️⃣", PALETTE[5]),
        RadialSlice("Quattro", "4️⃣", PALETTE[6]),
    ),
)
