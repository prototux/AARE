package net.prototux.aare.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import net.prototux.aare.R

/**
 * One colour on black, like many cars' night lighting: amber by default, or red (which keeps the
 * eyes used to the dark), green or any accent from the settings. App icons take the colour too.
 * Only AARE's own screens change: a navigation app still draws its map in its own colours.
 */
val NightTheme: CarThemeSpec = CarThemeSpec(
    id = "night",
    name = "Night",
    nameRes = R.string.theme_night,
    // Stand-ins: tinted() below replaces them with the accent's.
    day = lightColorScheme(),
    night = darkColorScheme(),
    dayColors = AareTheme.nightColors,
    nightColors = AareTheme.nightColors,
    typography = CarTypography,
    shapes = CarShapes,
    singleColor = CarAccents.AMBER.id,
    tintIcons = true,
).tinted(CarAccents.AMBER)
