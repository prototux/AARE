package net.prototux.aare.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp

/**
 * Material You: the phone's own colours, from its wallpaper, for every surface and accent, light in
 * the day and dark at night ([forPhone] sets them when the theme is applied). Material 3's corner
 * scale. Android 12 and later; the colours here are Material 3's baseline, the stand-in for tests
 * and older phones (which don't offer the theme).
 */
val MaterialYouTheme: CarThemeSpec = CarThemeSpec(
    id = "material_you",
    name = "Material You",
    day = lightColorScheme(),
    night = darkColorScheme(),
    // Material You has no call or status colours: Google's, as in the Android Auto theme by night.
    dayColors = AareTheme.dayColors,
    nightColors = AndroidAutoTheme.nightColors,
    typography = CarTypography,
    shapes = Shapes(),
    dimens = CarDimens(cardRadius = 28.dp),
    phoneColors = true,
)
