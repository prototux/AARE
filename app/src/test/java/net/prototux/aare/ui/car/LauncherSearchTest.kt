package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** F12: the launcher's search box filters by label as the user types. */
class LauncherSearchTest {
    private fun app(label: String) = CarAppEntry(id = label, packageName = "pkg.$label", label = label, icon = null, kind = CarAppKind.TEMPLATED)

    private val apps = listOf(app("Waze"), app("Spotify"), app("Settings"))

    @Test
    fun blankQueryReturnsEveryAppUnchanged() {
        assertSame(apps, filterLauncherApps(apps, ""))
        assertSame(apps, filterLauncherApps(apps, "   "))
    }

    @Test
    fun matchesAnywhereInTheLabelCaseInsensitively() {
        assertEquals(listOf("Spotify"), filterLauncherApps(apps, "pot").map { it.label })
        assertEquals(listOf("Settings"), filterLauncherApps(apps, "SETT").map { it.label })
    }

    @Test
    fun noMatchIsAnEmptyList() {
        assertEquals(emptyList<String>(), filterLauncherApps(apps, "zzz").map { it.label })
    }
}
