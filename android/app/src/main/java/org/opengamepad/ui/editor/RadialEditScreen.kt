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
import org.opengamepad.macro.DYNAMIC_RADIAL_PREFIX
import org.opengamepad.macro.RadialMenu
import org.opengamepad.macro.RadialMode
import org.opengamepad.macro.RadialSlice
import org.opengamepad.macro.Stick
import org.opengamepad.ui.RadialWheel

private const val MAX_SLICES = 12

/**
 * Edits a radial menu: live preview on the left, the selected slice on the right.
 * A slice can be a folder that opens another wheel ([folderTargets]); [onOpenSubmenu] saves
 * this wheel and edits that one (`target` null = create a new one for the slice at `index`).
 * [trail] names the wheels opened to get here, this one last.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadialEditScreen(
    initial: RadialMenu,
    takenIds: Set<String>,
    folderTargets: List<String>,
    trail: List<String>,
    onSave: (RadialMenu) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
    onOpenSubmenu: (menu: RadialMenu, index: Int, target: String?) -> Unit,
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
    // A folder slice whose wheel is not chosen yet has submenu = "".
    val unchosen = menu.slices.indices.filter { menu.slices[it].submenu == "" }
    val canSave = idError == null && menu.slices.isNotEmpty() && unchosen.isEmpty()
    // Folders run no actions.
    fun cleaned() = menu.copy(slices = menu.slices.map { if (it.isFolder) it.copy(actions = emptyList()) else it })

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
                Button(onClick = { onSave(cleaned()) }, enabled = canSave) { Text("Salva") }
                OutlinedButton(onClick = onCancel) { Text(if (trail.size > 1) "‹ Indietro" else "Annulla") }
            }
            if (trail.size > 1) Text(trail.joinToString("  ›  "), color = Muted, fontSize = 13.sp)
            if (unchosen.isNotEmpty()) {
                Text("Scegli la sotto-ruota dello spicchio ${unchosen.first() + 1}", color = Color(0xFFEB5757), fontSize = 12.sp)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Apertura:", color = Muted)
                FilterChip(
                    selected = menu.mode == RadialMode.HOLD,
                    onClick = { menu = menu.copy(mode = RadialMode.HOLD) },
                    label = { Text("Tieni premuto") },
                )
                FilterChip(
                    selected = menu.mode == RadialMode.STICKY,
                    onClick = { menu = menu.copy(mode = RadialMode.STICKY) },
                    label = { Text("Resta aperta") },
                )
            }
            Text(
                if (menu.mode == RadialMode.HOLD) {
                    "Visibile finché tieni la combinazione: rilascia per scegliere. Su una cartella resta fermo un attimo per aprirla."
                } else {
                    "Resta aperta dopo il rilascio: scegli con lo stick, conferma con A, un tocco o la combinazione; B torna indietro o chiude."
                },
                color = Muted,
                fontSize = 12.sp,
            )
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
            Text("Quando lo scegli", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !slice.isFolder,
                    onClick = { updateSlice { it.copy(submenu = null) } },
                    label = { Text("Esegue azioni") },
                )
                FilterChip(
                    selected = slice.isFolder,
                    onClick = { if (!slice.isFolder) updateSlice { it.copy(submenu = "") } },
                    label = { Text("📁 Apre una sotto-ruota") },
                )
            }
            if (slice.isFolder) {
                val targets = folderTargets.filter { it != menu.id && it != initial.id }
                if (targets.isNotEmpty()) Text("Ruota da aprire", color = Muted, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    targets.forEach { id ->
                        FilterChip(
                            selected = slice.submenu == id,
                            onClick = { updateSlice { it.copy(submenu = id) } },
                            label = { Text(if (id.startsWith(DYNAMIC_RADIAL_PREFIX)) "⚡ ${id.removePrefix(DYNAMIC_RADIAL_PREFIX)}" else id) },
                        )
                    }
                    AssistChip(
                        onClick = { onOpenSubmenu(cleaned(), selected, null) },
                        enabled = idError == null && (unchosen - selected).isEmpty(),
                        label = { Text("+ Nuova sotto-ruota") },
                    )
                }
                val target = slice.submenu
                if (target != null && target in takenIds) {
                    OutlinedButton(onClick = { onOpenSubmenu(cleaned(), selected, target) }, enabled = canSave) {
                        Text("Modifica \"$target\" ›")
                    }
                }
                Text(
                    "Aprire una sotto-ruota salva prima questa. B torna alla ruota precedente.",
                    color = Muted,
                    fontSize = 12.sp,
                )
            } else {
                ActionListEditor(slice.actions) { v -> updateSlice { it.copy(actions = v) } }
            }
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
