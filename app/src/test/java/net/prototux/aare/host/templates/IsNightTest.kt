package net.prototux.aare.host.templates

import androidx.compose.ui.graphics.luminance
import net.prototux.aare.ui.theme.CarThemes
import net.prototux.aare.ui.theme.DarkGlassTheme
import net.prototux.aare.ui.theme.FrostedGlassTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsNightTest {
    /** Themes with a solid background: night exactly where the background is dark, as before. */
    @Test fun aSolidBackgroundDecidesAsBefore() {
        for (theme in CarThemes.all) for (scheme in listOf(theme.day, theme.night)) {
            if (scheme.background.alpha < 1f) continue
            assertEquals(theme.id, scheme.background.luminance() < 0.5f, scheme.isNight)
        }
    }

    /** Glass themes leave the background transparent: their day is still day. */
    @Test fun glassThemesByDayAreDay() {
        assertFalse(FrostedGlassTheme.day.isNight)
        assertTrue(FrostedGlassTheme.night.isNight)
        assertTrue(DarkGlassTheme.day.isNight)
    }
}
