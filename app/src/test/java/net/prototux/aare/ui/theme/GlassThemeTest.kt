package net.prototux.aare.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassThemeTest {
    /** Alerts, calls, the assistant bar and the keyboard sit over a live map: the map mustn't show through. */
    @Test fun cardsOverTheMapAreOpaque() {
        for (theme in listOf(FrostedGlassTheme, DarkGlassTheme)) for (scheme in listOf(theme.day, theme.night)) {
            assertEquals(theme.id, 1f, scheme.surfaceContainerHighest.alpha)
        }
    }
}
