package net.prototux.aare.ui.theme

import androidx.compose.ui.graphics.Color
import net.prototux.aare.ui.car.styleSwatch
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassThemeTest {
    /** Alerts, calls and the assistant bar can sit over a live map: the map mustn't show through. */
    @Test fun cardsOverTheMapAreOpaque() {
        for (theme in listOf(FrostedGlassTheme, DarkGlassTheme)) for (scheme in listOf(theme.day, theme.night)) {
            assertEquals(theme.id, 1f, scheme.surfaceContainerHighest.alpha)
        }
    }

    /** Over a map (OverMap), every translucent surface and container is solid; the floating rail stays transparent. */
    @Test fun overAMapEveryContainerIsSolid() {
        for (theme in listOf(FrostedGlassTheme, DarkGlassTheme)) for ((scheme, backdrop) in listOf(theme.day to theme.dayBackdrop, theme.night to theme.nightBackdrop)) {
            val s = scheme.opaqueOver(backdrop.first())
            for (c in listOf(s.primaryContainer, s.secondaryContainer, s.surface, s.surfaceVariant, s.surfaceContainerLow,
                s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest)) assertEquals(theme.id, 1f, c.alpha)
            assertEquals(Color.Transparent, s.surfaceContainerLowest)
        }
    }

    /** The style list's dot shows a glass theme's backdrop, not its transparent background. */
    @Test fun aGlassThemesDotHasItsBackdropColor() {
        assertEquals(1f, styleSwatch(FrostedGlassTheme.id).first().alpha)
        assertEquals(1f, styleSwatch(DarkGlassTheme.id).first().alpha)
    }
}
