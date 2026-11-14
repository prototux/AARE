package net.prototux.aare.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.R

/**
 * Retro sci-fi: a green phosphor screen or the first digital dashboards. One colour on black
 * (green by default; amber, or a vacuum fluorescent display's cyan, from the settings), a fixed-width
 * font, a soft glow around text, square corners, cards drawn as outlines, and app icons in the
 * phosphor colour. The font is the phone's own monospace one: a segment-display font would be a
 * new bundled asset.
 */
val RetroTheme: CarThemeSpec = run {
    fun TextStyle.terminal() = copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, letterSpacing = 0.5.sp)
    val typography = CarTypography.run {
        copy(
            displaySmall = displaySmall.terminal(),
            headlineMedium = headlineMedium.terminal(),
            headlineSmall = headlineSmall.terminal(),
            titleLarge = titleLarge.terminal(),
            titleMedium = titleMedium.terminal(),
            bodyLarge = bodyLarge.terminal(),
            bodyMedium = bodyMedium.terminal(),
            labelLarge = labelLarge.terminal(),
            labelMedium = labelMedium.terminal(),
        )
    }
    val square = RoundedCornerShape(2.dp)
    CarThemeSpec(
        id = "retro",
        name = "Retro",
        nameRes = R.string.theme_retro,
        // Stand-ins: tinted() below replaces them with the accent's.
        day = lightColorScheme(),
        night = darkColorScheme(),
        dayColors = AareTheme.nightColors,
        nightColors = AareTheme.nightColors,
        typography = typography,
        shapes = Shapes(extraSmall = RoundedCornerShape(0.dp), small = square, medium = square, large = square, extraLarge = square),
        appIcon = RoundedCornerShape(4.dp),
        dimens = CarDimens(cardRadius = 2.dp),
        singleColor = CarAccents.GREEN.id,
        cardBorder = 2.dp,
        glow = true,
        tintIcons = true,
    ).tinted(CarAccents.GREEN)
}
