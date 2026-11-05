package org.opengamepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opengamepad.input.GamepadInput
import org.opengamepad.macro.RadialView
import org.opengamepad.protocol.Buttons
import org.opengamepad.protocol.ControllerState

private val DPAD_MASK = Buttons.DPAD_UP or Buttons.DPAD_DOWN or Buttons.DPAD_LEFT or Buttons.DPAD_RIGHT

/** Standard Xbox-style layout. [blackout] draws a black layer while controls stay active. */
@Composable
fun ControllerScreen(
    input: GamepadInput,
    status: String,
    blackout: Boolean,
    radial: RadialView?,
    nintendoLayout: Boolean = false,
    onScreenSaver: () -> Unit,
    onDisconnect: () -> Unit,
    onRadialTap: (Int) -> Unit = {},
    onRadialBack: () -> Unit = {},
    steering: Boolean = false,
    onSteering: (() -> Unit)? = null,
    onRecenter: () -> Unit = {},
    /** Joy-Con-style motion sensor on/off; null hides the button (no gyroscope). */
    motionSensor: Boolean = false,
    onMotionSensor: (() -> Unit)? = null,
    /** Motion axis preset while the sensor is on (null hides the switch). */
    axisPreset: org.opengamepad.motion.AxisPreset? = null,
    onAxisPreset: (org.opengamepad.motion.AxisPreset) -> Unit = {},
    /** Phone vibration for the game's rumble. */
    rumble: Boolean = true,
    onRumble: (() -> Unit)? = null,
    /** Opens the amiibo wheel; null hides the button (not connected to a Switch). */
    onAmiibo: (() -> Unit)? = null,
) {
    fun button(mask: Int): (Boolean) -> Unit = { down -> input.touch { it.with(mask, down) } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val stick = (maxHeight * 0.42f).coerceAtMost(190.dp)
        val face = (maxHeight * 0.14f).coerceIn(48.dp, 72.dp)

        // Shoulders and triggers
        Row(Modifier.align(Alignment.TopStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadButton(if (nintendoLayout) "ZL" else "LT", { d -> input.touch { it.copy(leftTrigger = if (d) 255 else 0) } },
                Modifier.width(96.dp).height(52.dp), TriggerShape)
            PadButton(if (nintendoLayout) "L" else "LB", button(Buttons.LEFT_SHOULDER), Modifier.width(96.dp).height(52.dp), PillShape)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadButton(if (nintendoLayout) "R" else "RB", button(Buttons.RIGHT_SHOULDER), Modifier.width(96.dp).height(52.dp), PillShape)
            PadButton(if (nintendoLayout) "ZR" else "RT", { d -> input.touch { it.copy(rightTrigger = if (d) 255 else 0) } },
                Modifier.width(96.dp).height(52.dp), TriggerShape)
        }

        // Center: system buttons, screen saver, status
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PadButton("☾", { if (it) onScreenSaver() }, Modifier.size(44.dp), fontSize = 20)
                PadButton("✕", { if (it) onDisconnect() }, Modifier.size(44.dp), fontSize = 16)
                // Tilt steering on/off, and re-centre while it is on.
                if (onSteering != null) {
                    PadButton("⎈", { if (it) onSteering() }, Modifier.size(44.dp), fontSize = 20,
                        color = if (steering) Accent else Color.White)
                    if (steering) PadButton("◎", { if (it) onRecenter() }, Modifier.size(44.dp), fontSize = 18, color = Accent)
                }
                if (onMotionSensor != null) {
                    PadButton("⟲", { if (it) onMotionSensor() }, Modifier.size(44.dp), fontSize = 20,
                        color = if (motionSensor) Accent else Color.White)
                }
                // Motion axes per game: kart for Mario Kart, top hat (Cappy) for Super Mario Odyssey.
                if (axisPreset != null) {
                    for ((preset, icon) in listOf(org.opengamepad.motion.AxisPreset.KART to "🏎",
                        org.opengamepad.motion.AxisPreset.CAPPY to "🎩")) {
                        PadButton(icon, { if (it) onAxisPreset(preset) }, Modifier.size(44.dp), fontSize = 18,
                            color = if (axisPreset == preset) Accent else Color(0xFF5F6673))
                    }
                }
                if (onRumble != null) {
                    PadButton("📳", { if (it) onRumble() }, Modifier.size(44.dp), fontSize = 16,
                        color = if (rumble) Accent else Color(0xFF5F6673))
                }
                if (onAmiibo != null) PadButton("NFC", { if (it) onAmiibo() }, Modifier.size(44.dp), fontSize = 12)
            }
            Text(status, color = Color(0xFF8A91A0), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                PadButton(if (nintendoLayout) "−" else "⧉", button(Buttons.BACK), Modifier.size(46.dp))
                PadButton("⊙", button(Buttons.GUIDE), Modifier.size(56.dp), fontSize = 22)
                PadButton(if (nintendoLayout) "+" else "☰", button(Buttons.START), Modifier.size(46.dp))
            }
        }

        // Left side: stick + D-pad + L3
        Stick(
            onMove = { x, y -> input.touch { it.copy(leftX = ControllerState.axis(x), leftY = ControllerState.axis(y)) } },
            modifier = Modifier.align(Alignment.CenterStart).offset(x = 36.dp, y = (-6).dp),
            size = stick,
        )
        DPad(
            onChange = { mask -> input.touch { it.copy(buttons = (it.buttons and DPAD_MASK.inv()) or mask) } },
            modifier = Modifier.align(Alignment.BottomStart).offset(x = stick + 40.dp, y = (-16).dp),
            size = (stick * 0.8f),
        )
        PadButton("L3", button(Buttons.LEFT_THUMB),
            Modifier.align(Alignment.BottomStart).offset(x = 20.dp, y = (-18).dp).size(48.dp), fontSize = 13)

        // Right side: ABXY + stick + R3
        Box(Modifier.align(Alignment.CenterEnd).offset(x = (-36).dp, y = (-20).dp).size(face * 3)) {
            PadButton(if (nintendoLayout) "X" else "Y", button(Buttons.Y), Modifier.align(Alignment.TopCenter).size(face), color = Color(0xFFF2C94C))
            PadButton(if (nintendoLayout) "Y" else "X", button(Buttons.X), Modifier.align(Alignment.CenterStart).size(face), color = Color(0xFF56A8F5))
            PadButton(if (nintendoLayout) "A" else "B", button(Buttons.B), Modifier.align(Alignment.CenterEnd).size(face), color = Color(0xFFEB5757))
            PadButton(if (nintendoLayout) "B" else "A", button(Buttons.A), Modifier.align(Alignment.BottomCenter).size(face), color = Color(0xFF6FCF97))
        }
        Stick(
            onMove = { x, y -> input.touch { it.copy(rightX = ControllerState.axis(x), rightY = ControllerState.axis(y)) } },
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = -(face * 3 + 56.dp), y = (-12).dp),
            size = stick * 0.85f,
        )
        PadButton("R3", button(Buttons.RIGHT_THUMB),
            Modifier.align(Alignment.BottomEnd).offset(x = (-20).dp, y = (-18).dp).size(48.dp), fontSize = 13)

        // Drawn last, without pointer input: touches still reach the controls underneath.
        if (blackout) Box(Modifier.fillMaxSize().background(Color.Black))
        // Above the black layer: the wheel must stay visible in OLED mode.
        radial?.let {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)))
            RadialMenuOverlay(it, nintendoLayout = nintendoLayout, onTap = onRadialTap, onBack = onRadialBack)
        }
    }
}
