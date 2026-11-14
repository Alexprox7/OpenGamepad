package org.opengamepad.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** App theme, chosen in the settings. [DARK] is the default. */
enum class AppTheme { DARK, OLED }

/** Neutral colours that change with the theme; accents and states are shared. */
@Immutable
class Palette(
    /** Screen background. */
    val background: Color,
    /** Cards and panels. */
    val surface: Color,
    /** Controls inside a card (fields, chips, list rows). */
    val surfaceHigh: Color,
    /** Borders and separators. */
    val outline: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    /** Controller buttons while pressed, and the stick knob. */
    val pressed: Color,
    val knob: Color,
    /** Navigation rail and bottom bar. */
    val bar: Color,
)

/** Anthracite surfaces, slightly lighter for each level. */
val DarkPalette = Palette(
    background = Color(0xFF131519),
    surface = Color(0xFF1B1E24),
    surfaceHigh = Color(0xFF252932),
    outline = Color(0xFF2F343E),
    text = Color(0xFFECEEF2),
    muted = Color(0xFF9BA2AF),
    faint = Color(0xFF6E7582),
    pressed = Color(0xFF353B47),
    knob = Color(0xFF4A515E),
    bar = Color(0xFF1B1E24),
)

/** Same hierarchy on pure black: OLED pixels stay off, cards are told apart by their outline. */
val OledPalette = Palette(
    background = Color.Black,
    surface = Color(0xFF0A0B0D),
    surfaceHigh = Color(0xFF15171B),
    outline = Color(0xFF262A31),
    text = Color(0xFFECEEF2),
    muted = Color(0xFF9BA2AF),
    faint = Color(0xFF6E7582),
    pressed = Color(0xFF262A33),
    knob = Color(0xFF3A3F4A),
    bar = Color.Black,
)

val Accent = Color(0xFF7C9CFF)
val Success = Color(0xFF4ADE80)
val Danger = Color(0xFFF87171)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

// Shorthands for the current theme, read where they are drawn.
val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
val Faint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.faint

@Composable
fun OpenGamepadTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val p = if (theme == AppTheme.OLED) OledPalette else DarkPalette
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Accent,
                onPrimary = Color(0xFF0B1230),
                primaryContainer = Accent.copy(alpha = 0.22f),
                onPrimaryContainer = p.text,
                secondaryContainer = Accent.copy(alpha = 0.22f),
                onSecondaryContainer = p.text,
                background = p.background,
                onBackground = p.text,
                surface = p.background,
                onSurface = p.text,
                onSurfaceVariant = p.muted,
                surfaceContainerLowest = p.background,
                surfaceContainerLow = p.surface,
                surfaceContainer = p.surface,
                surfaceContainerHigh = p.surfaceHigh,
                surfaceContainerHighest = p.surfaceHigh,
                outline = p.outline,
                outlineVariant = p.outline,
                error = Danger,
            ),
            content = content,
        )
    }
}
