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

// Controls are drawn with the theme's surfaces: a soft fill in the dark theme, near-black on OLED,
// and lit only while pressed.

/** Momentary button: [onChange] receives true on press and false on release. */
@Composable
fun PadButton(
    label: String,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    color: Color = Color.Unspecified,
    fontSize: Int = 18,
    /** On/off buttons: blue border and fill while on (emoji labels cannot change colour). */
    selected: Boolean = false,
) {
    val view = LocalView.current
    val p = LocalPalette.current
    val callback by rememberUpdatedState(onChange)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) p.pressed else if (selected) Accent.copy(alpha = 0.22f) else p.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) Accent else if (pressed) p.knob else p.outline, shape)
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
        val ink = if (color == Color.Unspecified) p.text else color
        Text(label, color = ink.copy(alpha = if (pressed) 1f else 0.75f), fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
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
    val p = LocalPalette.current
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
        drawCircle(p.surface, radius - 1f)
        drawCircle(p.outline, radius - 1f, style = Stroke(2f))
        // Rest area: where the thumb starts.
        drawCircle(p.outline.copy(alpha = 0.6f), radius * 0.38f, style = Stroke(1.5f))
        drawCircle(if (knob == Offset.Zero) p.knob else Accent.copy(alpha = 0.85f), radius * 0.36f, center + knob)
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
    val p = LocalPalette.current
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
        fun arm(x: Float, y: Float, on: Boolean) {
            val size = androidx.compose.ui.geometry.Size(arm, arm)
            val corner = androidx.compose.ui.geometry.CornerRadius(10f)
            drawRoundRect(if (on) p.pressed else p.surface, Offset(x, y), size, corner)
            drawRoundRect(if (on) Accent.copy(alpha = 0.85f) else p.outline, Offset(x, y), size, corner, style = Stroke(2f))
        }
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
