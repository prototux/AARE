package net.prototux.aare.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The look of Apple's CarPlay. Not affiliated with Apple.
 *
 * The colours are iOS's documented system colours (systemBackground, secondarySystemBackground,
 * systemGray4-5, systemBlue, separators…), dark at night and light in the day. Where white on
 * Apple's green or a label grey falls short of the contrast every theme must reach, Apple's
 * "increased contrast" variant or a darker tone is used instead (marked "darker"). Apple publishes
 * no CarPlay type scale or corner radii: San Francisco can't be bundled, so the system font with
 * Apple's semibold titles, and corners estimated from Apple's screenshots.
 */
val CarPlayTheme: CarThemeSpec = run {
    val typography = CarTypography.run {
        copy(
            displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold),
            headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
    CarThemeSpec(
        id = "carplay",
        name = "CarPlay",
        night = darkColorScheme(
            primary = Color(0xFF0A84FF), // systemBlue
            onPrimary = Color.White,
            primaryContainer = Color(0xFF0B3D72),
            onPrimaryContainer = Color.White,
            secondary = Color(0xFF8E8E93), // systemGray
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF2C2C2E), // systemGray5
            onSecondaryContainer = Color.White,
            tertiary = Color(0xFFFF9F0A), // systemOrange
            onTertiary = Color.Black,
            background = Color.Black, // systemBackground
            onBackground = Color.White,
            surface = Color(0xFF1C1C1E), // secondarySystemBackground
            onSurface = Color.White,
            surfaceVariant = Color(0xFF2C2C2E),
            onSurfaceVariant = Color(0xFFAEAEB2), // darker secondaryLabel would fall short
            surfaceContainerLowest = Color(0xFF0E0E0F),
            surfaceContainerLow = Color(0xFF111113),
            surfaceContainer = Color(0xFF1C1C1E),
            surfaceContainerHigh = Color(0xFF2C2C2E),
            surfaceContainerHighest = Color(0xFF3A3A3C), // systemGray4
            outline = Color(0xFF545458),
            outlineVariant = Color(0xFF38383A), // opaqueSeparator
            error = Color(0xFFFF453A), // systemRed
        ),
        day = lightColorScheme(
            primary = Color(0xFF007AFF),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD6E8FF),
            onPrimaryContainer = Color(0xFF003A80),
            secondary = Color(0xFF8E8E93),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE5E5EA), // systemGray5
            onSecondaryContainer = Color.Black,
            tertiary = Color(0xFFC93400), // systemOrange, increased contrast
            onTertiary = Color.White,
            background = Color(0xFFF2F2F7), // systemGroupedBackground
            onBackground = Color.Black,
            surface = Color.White,
            onSurface = Color.Black,
            surfaceVariant = Color(0xFFE5E5EA),
            onSurfaceVariant = Color(0xFF545458), // darker secondaryLabel
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF2F2F7),
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color(0xFFE5E5EA),
            surfaceContainerHighest = Color(0xFFD1D1D6), // systemGray4
            outline = Color(0xFFC6C6C8), // opaqueSeparator
            outlineVariant = Color(0xFFE5E5EA),
            error = Color(0xFFD70015),
        ),
        nightColors = CarColors(
            accept = Color(0xFF1F7A35), // darker systemGreen, for white text
            decline = Color(0xFFD70015), // systemRed, increased contrast
            onCall = Color.White,
            red = Color(0xFFFF453A),
            green = Color(0xFF30D158),
            blue = Color(0xFF0A84FF),
            yellow = Color(0xFFFFD60A),
            inkOnLight = Color.Black,
        ),
        dayColors = CarColors(
            accept = Color(0xFF1F7A35),
            decline = Color(0xFFD70015),
            onCall = Color.White,
            // The increased-contrast variants: the default ones are too pale on white.
            red = Color(0xFFD70015),
            green = Color(0xFF248A3D),
            blue = Color(0xFF0040DD),
            yellow = Color(0xFFA05A00),
            inkOnLight = Color.Black,
        ),
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        appIcon = RoundedCornerShape(22), // the rounded square of iOS icons
        dimens = CarDimens(cardRadius = 22.dp),
    )
}
