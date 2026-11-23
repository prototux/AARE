package net.prototux.aare.ui.car

import net.prototux.aare.ui.theme.CarAccents
import net.prototux.aare.ui.theme.CarThemes
import org.junit.Assert.assertEquals
import org.junit.Test

class StyleSwatchTest {
    @Test
    fun everyStyleAndThemeHasItsColorsAndEveryAccentIts() {
        CarStyles.all.forEach {
            val night = CarThemes.byId(it.themeId).night
            assertEquals(listOf(night.background, night.primary), styleSwatch("${it.themeId}/${it.layout.id}"))
            assertEquals(listOf(night.background, night.primary), styleSwatch(it.themeId))
        }
        CarAccents.all.forEach { assertEquals(listOf(it.dark.primary), styleSwatch(it.id)) }
    }

    @Test
    fun aMixAndAnAccentFromElsewhereHaveNone() {
        assertEquals(emptyList<Any>(), styleSwatch("custom"))
        assertEquals(emptyList<Any>(), styleSwatch(CarAccents.THEME))
        assertEquals(emptyList<Any>(), styleSwatch(CarAccents.PHONE))
    }
}
