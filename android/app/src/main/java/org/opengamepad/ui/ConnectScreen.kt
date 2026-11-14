package org.opengamepad.ui

import org.opengamepad.str
import org.opengamepad.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opengamepad.BuildConfig
import org.opengamepad.ScreenOrientation
import org.opengamepad.bt.GenericGamepadProfile
import org.opengamepad.bt.SwitchProProfile
import org.opengamepad.screen.SaverMode
import org.opengamepad.screen.ShizukuScreen

enum class StatusKind { IDLE, BUSY, CONNECTED, ERROR }

/** Sections of the home screen; the controller and the macro editor open as their own screens. */
private enum class Section(val icon: String, val label: Int, val short: Int = label) {
    CONNECT("🔗", R.string.nav_connect),
    MOTION("🧭", R.string.nav_motion),
    AMIIBO("🎴", R.string.nav_amiibo),
    SETTINGS("⚙️", R.string.nav_settings, R.string.nav_settings_short),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectScreen(
    status: String,
    statusKind: StatusKind,
    initialTarget: String,
    saverMode: SaverMode,
    shizuku: ShizukuScreen.Availability,
    onScan: () -> Unit,
    found: List<org.opengamepad.net.FoundHost>,
    searching: Boolean,
    onSearch: () -> Unit,
    onFound: (org.opengamepad.net.FoundHost) -> Unit,
    onUsb: () -> Unit,
    onConnect: (String) -> Unit,
    onTryLayout: () -> Unit,
    onMacros: () -> Unit,
    onSaverMode: (SaverMode) -> Unit,
    theme: AppTheme = AppTheme.DARK,
    onTheme: (AppTheme) -> Unit = {},
    language: String = "",
    onLanguage: (String) -> Unit = {},
    sectionIndex: Int = 0,
    onSection: (Int) -> Unit = {},
    orientation: ScreenOrientation = ScreenOrientation.LANDSCAPE,
    onOrientation: (ScreenOrientation) -> Unit = {},
    onShizukuPermission: () -> Unit,
    bluetooth: BluetoothPanel,
    motion: (@Composable () -> Unit)? = null,
    amiibo: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    // Held by the activity: it survives the rebuild that follows a language change.
    val section = Section.entries.getOrElse(sectionIndex) { Section.CONNECT }
    BackHandler(enabled = section != Section.CONNECT) { onSection(Section.CONNECT.ordinal) }
    val sections = Section.entries.filter {
        (it != Section.MOTION || motion != null) && (it != Section.AMIIBO || amiibo != null)
    }
    // Portrait (Game Boy layout) and phones: bottom bar, cards one under the other.
    val narrow = LocalConfiguration.current.screenWidthDp < 600

    val content: @Composable (Modifier) -> Unit = { modifier ->
            Column(
                modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (narrow) 16.dp else 28.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "OpenGamepad",
                        color = p.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    Box(Modifier.align(Alignment.CenterVertically)) { StatusPill(status, statusKind) }
                }
                AnimatedContent(
                    targetState = section,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) },
                    label = "section",
                ) { shown ->
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (shown) {
                            Section.CONNECT -> ConnectSection(
                                narrow, initialTarget, onScan, found, searching, onSearch, onFound, onUsb, onConnect, bluetooth,
                            )
                            Section.MOTION -> motion?.invoke()
                            Section.AMIIBO -> amiibo?.invoke()
                            Section.SETTINGS -> SettingsSection(
                                narrow, theme, onTheme, language, onLanguage, orientation, onOrientation, saverMode, onSaverMode, shizuku, onShizukuPermission,
                            )
                        }
                    }
                }
            }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        if (narrow) {
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f).fillMaxWidth())
                NavigationBar(containerColor = p.bar, tonalElevation = 0.dp) {
                    sections.forEach { s ->
                        NavigationBarItem(
                            selected = section == s,
                            onClick = { onSection(s.ordinal) },
                            icon = { Text(s.icon, fontSize = 20.sp) },
                            label = { Text(str(s.short), fontSize = 11.sp, maxLines = 1) },
                        )
                    }
                    NavigationBarItem(selected = false, onClick = onMacros, icon = { Text("🎯", fontSize = 20.sp) },
                        label = { Text(str(R.string.nav_macros_short), fontSize = 11.sp, maxLines = 1) })
                    NavigationBarItem(selected = false, onClick = onTryLayout, icon = { Text("🕹️", fontSize = 20.sp) },
                        label = { Text(str(R.string.nav_controller), fontSize = 11.sp, maxLines = 1) })
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                // Scrolls when the items do not fit (short landscape screens, large font sizes).
                NavigationRail(
                    containerColor = p.bar,
                    modifier = Modifier.fillMaxHeight().background(p.bar).verticalScroll(rememberScrollState()),
                ) {
                    sections.forEach { s ->
                        NavigationRailItem(
                            selected = section == s,
                            onClick = { onSection(s.ordinal) },
                            icon = { Text(s.icon, fontSize = 20.sp) },
                            label = { Text(str(s.label), fontSize = 12.sp) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // These open their own screens.
                    NavigationRailItem(selected = false, onClick = onMacros, icon = { Text("🎯", fontSize = 20.sp) },
                        label = { Text(str(R.string.nav_macros), fontSize = 12.sp) })
                    NavigationRailItem(selected = false, onClick = onTryLayout, icon = { Text("🕹️", fontSize = 20.sp) },
                        label = { Text(str(R.string.nav_controller), fontSize = 12.sp) })
                    Spacer(Modifier.height(8.dp))
                }
                content(Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectSection(
    narrow: Boolean,
    initialTarget: String,
    onScan: () -> Unit,
    found: List<org.opengamepad.net.FoundHost>,
    searching: Boolean,
    onSearch: () -> Unit,
    onFound: (org.opengamepad.net.FoundHost) -> Unit,
    onUsb: () -> Unit,
    onConnect: (String) -> Unit,
    bluetooth: BluetoothPanel,
) {
    var target by remember { mutableStateOf(initialTarget) }
    val wifi: @Composable (Modifier) -> Unit = { modifier ->
        Card("📶", "Wi‑Fi", str(R.string.wifi_subtitle), modifier) {
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(str(R.string.scan_qr)) }
            OutlinedButton(onClick = onSearch, enabled = !searching, modifier = Modifier.fillMaxWidth()) {
                Text(if (searching) str(R.string.searching) else str(R.string.search))
            }
            if (found.isNotEmpty()) {
                SectionLabel(str(R.string.found_on_network))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    found.forEach { host ->
                        Button(onClick = { onFound(host) }) {
                            Text((if (host.name.contains("Switch", true)) "🎮 " else "💻 ") + "${host.name} · ${host.host}")
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text(str(R.string.ip_port)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onConnect(target) }, enabled = target.isNotBlank()) { Text(str(R.string.nav_connect)) }
            }
    }
    }
    val usb: @Composable (Modifier) -> Unit = { modifier ->
        Card("🔌", "USB", str(R.string.usb_subtitle), modifier) {
            Button(onClick = onUsb, modifier = Modifier.fillMaxWidth()) { Text(str(R.string.usb_connect)) }
            Text(str(R.string.usb_note), color = Faint, fontSize = 12.sp)
    }
    }
    val bt: @Composable (Modifier) -> Unit = { modifier ->
        Card("🎮", "Bluetooth", str(R.string.bt_subtitle), modifier) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = bluetooth.profileId == GenericGamepadProfile.ID,
                    onClick = { bluetooth.onProfile(GenericGamepadProfile.ID) },
                    label = { Text(str(R.string.bt_generic)) },
                )
                FilterChip(
                    selected = bluetooth.profileId == SwitchProProfile.ID,
                    onClick = { bluetooth.onProfile(SwitchProProfile.ID) },
                    label = { Text("Nintendo Switch") },
                )
            }
            if (!bluetooth.active) {
                Button(onClick = bluetooth.onStart, modifier = Modifier.fillMaxWidth()) { Text(str(R.string.bt_enable)) }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    bluetooth.pairedHosts.forEach { (address, name) ->
                        OutlinedButton(onClick = { bluetooth.onConnect(address) }) { Text("💻 $name") }
                    }
                    OutlinedButton(onClick = bluetooth.onVisible) { Text(str(R.string.bt_visible)) }
                    TextButton(onClick = bluetooth.onDisable) { Text(str(R.string.bt_disable)) }
                }
            }
            if (bluetooth.status.isNotEmpty()) Text(bluetooth.status, color = Muted, fontSize = 12.sp)
    }
    }
    // One row of three on wide screens (Bluetooth always in sight), stacked on phones in portrait.
    // A Row, not a FlowRow: chips that wrap inside a FlowRow item can lose their last line.
    if (narrow) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            wifi(Modifier.fillMaxWidth()); usb(Modifier.fillMaxWidth()); bt(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val card = Modifier.weight(1f).fillMaxHeight()
            wifi(card); usb(card); bt(card)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSection(
    narrow: Boolean,
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    language: String,
    onLanguage: (String) -> Unit,
    orientation: ScreenOrientation,
    onOrientation: (ScreenOrientation) -> Unit,
    saverMode: SaverMode,
    onSaverMode: (SaverMode) -> Unit,
    shizuku: ShizukuScreen.Availability,
    onShizukuPermission: () -> Unit,
) {
    val chips = Arrangement.spacedBy(8.dp)
    // Two cards per row on wide screens (tablets, handhelds), one under the other on phones.
    FlowRow(
        // No shared height: intrinsic measuring of wrapping chips can clip the last row.
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        maxItemsInEachRow = if (narrow) 1 else 2,
    ) {
    val card = Modifier.weight(1f)
    Card("🎨", str(R.string.appearance), str(R.string.appearance_sub), card) {
        FlowRow(horizontalArrangement = chips, verticalArrangement = chips) {
            FilterChip(selected = theme == AppTheme.DARK, onClick = { onTheme(AppTheme.DARK) }, label = { Text(str(R.string.theme_dark)) })
            FilterChip(selected = theme == AppTheme.OLED, onClick = { onTheme(AppTheme.OLED) }, label = { Text(str(R.string.theme_oled)) })
        }
        Text(str(R.string.theme_oled_note), color = Faint, fontSize = 12.sp)
    }
    Card("🌐", str(R.string.language), str(R.string.language_sub), card) {
        FlowRow(horizontalArrangement = chips, verticalArrangement = chips) {
            FilterChip(selected = language.isEmpty(), onClick = { onLanguage("") }, label = { Text(str(R.string.language_system)) })
            org.opengamepad.OpenGamepadApp.LANGUAGES.forEach { (tag, name) ->
                FilterChip(selected = language == tag, onClick = { onLanguage(tag) }, label = { Text(name) })
            }
        }
    }
    Card("📱", str(R.string.orientation), str(R.string.orientation_sub), card) {
        // One option per line: the labels are long.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((value, label) in listOf(
                ScreenOrientation.LANDSCAPE to str(R.string.orient_landscape),
                ScreenOrientation.REVERSE to str(R.string.orient_reverse),
                ScreenOrientation.SYSTEM to str(R.string.orient_system),
                ScreenOrientation.PORTRAIT to str(R.string.orient_portrait),
            )) {
                FilterChip(selected = orientation == value, onClick = { onOrientation(value) }, label = { Text(label) })
            }
        }
    }
    Card("🌙", str(R.string.saver), str(R.string.saver_sub), card) {
        FlowRow(horizontalArrangement = chips, verticalArrangement = chips) {
            FilterChip(
                selected = saverMode == SaverMode.BLACK,
                onClick = { onSaverMode(SaverMode.BLACK) },
                label = { Text(str(R.string.saver_black)) },
            )
            FilterChip(
                selected = saverMode == SaverMode.PANEL_OFF,
                onClick = { onSaverMode(SaverMode.PANEL_OFF) },
                label = { Text(str(R.string.saver_panel)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (dot, text) = when (shizuku) {
                ShizukuScreen.Availability.READY -> Success to str(R.string.shizuku_ready)
                ShizukuScreen.Availability.NO_PERMISSION -> Accent to str(R.string.shizuku_perm)
                ShizukuScreen.Availability.NOT_RUNNING -> Faint to str(R.string.shizuku_off)
            }
            Dot(dot)
            Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            if (shizuku == ShizukuScreen.Availability.NO_PERMISSION) {
                TextButton(onClick = onShizukuPermission) { Text(str(R.string.grant)) }
            }
        }
        Text(
            str(R.string.saver_exit),
            color = Faint,
            fontSize = 12.sp,
        )
    }
    val uri = LocalUriHandler.current
    Card("ℹ️", str(R.string.about), str(R.string.about_sub, BuildConfig.VERSION_NAME), card) {
        Text(
            str(R.string.about_note),
            color = Faint,
            fontSize = 12.sp,
            modifier = Modifier.widthIn(max = 640.dp),
        )
        OutlinedButton(onClick = { uri.openUri("https://github.com/Alexprox7/OpenGamepad") }) { Text(str(R.string.about_source)) }
    }
    }
}

/** State and actions of the Bluetooth section. [pairedHosts]: address to name. */
class BluetoothPanel(
    val active: Boolean,
    val status: String,
    val pairedHosts: List<Pair<String, String>>,
    val onStart: () -> Unit,
    val onVisible: () -> Unit,
    val onDisable: () -> Unit,
    val profileId: String,
    val onProfile: (String) -> Unit,
    val onConnect: (String) -> Unit,
)
