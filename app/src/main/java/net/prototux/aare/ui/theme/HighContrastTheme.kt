package net.prototux.aare.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.prototux.aare.R

/**
 * For sun glare and weaker eyes: black on white by day, white on black by night, a bold accent
 * (yellow at night, dark blue by day), outlined cards, bigger icons and type 15 % bigger, one
 * weight heavier. Every text pair is well above the 4.5:1 the other themes must reach.
 */
val HighContrastTheme: CarThemeSpec = run {
    fun TextStyle.bigger(weight: FontWeight) = copy(fontSize = fontSize * 1.15f, lineHeight = lineHeight * 1.15f, fontWeight = weight)
    val typography = CarTypography.run {
        copy(
            displaySmall = displaySmall.bigger(FontWeight.Bold),
            headlineMedium = headlineMedium.bigger(FontWeight.Bold),
            headlineSmall = headlineSmall.bigger(FontWeight.Bold),
            titleLarge = titleLarge.bigger(FontWeight.Bold),
            titleMedium = titleMedium.bigger(FontWeight.Bold),
            bodyLarge = bodyLarge.bigger(FontWeight.Medium),
            bodyMedium = bodyMedium.bigger(FontWeight.Medium),
            labelLarge = labelLarge.bigger(FontWeight.Bold),
            labelMedium = labelMedium.bigger(FontWeight.Bold),
        )
    }
    CarThemeSpec(
        id = "high_contrast",
        name = "High contrast",
        nameRes = R.string.theme_high_contrast,
        night = darkColorScheme(
            primary = Color(0xFFFFD600),
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF3D3300),
            onPrimaryContainer = Color(0xFFFFF3B0),
            secondary = Color.White,
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF2A2A2A),
            onSecondaryContainer = Color.White,
            tertiary = Color(0xFF80D8FF),
            onTertiary = Color.Black,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color.Black,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1A1A1A),
            onSurfaceVariant = Color(0xFFE6E6E6),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0D0D0D),
            surfaceContainer = Color(0xFF141414),
            surfaceContainerHigh = Color(0xFF1F1F1F),
            surfaceContainerHighest = Color(0xFF2A2A2A),
            outline = Color.White,
            outlineVariant = Color(0xFFB3B3B3),
            error = Color(0xFFFF6B6B),
        ),
        day = lightColorScheme(
            primary = Color(0xFF0033CC),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD6E0FF),
            onPrimaryContainer = Color(0xFF001A66),
            secondary = Color.Black,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE6E6E6),
            onSecondaryContainer = Color.Black,
            tertiary = Color(0xFF6B2E00),
            onTertiary = Color.White,
            background = Color.White,
            onBackground = Color.Black,
            surface = Color.White,
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFF0F0F0),
            onSurfaceVariant = Color(0xFF1F1F1F),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF7F7F7),
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color(0xFFF0F0F0),
            surfaceContainerHighest = Color(0xFFE6E6E6),
            outline = Color.Black,
            outlineVariant = Color(0xFF4D4D4D),
            error = Color(0xFFB00020),
        ),
        nightColors = CarColors(
            accept = Color(0xFF006B1F), decline = Color(0xFFB00020), onCall = Color.White,
            red = Color(0xFFFF6B6B), green = Color(0xFF5CFF7A), blue = Color(0xFF82B1FF), yellow = Color(0xFFFFD600),
            inkOnLight = Color.Black,
        ),
        dayColors = CarColors(
            accept = Color(0xFF006B1F), decline = Color(0xFFB00020), onCall = Color.White,
            red = Color(0xFFB00020), green = Color(0xFF006B1F), blue = Color(0xFF0033CC), yellow = Color(0xFF6B4E00),
            inkOnLight = Color.Black,
        ),
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        dimens = CarDimens(cardRadius = 20.dp, iconSize = 40.dp),
        cardBorder = 2.dp,
    )
}
