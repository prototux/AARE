package net.prototux.aare.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import net.prototux.aare.R

/** An accent's colours on dark surfaces or on light ones. */
@Immutable
data class AccentTones(val primary: Color, val onPrimary: Color, val container: Color, val onContainer: Color)

/**
 * An accent colour from the settings. On most themes it replaces the theme's accent; on a
 * single-colour theme (Night, Retro) it is the colour of everything.
 */
@Immutable
data class CarAccent(
    /** Stored in the settings: never change it once released. */
    val id: String,
    @StringRes val nameRes: Int,
    val dark: AccentTones,
    val light: AccentTones,
)

object CarAccents {
    /** The theme's own accent (or its own colour, for a single-colour theme). */
    const val THEME = "theme"
    /** The phone's accent (Material You, Android 12 and later). */
    const val PHONE = "phone"

    val AMBER = CarAccent(
        "amber", R.string.accent_amber, // an amber monitor's phosphor, and many cars' night lighting
        dark = AccentTones(Color(0xFFFFB000), Color(0xFF2A1A00), Color(0xFF4A3200), Color(0xFFFFDEA6)),
        light = AccentTones(Color(0xFF8A5A00), Color.White, Color(0xFFFFDEA6), Color(0xFF2A1A00)),
    )
    val RED = CarAccent(
        "red", R.string.accent_red, // night lighting that keeps the eyes adapted to the dark
        dark = AccentTones(Color(0xFFFF5A4E), Color(0xFF2D0300), Color(0xFF5C1410), Color(0xFFFFDAD5)),
        light = AccentTones(Color(0xFFB3261E), Color.White, Color(0xFFFFDAD5), Color(0xFF410E0B)),
    )
    val GREEN = CarAccent(
        "green", R.string.accent_green, // a green CRT's phosphor, and the green of early digital dashboards
        dark = AccentTones(Color(0xFF33FF66), Color(0xFF002910), Color(0xFF0F3D1E), Color(0xFFB8FFC8)),
        light = AccentTones(Color(0xFF1B7A3A), Color.White, Color(0xFFB8F5C8), Color(0xFF002910)),
    )
    val CYAN = CarAccent(
        "cyan", R.string.accent_cyan, // a vacuum fluorescent display's blue-green
        dark = AccentTones(Color(0xFF2EF2D0), Color(0xFF00201B), Color(0xFF0B3D37), Color(0xFFB3FFF2)),
        light = AccentTones(Color(0xFF00695C), Color.White, Color(0xFFB3F0E6), Color(0xFF00201B)),
    )
    val BLUE = CarAccent(
        "blue", R.string.accent_blue,
        dark = AccentTones(Color(0xFF8AB4F8), Color(0xFF062E6F), Color(0xFF1F3A5F), Color(0xFFD3E3FD)),
        light = AccentTones(Color(0xFF1A5FBF), Color.White, Color(0xFFD3E3FD), Color(0xFF0A1F44)),
    )
    val WHITE = CarAccent(
        "white", R.string.accent_white,
        dark = AccentTones(Color(0xFFE8E8E8), Color(0xFF1A1A1A), Color(0xFF3A3A3A), Color(0xFFF5F5F5)),
        light = AccentTones(Color(0xFF3A3A3A), Color.White, Color(0xFFE0E0E0), Color(0xFF111111)),
    )

    val all: List<CarAccent> = listOf(AMBER, RED, GREEN, CYAN, BLUE, WHITE)

    fun byId(id: String?): CarAccent? = all.firstOrNull { it.id == id }
}

/**
 * This theme with [accent] (null: the theme's own). A single-colour theme is recoloured as a whole
 * ([tinted]); any other keeps its surfaces and text and swaps its accent, in the tones that suit
 * each scheme's background (some themes stay dark in the day).
 */
fun CarThemeSpec.withAccent(accent: CarAccent?): CarThemeSpec {
    singleColor?.let { own -> return tinted(accent ?: CarAccents.byId(own) ?: CarAccents.AMBER) }
    if (accent == null) return this
    fun ColorScheme.accented(): ColorScheme {
        val t = if (background.luminance() < 0.5f) accent.dark else accent.light
        return copy(primary = t.primary, onPrimary = t.onPrimary, primaryContainer = t.container, onPrimaryContainer = t.onContainer)
    }
    return copy(day = day.accented(), night = night.accented())
}
