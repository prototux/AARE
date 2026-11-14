package net.prototux.aare.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Car-screen sizes. Car screens are viewed from ~70 cm at a glance, so sizes follow the car-UI rule
 * of thumb: touch targets ≥ 76 dp, body text ≥ 24 sp, and no more than two text styles per card.
 */
@Immutable
data class CarDimens(
    val touchTarget: Dp = 76.dp,
    val railWidth: Dp = 96.dp,
    val gutter: Dp = 12.dp,
    val cardRadius: Dp = 28.dp,
    val iconSize: Dp = 36.dp,
)

val LocalCarDimens = staticCompositionLocalOf { CarDimens() }

/** The colours a theme needs that Material's [ColorScheme] has no slot for, for one of day or night. */
@Immutable
data class CarColors(
    /** Answering a call. */
    val accept: Color,
    /** Declining or hanging up a call. */
    val decline: Color,
    /** Icons and text on [accept] and [decline]. */
    val onCall: Color,
    /** CarColor's standard colours, which apps use for status text, icons and buttons. */
    val red: Color,
    val green: Color,
    val blue: Color,
    val yellow: Color,
    /** Text on a light background an app supplied (white text goes on dark ones). */
    val inkOnLight: Color,
)

val LocalCarColors = staticCompositionLocalOf { AareTheme.nightColors }

/**
 * A car-screen theme: what things look like (colours, type, shapes, sizes), not where they go;
 * that is the layout's job (see `CarLayout`). Every value is plain data, so a theme could later be
 * read from a file; for now a new theme is a new entry in [CarThemes].
 */
@Immutable
data class CarThemeSpec(
    /** Stored in the settings: never change it once released. */
    val id: String,
    /** Shown in the settings, not translated: a name like "AARE" or "CarPlay". */
    val name: String,
    val day: ColorScheme,
    val night: ColorScheme,
    val dayColors: CarColors,
    val nightColors: CarColors,
    val typography: Typography,
    val shapes: Shapes,
    /** App icons: round on Android, rounded squares on iOS. */
    val appIcon: Shape = CircleShape,
    val dimens: CarDimens = CarDimens(),
)

val LocalCarTheme = staticCompositionLocalOf { AareTheme }

/**
 * Applies [theme] in its day or night colours. [theme] defaults to the one already applied, so a
 * part of the screen can switch to night colours (the "dark bar") without knowing which theme it is.
 */
@Composable
fun CarTheme(night: Boolean, theme: CarThemeSpec = LocalCarTheme.current, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (night) theme.night else theme.day,
        typography = theme.typography,
        shapes = theme.shapes,
    ) {
        CompositionLocalProvider(
            LocalCarTheme provides theme,
            LocalCarColors provides if (night) theme.nightColors else theme.dayColors,
            LocalCarDimens provides theme.dimens,
            content = content,
        )
    }
}

/** Phone UI theme: AARE's palette, normal phone type scale. */
@Composable
fun PhoneTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) AareTheme.night else AareTheme.day, content = content)
}

/** The car type scale every theme starts from: big enough to read at a glance. */
internal val CarTypography = Typography(
    displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Medium),
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
    headlineSmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp),
    bodyMedium = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    labelLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
)

/**
 * AARE's corners. The car UI uses the scale by role: small for album art, thumbnails and keys,
 * medium for buttons and list rows, large for cards and banners, extraLarge for chips and sheets.
 */
internal val CarShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
