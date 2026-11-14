package org.opengamepad.ui

import org.opengamepad.str
import org.opengamepad.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// Geometry shared by the drawing and the tap test: keep them together.
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
    val p = LocalPalette.current
    // Quick zoom-in when the wheel opens (drawing only: input is unaffected).
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(130, easing = FastOutSlowInEasing)) }
    BoxWithConstraints(modifier) {
        val nested = view.trail.size > 1
        RadialWheel(
            view.menu,
            view.highlighted,
            Modifier.fillMaxSize().graphicsLayer {
                alpha = appear.value
                scaleX = 0.94f + 0.06f * appear.value
                scaleY = scaleX
            },
            hub = view.trail.lastOrNull()?.takeIf { nested },
        )
        if (nested) {
            Text(
                view.trail.joinToString("  ›  "),
                color = p.text,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            )
        }
        if (view.sticky) {
            // Face buttons by position: the south one is labelled B on a Nintendo layout.
            val confirm = if (nintendoLayout) "B" else "A"
            val cancel = if (nintendoLayout) "A" else "B"
            Text(
                str(R.string.radial_hint, confirm, cancel, str(if (nested) R.string.radial_back else R.string.radial_close)),
                color = p.muted,
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
 * Weapon-wheel style menu: ring slices with even gaps, each with its own colour and icon.
 * Unselected slices are tinted down towards the surface; the selected one is lit in its full
 * colour, grows slightly and is named in the hub. Folders get a badge; [hub] (the folder being
 * browsed) takes the centre when set.
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
    val p = LocalPalette.current
    // Selection "pop": restarts on each new highlighted slice.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(highlighted) {
        if (highlighted != null) {
            pop.snapTo(0f)
            pop.animateTo(1f, tween(140, easing = FastOutSlowInEasing))
        }
    }
    Canvas(modifier) {
        val count = menu.slices.size
        if (count == 0) return@Canvas
        val center = Offset(size.width / 2, size.height / 2)
        val outer = min(size.width, size.height) * OUTER
        val inner = outer * INNER
        val sweep = 360f / count
        val gap = if (count == 1) 0f else (3.dp.toPx() * scale).coerceAtLeast(1.5f)
        val ringGap = 4.dp.toPx() * scale

        // Faint track: the ring stays readable on any background.
        drawCircle(p.background.copy(alpha = 0.55f), outer + 6.dp.toPx() * scale, center)

        menu.slices.forEachIndexed { i, slice ->
            val selected = i == highlighted
            val r = if (selected) outer * (1f + 0.05f * pop.value) else outer
            // Slice i is centred on its angle (0 = up, clockwise); Compose's 0° is "right".
            val start = -90f + i * sweep - sweep / 2
            val path = ringSlice(center, inner + ringGap, r, start, if (count == 1) 359.9f else sweep, gap)
            val base = Color(slice.color)
            drawPath(path, if (selected) base else lerp(p.surface, base, 0.42f))
            drawPath(
                path,
                if (selected) Color.White.copy(alpha = 0.92f) else lerp(p.outline, base, 0.5f),
                style = Stroke(if (selected) 2.5.dp.toPx() else 1.dp.toPx()),
            )
        }

        // Hub: where the stick rests. A notch on its edge points at the selection.
        drawCircle(p.surface, inner, center)
        drawCircle(p.outline, inner, center, style = Stroke(1.5.dp.toPx()))
        if (highlighted != null && highlighted in menu.slices.indices) {
            val mid = -90f + highlighted * sweep
            val ring = inner - 4.dp.toPx() * scale
            drawArc(
                Accent,
                startAngle = mid - min(22f, sweep / 2.5f),
                sweepAngle = min(44f, sweep / 1.25f),
                useCenter = false,
                topLeft = center - Offset(ring, ring),
                size = androidx.compose.ui.geometry.Size(ring * 2, ring * 2),
                style = Stroke(3.dp.toPx() * scale.coerceAtLeast(0.6f), cap = StrokeCap.Round),
            )
        }
        val hubText = hub?.let { "‹ $it" }
            ?: highlighted?.let { menu.slices.getOrNull(it)?.label }?.takeIf { scale >= 0.6f && it.isNotBlank() }
        if (hubText != null) {
            val text = measurer.measure(
                hubText,
                TextStyle(
                    color = p.text,
                    fontSize = (14 * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
                constraints = Constraints(maxWidth = (inner * 1.55f).toInt().coerceAtLeast(1)),
            )
            drawText(text, topLeft = center - Offset(text.size.width / 2f, text.size.height / 2f))
        }

        // Icons and labels, centred in the ring and kept inside their slice.
        val mid = (outer + inner + ringGap) / 2
        val labelWidth = (if (count == 1) outer else 2 * mid * sin(PI.toFloat() / count) * 0.86f).coerceAtLeast(1f)
        menu.slices.forEachIndexed { i, slice ->
            val selected = i == highlighted
            val angle = (-90f + i * sweep) * PI.toFloat() / 180f
            val direction = Offset(cos(angle), sin(angle))
            val at = center + direction * (if (selected) mid * (1f + 0.04f * pop.value) else mid)
            val icon = measurer.measure(slice.icon, TextStyle(fontSize = ((if (selected) 32 else 28) * scale).sp))
            val label = measurer.measure(
                slice.label,
                TextStyle(
                    color = if (selected) Color.White else p.text.copy(alpha = 0.82f),
                    fontSize = (13 * scale).sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                constraints = Constraints(maxWidth = labelWidth.toInt()),
            )
            val hasIcon = slice.icon.isNotEmpty()
            val spacing = if (hasIcon) 2.dp.toPx() * scale else 0f
            val total = (if (hasIcon) icon.size.height else 0) + spacing + label.size.height
            var y = at.y - total / 2f
            if (hasIcon) {
                drawText(icon, topLeft = Offset(at.x - icon.size.width / 2f, y))
                y += icon.size.height + spacing
            }
            drawText(label, topLeft = Offset(at.x - label.size.width / 2f, y))
            if (slice.isFolder) {
                val badge = measurer.measure("📁", TextStyle(fontSize = (11 * scale).sp))
                val spot = center + direction * (outer * 0.9f)
                drawText(badge, topLeft = spot - Offset(badge.size.width / 2f, badge.size.height / 2f))
            }
        }
    }
}

/** Ring sector from [startDeg] spanning [sweepDeg], with straight [gap]-wide cuts on both sides. */
private fun ringSlice(center: Offset, inner: Float, outer: Float, startDeg: Float, sweepDeg: Float, gap: Float): Path {
    fun inset(radius: Float) = (asin((gap / 2 / radius).coerceIn(0f, 1f)) * 180f / PI.toFloat())
    val o = inset(outer)
    val n = inset(inner)
    return Path().apply {
        arcTo(Rect(center, outer), startDeg + o, sweepDeg - 2 * o, forceMoveTo = true)
        arcTo(Rect(center, inner), startDeg + sweepDeg - n, -(sweepDeg - 2 * n), forceMoveTo = false)
        close()
    }
}
