package net.prototux.aare.ui.theme

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.R

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
    /** Shown in the settings: a name like "AARE" or "CarPlay", or [nameRes] if set (a word to translate). */
    val name: String,
    @StringRes val nameRes: Int = 0,
    val day: ColorScheme,
    val night: ColorScheme,
    val dayColors: CarColors,
    val nightColors: CarColors,
    val typography: Typography,
    val shapes: Shapes,
    /** App icons: round on Android, rounded squares on iOS. */
    val appIcon: Shape = CircleShape,
    val dimens: CarDimens = CarDimens(),
    /**
     * The colours are the phone's own (Material You, Android 12 and later), set by [forPhone]; [day]
     * and [night] are only the stand-in for older phones.
     */
    val phoneColors: Boolean = false,
    /**
     * A single-colour theme: everything in one accent colour, by default the accent with this id
     * (`CarAccents`), or the one the settings pick. [day] and [night] are then rebuilt by `tinted`.
     */
    val singleColor: String? = null,
    /** An outline around the dashboard cards, in the outline colour (0: none). */
    val cardBorder: Dp = 0.dp,
    /** A phosphor-like halo around text, in the accent colour (single-colour themes). */
    val glow: Boolean = false,
    /** App icons drawn in the accent colour instead of their own (single-colour themes). */
    val tintIcons: Boolean = false,
    /** The clock on a seven-segment display ([SegmentFont]), its unlit segments faintly behind. */
    val segmentClock: Boolean = false,
)

/**
 * DSEG7 Classic, a seven-segment display font (SIL Open Font License 1.1, bundled with its licence
 * in assets/licenses). Digits and a few capitals only: for the clock, never for running text.
 * Its "!" is a blank as wide as a digit, to line up with the unlit "88:88".
 */
val SegmentFont = FontFamily(Font(R.font.dseg7_classic_regular))

/** [CarThemeSpec.name], translated when the name is a word. */
@Composable
fun CarThemeSpec.label(): String = if (nameRes != 0) stringResource(nameRes) else name

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
