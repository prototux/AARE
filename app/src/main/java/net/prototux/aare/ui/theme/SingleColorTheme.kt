package net.prototux.aare.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle

/** [c] darkened toward black: [f] = 1 is [c], 0 is black (mixed in sRGB, as a screen dims). */
private fun dim(c: Color, f: Float) = Color(c.red * f, c.green * f, c.blue * f)

/**
 * A single-colour theme in [accent]'s colour: black, surfaces faintly lit in it, text in it, like an
 * old monochrome screen or a car's night lighting. Text is a little dimmer at night (not more: red,
 * the darkest, would fall short of the contrast every theme must reach). Calls and the colours apps
 * use for warnings keep their own hues, so a warning still reads as one.
 */
internal fun CarThemeSpec.tinted(accent: CarAccent): CarThemeSpec {
    val t = accent.dark.primary
    val pale = accent.dark.onContainer
    fun scheme(night: Boolean) = darkColorScheme(
        primary = if (night) dim(t, 0.9f) else t,
        onPrimary = Color.Black,
        primaryContainer = dim(t, 0.25f),
        onPrimaryContainer = pale,
        secondary = t,
        onSecondary = Color.Black,
        secondaryContainer = dim(t, 0.18f),
        onSecondaryContainer = pale,
        tertiary = t,
        onTertiary = Color.Black,
        background = Color.Black,
        onBackground = if (night) dim(pale, 0.8f) else pale,
        surface = Color.Black,
        onSurface = if (night) dim(pale, 0.8f) else pale,
        surfaceVariant = dim(t, 0.09f),
        onSurfaceVariant = if (night) dim(t, 0.9f) else t,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = dim(t, 0.05f),
        surfaceContainer = dim(t, 0.09f),
        surfaceContainerHigh = dim(t, 0.12f),
        surfaceContainerHighest = dim(t, 0.16f),
        outline = dim(t, 0.5f),
        outlineVariant = dim(t, 0.25f),
        error = t,
    )
    return copy(
        day = scheme(night = false),
        night = scheme(night = true),
        dayColors = AareTheme.nightColors,
        nightColors = AareTheme.nightColors,
        typography = if (glow) typography.glowing(t) else typography,
    )
}

/** Every style with a soft halo in [color], like the phosphor of a CRT. */
private fun Typography.glowing(color: Color): Typography {
    val shadow = Shadow(color = color.copy(alpha = 0.55f), blurRadius = 12f)
    fun TextStyle.lit() = copy(shadow = shadow)
    return Typography(
        displayLarge.lit(), displayMedium.lit(), displaySmall.lit(),
        headlineLarge.lit(), headlineMedium.lit(), headlineSmall.lit(),
        titleLarge.lit(), titleMedium.lit(), titleSmall.lit(),
        bodyLarge.lit(), bodyMedium.lit(), bodySmall.lit(),
        labelLarge.lit(), labelMedium.lit(), labelSmall.lit(),
    )
}

/**
 * How app icons are drawn: in the accent colour, by brightness, on a theme that asks for it (as a
 * monochrome screen would show them); null (their own colours) otherwise.
 */
@Composable
fun appIconFilter(): ColorFilter? {
    if (!LocalCarTheme.current.tintIcons) return null
    val t = MaterialTheme.colorScheme.primary
    // Each output channel is the pixel's luminance times the accent's channel; alpha kept.
    fun row(c: Float) = floatArrayOf(0.2126f * c, 0.7152f * c, 0.0722f * c, 0f, 0f)
    return ColorFilter.colorMatrix(ColorMatrix(row(t.red) + row(t.green) + row(t.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f)))
}
