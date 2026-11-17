package net.prototux.aare.ui.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsNavigationTest {
    private fun entry(pkg: String, kind: CarAppKind) = CarAppEntry("id", pkg, "label", null, kind)

    @Test
    fun aCarNavigationAppFillsTheMapCard() {
        assertTrue(entry("de.blitzer.pro", CarAppKind.NAVIGATION).isNavigation)
    }

    @Test
    fun googleMapsAsAPhoneAppFillsTheMapCard() {
        assertTrue(entry("com.google.android.apps.maps", CarAppKind.STANDALONE).isNavigation)
    }

    @Test
    fun otherPhoneAppsDoNot() {
        assertFalse(entry("com.anthropic.claude", CarAppKind.STANDALONE).isNavigation)
        assertFalse(entry("com.google.android.apps.maps", CarAppKind.MEDIA).isNavigation)
    }
}
