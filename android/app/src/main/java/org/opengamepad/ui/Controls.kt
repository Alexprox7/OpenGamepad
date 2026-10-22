package org.opengamepad.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opengamepad.protocol.Buttons
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

// Pitch-black theme: on OLED panels black pixels are off, so controls are drawn
// as thin dim outlines and only lit while pressed.
private val Surface = Color.Black
private val SurfacePressed = Color(0xFF262A33)
private val Outline = Color(0xFF30343D)
private val Knob = Color(0xFF3A3F4A)

/** Momentary button: [onChange] receives true on press and false on release. */
@Composable
fun PadButton(
    label: String,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    color: Color = Color.White,
    fontSize: Int = 18,
) {
    val view = LocalView.current
    val callback by rememberUpdatedState(onChange)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) SurfacePressed else Surface)
            .border(1.dp, Outline, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    callback(true)
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    waitForUpOrCancellation()
                    pressed = false
                    callback(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color.copy(alpha = 0.7f), fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
    }
}

/** Analog stick: [onMove] receives x/y in -1..1 with up positive. */
@Composable
fun Stick(
    onMove: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
) {
    val callback by rememberUpdatedState(onMove)
    var knob by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier
            .size(size)
            .pointerInput(Unit) {
                val radius = min(this.size.width, this.size.height) / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                fun update(position: Offset) {
                    var d = position - center
                    val length = d.getDistance()
                    if (length > radius) d = d * (radius / length)
                    knob = d
                    callback(d.x / radius, -d.y / radius)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position)
                    drag(down.id) { change -> update(change.position) }
                    knob = Offset.Zero
                    callback(0f, 0f)
                }
            },
    ) {
        val radius = min(this.size.width, this.size.height) / 2f
        drawCircle(Outline, radius - 1f, style = Stroke(2f))
        drawCircle(Knob, radius * 0.38f, center + knob)
    }
}

/** 8-way D-pad: [onChange] receives the mask of pressed directions. */
@Composable
fun DPad(
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
) {
    val view = LocalView.current
    val callback by rememberUpdatedState(onChange)
    var current by remember { mutableStateOf(0) }
    Canvas(
        modifier
            .size(size)
            .pointerInput(Unit) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val deadZone = min(this.size.width, this.size.height) * 0.12f
                fun update(position: Offset) {
                    val d = position - center
                    val mask = if (hypot(d.x, d.y) < deadZone) 0 else directionMask(atan2(-d.y, d.x))
                    if (mask != current) {
                        current = mask
                        callback(mask)
                        if (mask != 0) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position)
                    drag(down.id) { change -> update(change.position) }
                    current = 0
                    callback(0)
                }
            },
    ) {
        val arm = this.size.width / 3f
        fun arm(x: Float, y: Float, on: Boolean) = drawRoundRect(
            if (on) Knob else Outline,
            topLeft = Offset(x, y),
            size = androidx.compose.ui.geometry.Size(arm, arm),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f),
            style = if (on) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2f),
        )
        arm(arm, 0f, current and Buttons.DPAD_UP != 0)
        arm(arm, arm * 2, current and Buttons.DPAD_DOWN != 0)
        arm(0f, arm, current and Buttons.DPAD_LEFT != 0)
        arm(arm * 2, arm, current and Buttons.DPAD_RIGHT != 0)
    }
}

/** Splits the circle in 8 sectors of 45°; diagonals press two directions. */
private fun directionMask(angle: Float): Int {
    val sector = Math.floorMod(Math.round(angle / (Math.PI / 4)).toInt(), 8)
    return when (sector) {
        0 -> Buttons.DPAD_RIGHT
        1 -> Buttons.DPAD_RIGHT or Buttons.DPAD_UP
        2 -> Buttons.DPAD_UP
        3 -> Buttons.DPAD_UP or Buttons.DPAD_LEFT
        4 -> Buttons.DPAD_LEFT
        5 -> Buttons.DPAD_LEFT or Buttons.DPAD_DOWN
        6 -> Buttons.DPAD_DOWN
        else -> Buttons.DPAD_DOWN or Buttons.DPAD_RIGHT
    }
}

val PillShape = RoundedCornerShape(50)
val TriggerShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
