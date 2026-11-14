package net.prototux.aare.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The look of Android Auto's current design (2023 on). Not affiliated with Google.
 *
 * Documented by Google ("Design for Driving", developers.google.com/cars/design/android-auto/design-system):
 * a true black background in day and night, white text at 88 % opacity in the day and 96 % at night
 * (72 % for secondary text), Roboto below 32 dp (Google Sans above it, which can't be bundled),
 * medium weight and never bold, 4.5:1 contrast. Google publishes no values for the grey surfaces
 * or the accent blue: those are measured from Google's own renders or are Google's usual
 * dark-theme tones (estimated), as are the corners.
 */
val AndroidAutoTheme: CarThemeSpec = run {
    fun scheme(text: Color) = darkColorScheme(
        primary = Color(0xFF8AB4F8), // estimated: Google blue for dark surfaces
        onPrimary = Color(0xFF062E6F),
        primaryContainer = Color(0xFF1F3A5F),
        onPrimaryContainer = Color(0xFFD3E3FD),
        secondary = Color(0xFFBDC1C6),
        onSecondary = Color(0xFF202124),
        secondaryContainer = Color(0xFF3C4043),
        onSecondaryContainer = Color(0xFFE8EAED),
        tertiary = Color(0xFFFDD663),
        onTertiary = Color(0xFF3C2F00),
        background = Color.Black, // documented
        onBackground = text,
        surface = Color.Black,
        onSurface = text,
        surfaceVariant = Color(0xFF303134),
        onSurfaceVariant = Color(0xFFB8B8B8), // documented: white at 72 %
        surfaceContainerLowest = Color.Black, // the taskbar sits on the background
        surfaceContainerLow = Color(0xFF202124),
        surfaceContainer = Color(0xFF303134), // measured: the dashboard cards
        surfaceContainerHigh = Color(0xFF35363A),
        surfaceContainerHighest = Color(0xFF3C4043),
        outline = Color(0xFF5F6368),
        outlineVariant = Color(0xFF3C4043),
        error = Color(0xFFF28B82),
    )
    val colors = CarColors(
        accept = Color(0xFF188038),
        decline = Color(0xFFD93025), // documented: red hang-up buttons
        onCall = Color.White,
        red = Color(0xFFF28B82),
        green = Color(0xFF81C995),
        blue = Color(0xFF8AB4F8),
        yellow = Color(0xFFFDD663),
        inkOnLight = Color(0xFF202124),
    )
    CarThemeSpec(
        id = "android_auto",
        name = "Android Auto",
        day = scheme(text = Color(0xFFE0E0E0)), // documented: white at 88 %
        night = scheme(text = Color(0xFFF5F5F5)), // documented: white at 96 %
        dayColors = colors,
        nightColors = colors,
        typography = CarTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        dimens = CarDimens(cardRadius = 24.dp),
    )
}
