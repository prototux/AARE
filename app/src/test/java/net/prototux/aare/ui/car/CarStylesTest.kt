package net.prototux.aare.ui.car

import net.prototux.aare.ui.theme.CarThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarStylesTest {
    @Test
    fun unknownLayoutFallsBackToTheTaskbar() {
        assertEquals(CarLayout.TASKBAR, CarLayout.byId("gone"))
        assertEquals(CarLayout.TASKBAR, CarLayout.byId(null))
        assertEquals(CarLayout.DOCK, CarLayout.byId("dock"))
    }

    @Test
    fun aStyleIsItsThemeWithItsLayout() {
        CarStyles.all.forEach { assertEquals(it, CarStyles.matching(it.themeId, it.layout)) }
    }

    @Test
    fun aThemeOnAnotherLayoutIsCustom() {
        assertNull(CarStyles.matching("aare", CarLayout.DOCK))
    }

    @Test
    fun everyStyleNamesAnExistingTheme() {
        CarStyles.all.forEach { assertEquals(it.themeId, CarThemes.byId(it.themeId).id) }
    }
}
