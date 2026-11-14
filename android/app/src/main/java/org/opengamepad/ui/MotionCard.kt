package org.opengamepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opengamepad.motion.AxisPreset
import org.opengamepad.motion.GyroMode
import org.opengamepad.motion.MotionController
import org.opengamepad.motion.MotionSettings
import org.opengamepad.protocol.Buttons
import kotlin.math.roundToInt

private val ShakeButtons = listOf(
    "A" to Buttons.A, "B" to Buttons.B, "X" to Buttons.X, "Y" to Buttons.Y,
    "LB" to Buttons.LEFT_SHOULDER, "RB" to Buttons.RIGHT_SHOULDER,
    "L3" to Buttons.LEFT_THUMB, "R3" to Buttons.RIGHT_THUMB,
    "Su" to Buttons.DPAD_UP, "Giù" to Buttons.DPAD_DOWN,
    "Back" to Buttons.BACK, "Start" to Buttons.START,
)

/** Motion settings: tilt steering, shake, real gyro (Switch, Bluetooth Pro Controller, DSU). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MotionCard(motion: MotionController) {
    val s by motion.settings.collectAsState()
    val steer by motion.steer.collectAsState()
    val dsuStatus by motion.dsu.status.collectAsState()
    val set = motion::update

    Card(
        "🧭",
        "Movimento",
        "Sterzo inclinando il telefono, scuotimento e giroscopio reale · tutto spento finché non lo attivi",
        Modifier.fillMaxWidth(),
    ) {
        if (!motion.hasTilt) {
            Text("Questo telefono non ha sensori di movimento", color = Muted, fontSize = 13.sp)
            return@Card
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            maxItemsInEachRow = if (LocalConfiguration.current.screenWidthDp < 600) 1 else 3,
        ) {
            // Real gyro
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!motion.hasGyro) {
                    Text("Giroscopio non disponibile su questo telefono", color = Muted, fontSize = 13.sp)
                    return@Column
                }
                Toggle("Sensore di movimento (come Joy-Con)", s.gyro) { on -> set { it.copy(gyro = on) } }
                Text("Il controller virtuale ha il sensore come un Joy-Con (sysmodule via Wi‑Fi, Pro Controller Bluetooth). Su PC/Xbox non ha effetto.",
                    color = Faint, fontSize = 12.sp)
                if (s.gyro) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = s.gyroMode == GyroMode.SHAKE,
                            onClick = { set { it.copy(gyroMode = GyroMode.SHAKE) } },
                            label = { Text("Solo scuotimento") },
                        )
                        FilterChip(
                            selected = s.gyroMode == GyroMode.FULL,
                            onClick = { set { it.copy(gyroMode = GyroMode.FULL) } },
                            label = { Text("Completo") },
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = s.axes == AxisPreset.KART,
                            onClick = { set { it.copy(axes = AxisPreset.KART) } },
                            label = { Text("🏎 Assi kart") },
                        )
                        FilterChip(
                            selected = s.axes == AxisPreset.CAPPY,
                            onClick = { set { it.copy(axes = AxisPreset.CAPPY) } },
                            label = { Text("🎩 Assi Cappy") },
                        )
                    }
                    if (s.axes == AxisPreset.KART) {
                        Setting("Sensibilità sterzo", "×%.1f · %d° per 90°".format(s.kartGain, (90 / s.kartGain).roundToInt()),
                            s.kartGain, 1f..4f) { v -> set { it.copy(kartGain = (v * 4).roundToInt() / 4f) } }
                    }
                    Text("◎ sul controller: tieni il telefono nella posizione di gioco e toccalo per impostare la posizione neutra.",
                        color = Faint, fontSize = 12.sp)
                    if (s.gyroMode == GyroMode.SHAKE) {
                        Text("Agita il telefono come un Joy-Con (es. lancio del cappello in Mario Odyssey): il gioco lo legge dal sensore, la visuale non si muove.",
                            color = Faint, fontSize = 12.sp)
                        Setting("Forza scossa", "×%.1f".format(s.shakeGain), s.shakeGain, 1f..5f) { v ->
                            set { it.copy(shakeGain = (v * 2).roundToInt() / 2f) }
                        }
                    } else {
                        Text("Anche la rotazione del telefono: visuale e mira con il giroscopio, sterzo nei giochi che lo usano.",
                            color = Faint, fontSize = 12.sp)
                    }
                }
                Toggle("Server DSU per emulatori", s.dsu) { on -> set { it.copy(dsu = on) } }
                Text("Cemu, Ryujinx, Dolphin, Citra… ricevono il movimento via Wi‑Fi.", color = Faint, fontSize = 12.sp)
                if (s.dsu) {
                    val addresses = motion.localAddresses().joinToString(" · ").ifEmpty { "nessuna rete" }
                    Text("Nell'emulatore: server $addresses, porta ${MotionSettings.DSU_PORT}", color = Muted, fontSize = 13.sp)
                    if (dsuStatus.isNotEmpty()) Text(dsuStatus, color = Muted, fontSize = 12.sp)
                }
            }
            // Tilt steering
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle("Sterzo inclinando (stick)", s.steering) { on -> set { it.copy(steering = on) } }
                Text("Ruota il telefono come un volante: muove lo stick sinistro, anche su PC. Spegne il sensore di movimento.",
                    color = Faint, fontSize = 12.sp)
                if (s.steering) {
                    SteerBar(steer ?: 0f)
                    Setting("Angolo massimo", "${s.maxAngle.roundToInt()}°", s.maxAngle, 10f..90f) { v ->
                        set { it.copy(maxAngle = v.roundToInt().toFloat()) }
                    }
                    Setting("Zona morta", "${s.deadZone.roundToInt()}°", s.deadZone, 0f..15f) { v ->
                        set { it.copy(deadZone = v.roundToInt().toFloat()) }
                    }
                    val curve = when {
                        s.curve < 0.9f -> "rapida"
                        s.curve > 1.1f -> "precisa al centro"
                        else -> "lineare"
                    }
                    Setting("Curva", "%.1f · %s".format(s.curve, curve), s.curve, 0.5f..2.5f) { v ->
                        set { it.copy(curve = (v * 10).roundToInt() / 10f) }
                    }
                    Toggle("Anche avanti/indietro (stick Y)", s.pitchY) { on -> set { it.copy(pitchY = on) } }
                    OutlinedButton(onClick = motion::recenter) { Text("Centra") }
                    Text("Centra: tieni il telefono in posizione di guida e tocca. Anche con ◎ sul controller.",
                        color = Faint, fontSize = 12.sp)
                }
            }
            // Shake
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Toggle("Scuoti per premere", s.shake) { on -> set { it.copy(shake = on) } }
                Text("Una scossa preme un tasto per un istante.", color = Faint, fontSize = 12.sp)
                if (s.shake) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShakeButtons.forEach { (label, mask) ->
                            FilterChip(
                                selected = s.shakeButton == mask,
                                onClick = { set { it.copy(shakeButton = mask) } },
                                label = { Text(label) },
                            )
                        }
                    }
                    Setting("Sensibilità", "${(s.shakeSensitivity * 100).roundToInt()}%", s.shakeSensitivity, 0f..1f) { v ->
                        set { it.copy(shakeSensitivity = (v * 20).roundToInt() / 20f) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun Setting(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = Color.White, fontSize = 13.sp)
        }
        Slider(value = current.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

/** Live steering output: the marker moves from the center towards the side being steered. */
@Composable
private fun SteerBar(value: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, Color(0xFF30343D), RoundedCornerShape(7.dp)),
    ) {
        Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(Color(0xFF30343D)))
        Layout(
            content = { Box(Modifier.width(10.dp).fillMaxHeight().background(Accent)) },
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        ) { measurables, constraints ->
            val marker = measurables.first().measure(constraints.copy(minWidth = 0))
            val travel = (constraints.maxWidth - marker.width) / 2f
            layout(constraints.maxWidth, constraints.maxHeight) {
                marker.place((travel + value.coerceIn(-1f, 1f) * travel).roundToInt(), 0)
            }
        }
    }
}
