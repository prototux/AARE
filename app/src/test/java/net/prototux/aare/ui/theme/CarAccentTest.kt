package net.prototux.aare.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CarAccentTest {
    @Test
    fun theThemesOwnAccentLeavesItAlone() {
        assertSame(AareTheme, AareTheme.withAccent(null))
    }

    @Test
    fun anAccentReplacesOnlyTheAccent() {
        val red = AareTheme.withAccent(CarAccents.RED)
        assertEquals(CarAccents.RED.dark.primary, red.night.primary)
        assertEquals(CarAccents.RED.light.primary, red.day.primary)
        assertEquals(AareTheme.night.surfaceContainer, red.night.surfaceContainer)
    }

    /** Android Auto stays dark in the day: its day scheme takes the accent's tones for dark surfaces. */
    @Test
    fun anAlwaysDarkThemeTakesTheDarkTonesByDay() {
        assertEquals(CarAccents.RED.dark.primary, AndroidAutoTheme.withAccent(CarAccents.RED).day.primary)
    }

    @Test
    fun aSingleColorThemeIsRecolouredWhole() {
        val green = NightTheme.withAccent(CarAccents.GREEN)
        assertEquals(CarAccents.GREEN.dark.primary, green.day.primary)
        assertEquals(CarAccents.GREEN.dark.onContainer, green.day.onSurface)
    }

    @Test
    fun aSingleColorThemeDefaultsToItsOwnColour() {
        assertEquals(CarAccents.AMBER.dark.primary, NightTheme.withAccent(null).day.primary)
        assertEquals(CarAccents.GREEN.dark.primary, RetroTheme.withAccent(null).day.primary)
    }

    @Test
    fun unknownAccentIsNone() {
        assertNull(CarAccents.byId("gone"))
    }
}
