package net.prototux.aare.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import net.prototux.aare.R

/** This theme with the phone's own colours if it takes them (Material You), else unchanged. */
fun CarThemeSpec.forPhone(context: Context): CarThemeSpec {
    if (!phoneColors || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return copy(day = dynamicLightColorScheme(context), night = dynamicDarkColorScheme(context))
}

/** The phone's accent (Material You), or null before Android 12. */
fun CarAccents.fromPhone(context: Context): CarAccent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val dark = dynamicDarkColorScheme(context)
    val light = dynamicLightColorScheme(context)
    return CarAccent(
        PHONE, R.string.accent_phone,
        dark = AccentTones(dark.primary, dark.onPrimary, dark.primaryContainer, dark.onPrimaryContainer),
        light = AccentTones(light.primary, light.onPrimary, light.primaryContainer, light.onPrimaryContainer),
    )
}

/** This theme with the accent the settings store under [id] ([CarAccents.THEME], [CarAccents.PHONE] or a colour's). */
fun CarThemeSpec.withAccent(id: String, context: Context): CarThemeSpec = withAccent(
    when (id) {
        CarAccents.THEME -> null
        CarAccents.PHONE -> CarAccents.fromPhone(context)
        else -> CarAccents.byId(id)
    },
)
