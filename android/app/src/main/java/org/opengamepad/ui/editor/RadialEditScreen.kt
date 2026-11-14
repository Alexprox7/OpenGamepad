package org.opengamepad.ui.editor

import org.opengamepad.str
import org.opengamepad.R
import org.opengamepad.ui.Danger
import org.opengamepad.ui.Ink
import org.opengamepad.ui.LocalPalette
import org.opengamepad.ui.Muted
import org.opengamepad.ui.ScreenHeader
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
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
        menu.id.isBlank() -> str(R.string.need_name)
        menu.id != initial.id && menu.id in takenIds -> str(R.string.name_taken)
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

    // Phones in portrait: one column, scrolled as a whole; otherwise wheel on the left, slice on the right.
    val narrow = LocalConfiguration.current.screenWidthDp < 600
    val p = LocalPalette.current
    val left: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ScreenHeader(str(R.string.wheel), onBack = onCancel) {
                Button(onClick = { onSave(cleaned()) }, enabled = canSave) { Text(str(R.string.save)) }
                OutlinedButton(onClick = onCancel) { Text(str(if (trail.size > 1) R.string.back else R.string.cancel)) }
            }
            if (trail.size > 1) Text(trail.joinToString("  ›  "), color = Muted, fontSize = 13.sp)
            if (unchosen.isNotEmpty()) {
                Text(str(R.string.choose_sub, unchosen.first() + 1), color = Danger, fontSize = 12.sp)
            }
            RadialWheel(
                menu,
                highlighted = selected.takeIf { it in menu.slices.indices },
                modifier = Modifier.size(300.dp).align(Alignment.CenterHorizontally),
                scale = 0.8f,
            )
            Text(str(R.string.tap_slice), color = Muted, fontSize = 12.sp)
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
                        menu = menu.copy(slices = menu.slices + RadialSlice(str(R.string.new_slice_label), "⭐", color))
                        selected = menu.slices.lastIndex
                    },
                    label = { Text(str(R.string.add_slice)) },
                )
            }
            OutlinedTextField(
                value = menu.id,
                onValueChange = { menu = menu.copy(id = it.trim()) },
                label = { Text(str(R.string.wheel_name)) },
                isError = idError != null,
                supportingText = idError?.let { { Text(it) } },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(str(R.string.pick_with), color = Muted)
                Stick.entries.forEach { stick ->
                    FilterChip(
                        selected = menu.stick == stick,
                        onClick = { menu = menu.copy(stick = stick) },
                        label = { Text(str(if (stick == Stick.LEFT) R.string.stick_left else R.string.stick_right)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(str(R.string.opening), color = Muted)
                FilterChip(
                    selected = menu.mode == RadialMode.HOLD,
                    onClick = { menu = menu.copy(mode = RadialMode.HOLD) },
                    label = { Text(str(R.string.mode_hold)) },
                )
                FilterChip(
                    selected = menu.mode == RadialMode.STICKY,
                    onClick = { menu = menu.copy(mode = RadialMode.STICKY) },
                    label = { Text(str(R.string.mode_sticky)) },
                )
            }
            Text(
                if (menu.mode == RadialMode.HOLD) {
                    str(R.string.hold_help)
                } else {
                    str(R.string.sticky_help)
                },
                color = Muted,
                fontSize = 12.sp,
            )
            onDelete?.let { TextButton(onClick = it) { Text(str(R.string.delete_wheel), color = Danger) } }
        }
    }

    // The selected slice
    val slice = menu.slices.getOrNull(selected)
    val right: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier
                .clip(RoundedCornerShape(18.dp))
                .background(p.surface)
                .border(1.dp, p.outline, RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (slice == null) {
                Text(str(R.string.add_slice_hint), color = Muted)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(str(R.string.slice_n, selected + 1), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                TextButton(
                    onClick = { if (selected > 0) { menu = menu.copy(slices = menu.slices.swap(selected, selected - 1)); selected-- } },
                    enabled = selected > 0,
                ) { Text(str(R.string.move_left)) }
                TextButton(
                    onClick = { if (selected < menu.slices.lastIndex) { menu = menu.copy(slices = menu.slices.swap(selected, selected + 1)); selected++ } },
                    enabled = selected < menu.slices.lastIndex,
                ) { Text(str(R.string.move_right)) }
                TextButton(onClick = {
                    menu = menu.copy(slices = menu.slices.filterIndexed { i, _ -> i != selected })
                    selected = (selected - 1).coerceAtLeast(0)
                }) { Text(str(R.string.delete_slice), color = Danger) }
            }
            OutlinedTextField(
                value = slice.label,
                onValueChange = { v -> updateSlice { it.copy(label = v) } },
                label = { Text(str(R.string.label)) },
                singleLine = true,
                modifier = Modifier.width(260.dp),
            )
            Text(str(R.string.icon), color = Muted, fontSize = 13.sp)
            EmojiPicker(slice.icon) { v -> updateSlice { it.copy(icon = v) } }
            Text(str(R.string.bg_color), color = Muted, fontSize = 13.sp)
            ColorPalette(slice.color) { v -> updateSlice { it.copy(color = v) } }
            Text(str(R.string.when_chosen), color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !slice.isFolder,
                    onClick = { updateSlice { it.copy(submenu = null) } },
                    label = { Text(str(R.string.runs_actions)) },
                )
                FilterChip(
                    selected = slice.isFolder,
                    onClick = { if (!slice.isFolder) updateSlice { it.copy(submenu = "") } },
                    label = { Text(str(R.string.opens_sub)) },
                )
            }
            if (slice.isFolder) {
                val targets = folderTargets.filter { it != menu.id && it != initial.id }
                if (targets.isNotEmpty()) Text(str(R.string.wheel_to_open), color = Muted, fontSize = 13.sp)
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
                        label = { Text(str(R.string.new_sub)) },
                    )
                }
                val target = slice.submenu
                if (target != null && target in takenIds) {
                    OutlinedButton(onClick = { onOpenSubmenu(cleaned(), selected, target) }, enabled = canSave) {
                        Text(str(R.string.edit_target, target))
                    }
                }
                Text(
                    str(R.string.sub_note),
                    color = Muted,
                    fontSize = 12.sp,
                )
            } else {
                ActionListEditor(slice.actions) { v -> updateSlice { it.copy(actions = v) } }
            }
        }
    }

    val screen = Modifier
        .fillMaxSize()
        .background(p.background)
        .windowInsetsPadding(WindowInsets.displayCutout)
    if (narrow) {
        Column(
            screen.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            left(Modifier.fillMaxWidth())
            right(Modifier.fillMaxWidth())
        }
    } else {
        Row(screen.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            left(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
            right(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
        }
    }
}

private fun <T> List<T>.swap(a: Int, b: Int) = toMutableList().also { java.util.Collections.swap(it, a, b) }

fun newRadial(id: String) = RadialMenu(
    id = id,
    stick = Stick.RIGHT,
    slices = listOf(
        RadialSlice(str(R.string.one), "1️⃣", PALETTE[0]),
        RadialSlice(str(R.string.two), "2️⃣", PALETTE[3]),
        RadialSlice(str(R.string.three), "3️⃣", PALETTE[5]),
        RadialSlice(str(R.string.four), "4️⃣", PALETTE[6]),
    ),
)
