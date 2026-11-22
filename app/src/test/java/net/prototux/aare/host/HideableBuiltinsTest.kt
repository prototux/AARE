package net.prototux.aare.host

import net.prototux.aare.ui.car.CarAppEntry
import net.prototux.aare.ui.car.CarAppKind
import net.prototux.aare.ui.car.withoutLauncherHidden
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which built-in apps the user may hide from the car's app grid, and what hiding them changes. */
class HideableBuiltinsTest {
    private fun builtin(id: String, kind: CarAppKind) = CarAppEntry(id, "net.prototux.aare", id, null, kind)

    private val apps = listOf(
        builtin(CarRuntime.PHONE_ID, CarAppKind.PHONE),
        builtin(CarRuntime.SETTINGS_ID, CarAppKind.SETTINGS),
        builtin(CarRuntime.NOTIFICATIONS_ID, CarAppKind.NOTIFICATIONS),
    )

    @Test
    fun phoneAndNotificationsCanBeHiddenButNeverSettings() {
        // Hiding Settings would lock the user out of the car-side settings from the car itself;
        // packages in hiddenApps are not built-ins and are dropped elsewhere.
        val hidden = setOf(CarRuntime.PHONE_ID, CarRuntime.NOTIFICATIONS_ID, CarRuntime.SETTINGS_ID, "com.example.app")
        assertEquals(setOf(CarRuntime.PHONE_ID, CarRuntime.NOTIFICATIONS_ID), CarRuntime.launcherHiddenIds(hidden))
        assertEquals(emptySet<String>(), CarRuntime.launcherHiddenIds(emptySet()))
    }

    @Test
    fun hidingOnlyTakesThemOffTheGrid() {
        val hidden = CarRuntime.launcherHiddenIds(setOf(CarRuntime.PHONE_ID, CarRuntime.NOTIFICATIONS_ID))
        assertEquals(listOf(CarRuntime.SETTINGS_ID), apps.withoutLauncherHidden(hidden).map { it.id })
    }

    @Test
    fun nothingHiddenKeepsTheWholeGrid() {
        assertEquals(apps, apps.withoutLauncherHidden(CarRuntime.launcherHiddenIds(emptySet())))
    }
}
