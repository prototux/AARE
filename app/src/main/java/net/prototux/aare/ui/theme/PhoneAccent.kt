package net.prototux.aare.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.luminance

/** This theme with the phone's own colours if it takes them (Material You), else unchanged. */
fun CarThemeSpec.forPhone(context: Context): CarThemeSpec {
    if (!phoneColors || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return copy(day = dynamicLightColorScheme(context), night = dynamicDarkColorScheme(context))
}

/**
 * This theme with the phone's accent colours (Material You, from its wallpaper) instead of its own.
 * Only the accents change: the surfaces, the text colours and the call colours stay the theme's.
 * Unchanged before Android 12, which has no such colours.
 */
fun CarThemeSpec.withPhoneAccent(context: Context): CarThemeSpec {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    val dark = dynamicDarkColorScheme(context)
    val light = dynamicLightColorScheme(context)
    // By the scheme's own background, not day or night: some themes stay dark in the day.
    fun ColorScheme.accented(): ColorScheme {
        val from = if (background.luminance() < 0.5f) dark else light
        return copy(
            primary = from.primary,
            onPrimary = from.onPrimary,
            primaryContainer = from.primaryContainer,
            onPrimaryContainer = from.onPrimaryContainer,
            tertiary = from.tertiary,
            onTertiary = from.onTertiary,
        )
    }
    return copy(day = day.accented(), night = night.accented())
}
