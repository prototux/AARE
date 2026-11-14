package net.prototux.aare.ui.car

import net.prototux.aare.ui.car.RailWidget.BATTERY
import net.prototux.aare.ui.car.RailWidget.CLOCK
import net.prototux.aare.ui.car.RailWidget.TEMPERATURE
import net.prototux.aare.ui.car.RailWidget.WEATHER
import org.junit.Assert.assertEquals
import org.junit.Test

class RailWidgetsTest {
    @Test
    fun parseDropsUnknownAndRepeatedIds() {
        assertEquals(listOf(BATTERY, CLOCK), RailWidget.parse(listOf("battery", "gone", "clock", "battery")))
    }

    @Test
    fun showingAddsAtTheEndAndHidingRemoves() {
        assertEquals(listOf(CLOCK, WEATHER), listOf(CLOCK).withWidget(WEATHER, true))
        assertEquals(listOf(CLOCK, WEATHER), listOf(CLOCK, WEATHER).withWidget(WEATHER, true)) // already shown: unchanged
        assertEquals(listOf(CLOCK), listOf(CLOCK, WEATHER).withWidget(WEATHER, false))
    }

    @Test
    fun everyWidgetCanBeHiddenUnlikeDashboardCards() {
        // Unlike DashCard, a rail widget isn't the only thing filling the space: an empty list is fine.
        assertEquals(emptyList<RailWidget>(), listOf(CLOCK).withWidget(CLOCK, false))
    }

    @Test
    fun movingReordersTheChosenWidgets() {
        assertEquals(listOf(BATTERY, CLOCK, WEATHER), listOf(CLOCK, BATTERY, WEATHER).movedBy(1, -1))
    }

    @Test
    fun defaultIsClockBatteryWeather() {
        assertEquals(listOf(CLOCK, BATTERY, WEATHER), RailWidget.DEFAULT)
        assertEquals(TEMPERATURE, RailWidget.entries.first { it.id == "temperature" })
    }
}
