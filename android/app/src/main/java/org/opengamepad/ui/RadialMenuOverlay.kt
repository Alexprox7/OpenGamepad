package org.opengamepad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.opengamepad.macro.RadialMenu
import org.opengamepad.macro.RadialView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Radial menu shown while its combo is held. */
@Composable
fun RadialMenuOverlay(view: RadialView, modifier: Modifier = Modifier.fillMaxSize()) {
    RadialWheel(view.menu, view.highlighted, modifier)
}

/**
 * Weapon-wheel style menu: each slice has its own colour and icon. Slices are dimmed
 * unless selected, so most of the screen stays dark.
 */
@Composable
fun RadialWheel(menu: RadialMenu, highlighted: Int?, modifier: Modifier = Modifier, scale: Float = 1f) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val count = menu.slices.size
        if (count == 0) return@Canvas
        val center = Offset(size.width / 2, size.height / 2)
        val outer = min(size.width, size.height) * 0.46f
        val inner = outer * 0.30f
        val sweep = 360f / count
        val topLeft = center - Offset(outer, outer)
        val arcSize = Size(outer * 2, outer * 2)

        menu.slices.forEachIndexed { i, slice ->
            val selected = i == highlighted
            // Slice i is centred on its angle (0 = up, clockwise); Compose's 0° is "right".
            val start = -90f + i * sweep - sweep / 2
            drawArc(
                color = Color(slice.color).copy(alpha = if (selected) 1f else 0.42f),
                startAngle = start + 1f,
                sweepAngle = sweep - 2f,
                useCenter = true,
                topLeft = topLeft,
                size = arcSize,
            )
            if (selected) {
                drawArc(Color.White, start + 1f, sweep - 2f, true, topLeft, arcSize, style = Stroke(3f))
            }
        }
        // Black hub: the centre is where the stick rests.
        drawCircle(Color.Black, inner, center)
        drawCircle(Color(0xFF3A3F4A), inner, center, style = Stroke(2f))

        menu.slices.forEachIndexed { i, slice ->
            val selected = i == highlighted
            val angle = (-90f + i * sweep) * PI.toFloat() / 180f
            val at = center + Offset(cos(angle), sin(angle)) * ((outer + inner) / 2)
            val icon = measurer.measure(slice.icon, TextStyle(fontSize = (30 * scale).sp))
            val label = measurer.measure(
                slice.label,
                TextStyle(
                    color = if (selected) Color.White else Color(0xFFD0D4DC),
                    fontSize = (14 * scale).sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                ),
            )
            val total = (if (slice.icon.isNotEmpty()) icon.size.height else 0) + label.size.height
            var y = at.y - total / 2f
            if (slice.icon.isNotEmpty()) {
                drawText(icon, topLeft = Offset(at.x - icon.size.width / 2f, y))
                y += icon.size.height
            }
            drawText(label, topLeft = Offset(at.x - label.size.width / 2f, y))
        }
    }
}
