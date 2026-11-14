package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Test

class RailAppsTest {
    private val self = "net.prototux.aare"

    private fun entry(id: String, pkg: String) = CarAppEntry(id, pkg, id, null, CarAppKind.TEMPLATED)

    @Test
    fun emptyKeysMeanNoCustomRail() {
        val apps = listOf(entry("maps", "com.maps"))
        assertEquals(emptyList<CarAppEntry>(), resolveRailApps(emptyList(), apps, self))
    }

    @Test
    fun resolvesInTheUsersOrderNotTheAppsOrder() {
        val maps = entry("maps", "com.maps")
        val music = entry("music", "com.music")
        val apps = listOf(music, maps)
        assertEquals(listOf(maps, music), resolveRailApps(listOf("com.maps", "com.music"), apps, self))
    }

    @Test
    fun skipsAppsNoLongerInstalled() {
        val maps = entry("maps", "com.maps")
        val apps = listOf(maps)
        assertEquals(listOf(maps), resolveRailApps(listOf("com.maps", "com.gone", "phone"), apps, self))
    }

    @Test
    fun builtInAppsKeyOnTheirIdNotTheSharedPackage() {
        // AARE's own built-in entries (phone, settings…) share AARE's package: keyed on id, like
        // CarRuntime.orderKey and the launcher's own appOrder.
        val phone = entry("phone", self)
        assertEquals(listOf(phone), resolveRailApps(listOf("phone"), listOf(phone), self))
        assertEquals(emptyList<CarAppEntry>(), resolveRailApps(listOf(self), listOf(phone), self))
    }

    @Test
    fun withKeyAddsAtTheEndAndRemoves() {
        assertEquals(listOf("a", "b"), listOf("a").withKey("b", true))
        assertEquals(listOf("a"), listOf("a", "b").withKey("b", false))
        assertEquals(listOf("a"), listOf("a").withKey("a", true)) // already chosen: unchanged
    }
}
