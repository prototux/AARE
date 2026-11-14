package net.prototux.aare.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every built-in theme must stay readable at a glance in the car, in day and in night colours:
 * WCAG AA contrast (4.5:1) for text, 3:1 for icons and the colours apps use for status.
 */
class CarThemeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun failures(theme: CarThemeSpec): List<String> = buildList {
        fun check(mode: String, what: String, fg: Color, bg: Color, min: Double) {
            val c = contrast(fg, bg)
            if (c < min) add("${theme.id} $mode: $what %.2f < $min".format(c))
        }
        fun scheme(mode: String, s: ColorScheme, x: CarColors) {
            for ((name, bg) in listOf("background" to s.background, "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHighest" to s.surfaceContainerHighest)) {
                check(mode, "onSurface on $name", s.onSurface, bg, 4.5)
                check(mode, "onSurfaceVariant on $name", s.onSurfaceVariant, bg, 4.5)
            }
            check(mode, "onPrimary on primary", s.onPrimary, s.primary, 4.5)
            check(mode, "onPrimaryContainer on primaryContainer", s.onPrimaryContainer, s.primaryContainer, 4.5)
            check(mode, "onSecondaryContainer on secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 4.5)
            check(mode, "primary on surfaceContainer", s.primary, s.surfaceContainer, 3.0)
            check(mode, "onCall on accept", x.onCall, x.accept, 4.5)
            check(mode, "onCall on decline", x.onCall, x.decline, 4.5)
            for ((name, c) in listOf("red" to x.red, "green" to x.green, "blue" to x.blue, "yellow" to x.yellow)) {
                check(mode, "$name on background", c, s.background, 3.0)
            }
        }
        scheme("day", theme.day, theme.dayColors)
        scheme("night", theme.night, theme.nightColors)
    }

    @Test
    fun everyThemeIsReadableDayAndNight() {
        val all = CarThemes.all.flatMap(::failures)
        assertTrue(all.joinToString("\n"), all.isEmpty())
    }

    @Test
    fun themeIdsAreUnique() {
        val ids = CarThemes.all.map { it.id }
        assertEquals(ids, ids.distinct())
    }

    @Test
    fun unknownIdFallsBackToAare() {
        assertEquals(AareTheme, CarThemes.byId("gone"))
        assertEquals(AareTheme, CarThemes.byId(null))
    }
}
