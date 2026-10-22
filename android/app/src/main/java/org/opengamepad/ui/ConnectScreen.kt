package org.opengamepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opengamepad.screen.SaverMode
import org.opengamepad.screen.ShizukuScreen

val Accent = Color(0xFF7C9CFF)
private val CardBorder = Color(0xFF262A31)
private val Muted = Color(0xFF8A91A0)
private val Faint = Color(0xFF5F6673)
private val Ok = Color(0xFF4ADE80)
private val Error = Color(0xFFF87171)

enum class StatusKind { IDLE, BUSY, CONNECTED, ERROR }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectScreen(
    status: String,
    statusKind: StatusKind,
    initialTarget: String,
    saverMode: SaverMode,
    shizuku: ShizukuScreen.Availability,
    onScan: () -> Unit,
    onUsb: () -> Unit,
    onConnect: (String) -> Unit,
    onTryLayout: () -> Unit,
    onMacros: () -> Unit,
    onSaverMode: (SaverMode) -> Unit,
    onShizukuPermission: () -> Unit,
    bluetooth: BluetoothPanel,
) {
    var target by remember { mutableStateOf(initialTarget) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("OpenGamepad", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            StatusPill(status, statusKind)
            Box(Modifier.weight(1f))
            OutlinedButton(onClick = onMacros) { Text("Macro e ruote") }
            OutlinedButton(onClick = onTryLayout) { Text("Prova layout") }
        }

        // Connection modes
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card("📶", "Wi‑Fi", "Companion OpenGamepad o app PC originale · controller Xbox", Modifier.weight(1f)) {
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scansiona QR") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text("IP:porta") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onConnect(target) }, enabled = target.isNotBlank()) { Text("Connetti") }
                }
            }
            Card("🔌", "USB", "Companion OpenGamepad e debug USB attivo · latenza minima", Modifier.weight(1f)) {
                Button(onClick = onUsb, modifier = Modifier.fillMaxWidth()) { Text("Connetti via USB") }
                Text("Il companion prepara il collegamento da solo appena colleghi il cavo.", color = Faint, fontSize = 12.sp)
            }
            Card("🎮", "Bluetooth", "Nessun programma sul PC · gamepad generico + tastiera", Modifier.weight(1f)) {
                if (!bluetooth.active) {
                    Button(onClick = bluetooth.onStart, modifier = Modifier.fillMaxWidth()) { Text("Attiva Bluetooth") }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bluetooth.pairedHosts.forEach { (address, name) ->
                            OutlinedButton(onClick = { bluetooth.onConnect(address) }) { Text("💻 $name") }
                        }
                        OutlinedButton(onClick = bluetooth.onVisible) { Text("Rendi visibile") }
                        TextButton(onClick = bluetooth.onDisable) { Text("Disattiva") }
                    }
                }
                if (bluetooth.status.isNotEmpty()) Text(bluetooth.status, color = Muted, fontSize = 12.sp)
            }
        }

        // Screen saver
        Card("🌙", "Risparmio schermo", "☾ o Volume giù per attivare · Volume su per uscire", Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = saverMode == SaverMode.BLACK,
                    onClick = { onSaverMode(SaverMode.BLACK) },
                    label = { Text("Nero OLED") },
                )
                FilterChip(
                    selected = saverMode == SaverMode.PANEL_OFF,
                    onClick = { onSaverMode(SaverMode.PANEL_OFF) },
                    label = { Text("Spegni pannello (anche LCD)") },
                )
                val (dot, text) = when (shizuku) {
                    ShizukuScreen.Availability.READY -> Ok to "Shizuku pronto"
                    ShizukuScreen.Availability.NO_PERMISSION -> Accent to "Shizuku attivo, permesso da concedere"
                    ShizukuScreen.Availability.NOT_RUNNING -> Faint to "Shizuku non attivo (serve per spegnere il pannello)"
                }
                Dot(dot)
                Text(text, color = Muted, fontSize = 13.sp)
                if (shizuku == ShizukuScreen.Availability.NO_PERMISSION) {
                    TextButton(onClick = onShizukuPermission) { Text("Concedi") }
                }
            }
            Text(
                "Uscita: Volume su · Back+Start tenuti 1,5 s sul controller · uscita dall'app. " +
                    "A pannello spento, se l'app si blocca lo schermo si riaccende da solo.",
                color = Faint,
                fontSize = 12.sp,
            )
        }
    }
}

/** Outline-only card: no fill, so the screen stays black on OLED panels. */
@Composable
private fun Card(
    icon: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxHeight()
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(icon, fontSize = 22.sp)
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(subtitle, color = Muted, fontSize = 13.sp)
        content()
    }
}

@Composable
private fun StatusPill(text: String, kind: StatusKind) {
    val color = when (kind) {
        StatusKind.CONNECTED -> Ok
        StatusKind.ERROR -> Error
        StatusKind.BUSY -> Accent
        StatusKind.IDLE -> Faint
    }
    Row(
        Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(color)
        Text(text, color = color, fontSize = 13.sp)
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** State and actions of the Bluetooth section. [pairedHosts]: address to name. */
class BluetoothPanel(
    val active: Boolean,
    val status: String,
    val pairedHosts: List<Pair<String, String>>,
    val onStart: () -> Unit,
    val onVisible: () -> Unit,
    val onDisable: () -> Unit,
    val onConnect: (String) -> Unit,
)
