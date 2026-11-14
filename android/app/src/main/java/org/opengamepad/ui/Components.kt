package org.opengamepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val CardShape = RoundedCornerShape(18.dp)

/** Section card: icon tile, title, one-line description, then the content. */
@Composable
internal fun Card(
    icon: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(CardShape)
            .background(p.surface)
            .border(1.dp, p.outline, CardShape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) { Text(icon, fontSize = 20.sp) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = p.muted, fontSize = 13.sp, lineHeight = 17.sp)
            }
        }
        content()
    }
}

/** Clickable list row inside a screen (macros, wheels). */
@Composable
internal fun ListRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(p.surface)
            .border(1.dp, p.outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Small caps label above a group of controls. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = modifier)
}

/** Title bar of an editor screen: back, title, then the actions (wrapping on narrow screens). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit, backLabel: String = "‹", actions: @Composable RowScope.() -> Unit = {}) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(backLabel, fontSize = if (backLabel == "‹") 24.sp else 14.sp) }
            Text(title, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.align(Alignment.CenterVertically),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** Combination shown as key caps, e.g. [L3] + [R3]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyCaps(text: String) {
    val p = LocalPalette.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.split(" + ").forEachIndexed { i, key ->
            if (i > 0) Text("+", color = p.faint, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
            Text(
                key,
                color = p.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(p.surfaceHigh)
                    .border(1.dp, p.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
internal fun StatusPill(text: String, kind: StatusKind) {
    val color = when (kind) {
        StatusKind.CONNECTED -> Success
        StatusKind.ERROR -> Danger
        StatusKind.BUSY -> Accent
        StatusKind.IDLE -> Faint
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(color)
        Text(text, color = if (kind == StatusKind.IDLE) Muted else color, fontSize = 13.sp)
    }
}

@Composable
internal fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}
