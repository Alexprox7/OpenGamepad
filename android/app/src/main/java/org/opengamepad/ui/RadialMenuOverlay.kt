package org.opengamepad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import org.opengamepad.macro.RadialMenu
import org.opengamepad.macro.RadialView
import org.opengamepad.macro.radialSliceAt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private const val OUTER = 0.46f // of the shorter side
private const val INNER = 0.30f // of the outer radius

/**
 * Radial menu shown while its combo is held, or until confirmed when sticky. The hub shows the
 * folder being browsed. A sticky wheel also takes taps: a slice confirms it, the hub goes back.
 * Hold wheels take no touches, so the on-screen combo and stick keep working underneath.
 */
@Composable
fun RadialMenuOverlay(
    view: RadialView,
    modifier: Modifier = Modifier.fillMaxSize(),
    nintendoLayout: Boolean = false,
    onTap: ((Int) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val nested = view.trail.size > 1
        RadialWheel(view.menu, view.highlighted, Modifier.fillMaxSize(), hub = view.trail.lastOrNull()?.takeIf { nested })
        if (nested) {
            Text(
                view.trail.joinToString("  ›  "),
                color = Color(0xFFD0D4DC),
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            )
        }
        if (view.sticky) {
            // Face buttons by position: the south one is labelled B on a Nintendo layout.
            val confirm = if (nintendoLayout) "B" else "A"
            val cancel = if (nintendoLayout) "A" else "B"
            Text(
                "$confirm o tocca: scegli\n$cancel: " + if (nested) "indietro" else "chiudi",
                color = Color(0xFF8A91A0),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            )
            if (onTap != null) {
                val side = min(maxWidth, maxHeight) * (OUTER * 2)
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(side)
                        .pointerInput(view.menu, onTap, onBack) {
                            detectTapGestures { at ->
                                val outer = size.width / 2f
                                val x = at.x - outer
                                val y = outer - at.y
                                val distance = hypot(x, y)
                                val count = view.menu.slices.size
                                when {
                                    distance < outer * INNER -> onBack?.invoke()
                                    distance <= outer && count > 0 -> onTap(radialSliceAt(x, y, count))
                                }
                            }
                        },
                )
            }
        }
    }
}

/**
 * Weapon-wheel style menu: each slice has its own colour and icon. Slices are dimmed
 * unless selected, so most of the screen stays dark. Folders get a badge; [hub] is written
 * in the centre (the folder being browsed).
 */
@Composable
fun RadialWheel(
    menu: RadialMenu,
    highlighted: Int?,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    hub: String? = null,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val count = menu.slices.size
        if (count == 0) return@Canvas
        val center = Offset(size.width / 2, size.height / 2)
        val outer = min(size.width, size.height) * OUTER
        val inner = outer * INNER
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
        if (hub != null) {
            val text = measurer.measure(
                "‹ $hub",
                TextStyle(color = Color.White, fontSize = (13 * scale).sp, textAlign = TextAlign.Center),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                constraints = Constraints(maxWidth = (inner * 1.6f).toInt().coerceAtLeast(1)),
            )
            drawText(text, topLeft = center - Offset(text.size.width / 2f, text.size.height / 2f))
        }

        menu.slices.forEachIndexed { i, slice ->
            val selected = i == highlighted
            val angle = (-90f + i * sweep) * PI.toFloat() / 180f
            val direction = Offset(cos(angle), sin(angle))
            val at = center + direction * ((outer + inner) / 2)
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
            if (slice.isFolder) {
                val badge = measurer.measure("📁", TextStyle(fontSize = (13 * scale).sp))
                val spot = center + direction * (outer * 0.9f)
                drawText(badge, topLeft = spot - Offset(badge.size.width / 2f, badge.size.height / 2f))
            }
        }
    }
}
