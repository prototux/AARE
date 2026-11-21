package net.prototux.aare.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.prototux.aare.R

/**
 * Frosted glass: cards and bars of translucent white over a soft color gradient, with a fine light
 * edge, as glass dashboards do. Compose can't blur what lies behind a card, so the gradient is
 * already smooth and the cards only lighten it. Every translucent color is checked for contrast
 * over each color of the gradient.
 */
val FrostedGlassTheme: CarThemeSpec = run {
    fun white(alpha: Float) = Color.White.copy(alpha = alpha)
    CarThemeSpec(
        id = "frosted_glass",
        name = "Frosted glass",
        nameRes = R.string.theme_frosted_glass,
        nightBackdrop = listOf(Color(0xFF16233F), Color(0xFF2E1A47), Color(0xFF0E3440)),
        dayBackdrop = listOf(Color(0xFFC9D8F0), Color(0xFFE6CFE3), Color(0xFFC6E6E2)),
        night = darkColorScheme(
            primary = Color(0xFF8AD4FF),
            onPrimary = Color(0xFF00243A),
            primaryContainer = white(0.22f),
            onPrimaryContainer = Color.White,
            secondary = Color(0xFFC7CEDD),
            onSecondary = Color(0xFF1A2030),
            secondaryContainer = white(0.18f),
            onSecondaryContainer = Color.White,
            tertiary = Color(0xFFFFC38A),
            onTertiary = Color(0xFF3A1D00),
            background = Color.Transparent,
            onBackground = Color.White,
            surface = white(0.10f),
            onSurface = Color.White,
            surfaceVariant = white(0.14f),
            onSurfaceVariant = Color(0xFFDDE2EE),
            surfaceContainerLowest = Color.Transparent, // the rail: icons float on the backdrop
            surfaceContainerLow = white(0.08f),
            surfaceContainer = white(0.10f),
            surfaceContainerHigh = white(0.16f),
            surfaceContainerHighest = white(0.20f),
            outline = white(0.30f),
            outlineVariant = white(0.16f),
            error = Color(0xFFFF8A80),
        ),
        day = lightColorScheme(
            primary = Color(0xFF1D4ED8),
            onPrimary = Color.White,
            primaryContainer = white(0.70f),
            onPrimaryContainer = Color(0xFF0B1F4D),
            secondary = Color(0xFF475569),
            onSecondary = Color.White,
            secondaryContainer = white(0.60f),
            onSecondaryContainer = Color(0xFF0F172A),
            tertiary = Color(0xFF9A3412),
            onTertiary = Color.White,
            background = Color.Transparent,
            onBackground = Color(0xFF0F172A),
            surface = white(0.45f),
            onSurface = Color(0xFF0F172A),
            surfaceVariant = white(0.55f),
            onSurfaceVariant = Color(0xFF334155),
            surfaceContainerLowest = Color.Transparent,
            surfaceContainerLow = white(0.40f),
            surfaceContainer = white(0.50f),
            surfaceContainerHigh = white(0.60f),
            surfaceContainerHighest = white(0.70f),
            outline = white(0.85f),
            outlineVariant = white(0.60f),
            error = Color(0xFFB91C1C),
        ),
        nightColors = CarColors(
            accept = Color(0xFF1F7A35),
            decline = Color(0xFFC62828),
            onCall = Color.White,
            red = Color(0xFFFF8A80),
            green = Color(0xFF7EE2A0),
            blue = Color(0xFF8AD4FF),
            yellow = Color(0xFFFFE082),
            inkOnLight = Color.Black,
        ),
        dayColors = CarColors(
            accept = Color(0xFF1F7A35),
            decline = Color(0xFFC62828),
            onCall = Color.White,
            red = Color(0xFFB91C1C),
            green = Color(0xFF166534),
            blue = Color(0xFF1D4ED8),
            yellow = Color(0xFF854D0E),
            inkOnLight = Color.Black,
        ),
        typography = CarTypography,
        shapes = CarShapes,
        cardBorder = 1.dp,
    )
}
