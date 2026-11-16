package net.prototux.aare.ui.theme

import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** The built-in themes. A new theme is a new entry here (and in the settings' choices). */
object CarThemes {
    val all: List<CarThemeSpec> get() = listOf(AareTheme, AndroidAutoTheme, AndroidAuto2019Theme, CarPlayTheme, MaterialYouTheme, HighContrastTheme, NightTheme, RetroTheme)

    /** The themes this phone can show: Material You needs Android 12. */
    val available: List<CarThemeSpec>
        get() = all.filter { !it.phoneColors || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }

    /** The theme stored under [id], or AARE's for an unknown or removed one. */
    fun byId(id: String?): CarThemeSpec = all.firstOrNull { it.id == id } ?: AareTheme
}

/** AARE's own look: calm dark surfaces, one river-teal accent (the Aare is famously turquoise). */
val AareTheme: CarThemeSpec = run {
    val teal = Color(0xFF3DDBC6)
    val tealDeep = Color(0xFF00695C)
    CarThemeSpec(
        id = "aare",
        name = "AARE",
        night = darkColorScheme(
            primary = teal,
            onPrimary = Color(0xFF00201C),
            primaryContainer = Color(0xFF0E3B36),
            onPrimaryContainer = Color(0xFFB9F5EA),
            secondary = Color(0xFFB4CAC5),
            onSecondary = Color(0xFF1F3531),
            secondaryContainer = Color(0xFF263330),
            onSecondaryContainer = Color(0xFFD0E6E1),
            tertiary = Color(0xFFFFB86B),
            onTertiary = Color(0xFF2E1500),
            background = Color(0xFF0B0F0F),
            onBackground = Color(0xFFE3E9E7),
            surface = Color(0xFF0B0F0F),
            onSurface = Color(0xFFE3E9E7),
            surfaceVariant = Color(0xFF1A2120),
            onSurfaceVariant = Color(0xFFAFBAB7),
            surfaceContainerLowest = Color(0xFF060909),
            surfaceContainerLow = Color(0xFF121818),
            surfaceContainer = Color(0xFF161D1C),
            surfaceContainerHigh = Color(0xFF1D2524),
            surfaceContainerHighest = Color(0xFF26302E),
            outline = Color(0xFF5E6B68),
            outlineVariant = Color(0xFF2E3836),
            error = Color(0xFFFF8A80),
        ),
        day = lightColorScheme(
            primary = tealDeep,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFB9F5EA),
            onPrimaryContainer = Color(0xFF00201C),
            secondary = Color(0xFF4A635F),
            secondaryContainer = Color(0xFFCCE8E2),
            onSecondaryContainer = Color(0xFF06201C),
            tertiary = Color(0xFF8B5000),
            background = Color(0xFFF3F7F6),
            onBackground = Color(0xFF161D1C),
            surface = Color(0xFFF3F7F6),
            onSurface = Color(0xFF161D1C),
            surfaceVariant = Color(0xFFDAE5E2),
            onSurfaceVariant = Color(0xFF3F4947),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFECF2F0),
            surfaceContainer = Color(0xFFE6ECEA),
            surfaceContainerHigh = Color(0xFFE0E7E5),
            surfaceContainerHighest = Color(0xFFD9E1DF),
            outline = Color(0xFF6F7977),
            outlineVariant = Color(0xFFBEC9C6),
        ),
        nightColors = CarColors(
            accept = Color(0xFF2E7D32),
            decline = Color(0xFFD32F2F),
            onCall = Color.White,
            red = Color(0xFFFF7A70),
            green = Color(0xFF6EDB8F),
            blue = Color(0xFF82B4FF),
            yellow = Color(0xFFFFD45C),
            inkOnLight = Color(0xFF101414),
        ),
        dayColors = CarColors(
            accept = Color(0xFF2E7D32),
            decline = Color(0xFFD32F2F),
            onCall = Color.White,
            red = Color(0xFFC62828),
            green = Color(0xFF1B7F3B),
            blue = Color(0xFF1A5FBF),
            yellow = Color(0xFF8A6A00),
            inkOnLight = Color(0xFF101414),
        ),
        typography = CarTypography,
        shapes = CarShapes,
    )
}
