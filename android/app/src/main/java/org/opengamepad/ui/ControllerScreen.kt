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
    onScreenSaver: () -> Unit,
    onDisconnect: () -> Unit,
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
            PadButton("LT", { d -> input.touch { it.copy(leftTrigger = if (d) 255 else 0) } },
                Modifier.width(96.dp).height(52.dp), TriggerShape)
            PadButton("LB", button(Buttons.LEFT_SHOULDER), Modifier.width(96.dp).height(52.dp), PillShape)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadButton("RB", button(Buttons.RIGHT_SHOULDER), Modifier.width(96.dp).height(52.dp), PillShape)
            PadButton("RT", { d -> input.touch { it.copy(rightTrigger = if (d) 255 else 0) } },
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
            }
            Text(status, color = Color(0xFF8A91A0), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                PadButton("⧉", button(Buttons.BACK), Modifier.size(46.dp))
                PadButton("⊙", button(Buttons.GUIDE), Modifier.size(56.dp), fontSize = 22)
                PadButton("☰", button(Buttons.START), Modifier.size(46.dp))
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
            PadButton("Y", button(Buttons.Y), Modifier.align(Alignment.TopCenter).size(face), color = Color(0xFFF2C94C))
            PadButton("X", button(Buttons.X), Modifier.align(Alignment.CenterStart).size(face), color = Color(0xFF56A8F5))
            PadButton("B", button(Buttons.B), Modifier.align(Alignment.CenterEnd).size(face), color = Color(0xFFEB5757))
            PadButton("A", button(Buttons.A), Modifier.align(Alignment.BottomCenter).size(face), color = Color(0xFF6FCF97))
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
            RadialMenuOverlay(it)
        }
    }
}
