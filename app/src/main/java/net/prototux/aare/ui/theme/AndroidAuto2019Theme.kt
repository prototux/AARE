package net.prototux.aare.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The look of Android Auto from 2019 to 2022, before the current design. Not affiliated with Google.
 *
 * Dark only, as it was: no day colours existed. Google published no values; the greys, the call
 * buttons' green and red are measured from the screenshots in Google's 2019 announcement
 * (blog.google, "Android Auto: a new look"), the accent is Google's usual blue for dark surfaces,
 * and the corners are estimated. The secondary text is a little lighter than measured, to reach
 * the contrast every theme must, and the green a little darker, for white text.
 */
val AndroidAuto2019Theme: CarThemeSpec = run {
    val scheme = darkColorScheme(
        primary = Color(0xFF8AB4F8),
        onPrimary = Color(0xFF062E6F),
        primaryContainer = Color(0xFF1F3A5F),
        onPrimaryContainer = Color(0xFFD3E3FD),
        secondary = Color(0xFFBDC1C6),
        onSecondary = Color(0xFF202124),
        secondaryContainer = Color(0xFF3C4043),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFF81C995),
        onTertiary = Color(0xFF0D2E18),
        background = Color.Black,
        onBackground = Color.White,
        surface = Color.Black,
        onSurface = Color.White,
        surfaceVariant = Color(0xFF202124),
        onSurfaceVariant = Color(0xFFBDC1C6),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF18191C),
        surfaceContainer = Color(0xFF202124), // measured: notification and call cards
        surfaceContainerHigh = Color(0xFF292A2E), // measured: the bottom bar
        surfaceContainerHighest = Color(0xFF3C4043), // measured: the pill in the bar
        outline = Color(0xFF5F6368),
        outlineVariant = Color(0xFF3C4043),
        error = Color(0xFFF28B82),
    )
    val colors = CarColors(
        accept = Color(0xFF188038), // measured 0xFF1B8543, darkened
        decline = Color(0xFFD33834), // measured
        onCall = Color.White,
        red = Color(0xFFF28B82),
        green = Color(0xFF81C995),
        blue = Color(0xFF8AB4F8),
        yellow = Color(0xFFFDD663),
        inkOnLight = Color(0xFF202124),
    )
    CarThemeSpec(
        id = "android_auto_2019",
        name = "Android Auto (2019)",
        day = scheme,
        night = scheme,
        dayColors = colors,
        nightColors = colors,
        typography = CarTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        dimens = CarDimens(cardRadius = 16.dp),
    )
}
