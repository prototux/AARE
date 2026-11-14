package net.prototux.aare.ui.car

import net.prototux.aare.ui.car.RailWidget.BATTERY
import net.prototux.aare.ui.car.RailWidget.CLOCK
import net.prototux.aare.ui.car.RailWidget.TEMPERATURE
import net.prototux.aare.ui.car.RailWidget.WEATHER
import net.prototux.aare.car.usesFahrenheit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    @Test
    fun temperatureGivesWayToTheWeatherWithoutACarSensor() {
        // No car sensor: the temperature widget would show the weather chip, already there once.
        assertEquals(listOf(CLOCK, WEATHER), listOf(CLOCK, TEMPERATURE, WEATHER).forDisplay(carHasTemperature = false))
        assertEquals(listOf(TEMPERATURE), listOf(TEMPERATURE).forDisplay(carHasTemperature = false)) // alone: it is the weather
        assertEquals(listOf(TEMPERATURE, WEATHER), listOf(TEMPERATURE, WEATHER).forDisplay(carHasTemperature = true))
    }

    @Test
    fun outsideTemperatureFollowsTheLocaleUnit() {
        assertEquals("21°", outsideTemperatureLabel(21.4f, fahrenheit = false))
        assertEquals("71°F", outsideTemperatureLabel(21.4f, fahrenheit = true))
        assertEquals("-5°", outsideTemperatureLabel(-4.6f, fahrenheit = false))
        assertTrue(usesFahrenheit(Locale.US))
        assertFalse(usesFahrenheit(Locale.FRANCE))
    }
}
