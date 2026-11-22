package net.prototux.aare.host.templates

import androidx.compose.runtime.Immutable

/**
 * An app's own accent colours, from the car theme it declares: carColorPrimary/carColorPrimaryDark
 * and carColorSecondary/carColorSecondaryDark, as ARGB, day and night. CarColor.PRIMARY and
 * SECONDARY in that app's templates resolve to them. A pair the theme doesn't set ([primary] or
 * [secondary] null) keeps the car theme's own accent colours, so an app without a car theme looks
 * like the rest of the car screen.
 */
@Immutable
data class AppThemeColors(val primary: DayNight? = null, val secondary: DayNight? = null) {
    data class DayNight(val day: Int, val night: Int) {
        fun pick(night: Boolean): Int = if (night) this.night else day
    }

    companion object {
        val NONE = AppThemeColors()

        /**
         * A pair counts only when the theme sets both colours: with just one of them (or a
         * transparent one, which is what an unset attribute reads as) the whole pair is the default.
         */
        fun pair(day: Int, night: Int): DayNight? = if (day != 0 && night != 0) DayNight(day, night) else null
    }
}
