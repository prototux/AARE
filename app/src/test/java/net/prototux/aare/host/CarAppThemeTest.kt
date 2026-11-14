package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Test

class CarAppThemeTest {
    @Test
    fun theServicesCarThemeComesFirst() {
        assertEquals(1, CarAppTheme.themeRes(serviceMeta = 1, appMeta = 2, appTheme = 3))
    }

    @Test
    fun thenTheApplicationsCarTheme() {
        assertEquals(2, CarAppTheme.themeRes(serviceMeta = 0, appMeta = 2, appTheme = 3))
    }

    @Test
    fun thenTheApplicationsOwnTheme() {
        assertEquals(3, CarAppTheme.themeRes(serviceMeta = 0, appMeta = 0, appTheme = 3))
        assertEquals(0, CarAppTheme.themeRes(serviceMeta = 0, appMeta = 0, appTheme = 0))
    }
}
