package net.prototux.aare.car

import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiElement
import net.prototux.aare.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class CarChromeTest {
    private val clockOnly = CarChrome(clock = true, phoneSignal = false, battery = false)

    @Test
    fun nothingFromTheHeadUnitDrawsNothing() {
        assertEquals(CarChrome.NONE, CarChrome.drawnByHeadUnit(atLeast43 = false, sessionBits = 0, uiElements = emptyList()))
        assertEquals(CarChrome.NONE, CarChrome.drawnByHeadUnit(atLeast43 = true, sessionBits = 0, uiElements = emptyList()))
    }

    @Test
    fun olderHeadUnitsUseTheSessionBits() {
        assertEquals(clockOnly, CarChrome.drawnByHeadUnit(false, 1, emptyList()))
        assertEquals(CarChrome(clock = false, phoneSignal = true, battery = false), CarChrome.drawnByHeadUnit(false, 2, emptyList()))
        assertEquals(CarChrome(clock = false, phoneSignal = false, battery = true), CarChrome.drawnByHeadUnit(false, 4, emptyList()))
        // Bit 8 (native media during voice) is not a status item.
        assertEquals(CarChrome.ALL, CarChrome.drawnByHeadUnit(false, 1 or 2 or 4 or 8, emptyList()))
        // UI elements are not read below 4.3.
        assertEquals(CarChrome.NONE, CarChrome.drawnByHeadUnit(false, 0, listOf(UiElement.UI_ELEMENT_CLOCK)))
    }

    @Test
    fun newerHeadUnitsUseTheUiElements() {
        assertEquals(clockOnly, CarChrome.drawnByHeadUnit(true, 0, listOf(UiElement.UI_ELEMENT_CLOCK)))
        assertEquals(CarChrome(clock = false, phoneSignal = true, battery = true), CarChrome.drawnByHeadUnit(true, 0,
            listOf(UiElement.UI_ELEMENT_BATTERY_LEVEL, UiElement.UI_ELEMENT_PHONE_SIGNAL, UiElement.UI_ELEMENT_NATIVE_UI_AFFORDANCE)))
        // The session bits are not read from 4.3 on.
        assertEquals(CarChrome.NONE, CarChrome.drawnByHeadUnit(true, 1 or 2 or 4, emptyList()))
    }

    @Test
    fun followTheCarHidesWhatTheCarDraws() {
        assertEquals(CarChrome.ALL, CarChrome.shown(Settings.CHROME_AUTO, CarChrome.NONE))
        assertEquals(CarChrome(clock = false, phoneSignal = true, battery = true), CarChrome.shown(Settings.CHROME_AUTO, clockOnly))
        assertEquals(CarChrome.NONE, CarChrome.shown(Settings.CHROME_AUTO, CarChrome.ALL))
        // An unknown stored value follows the car too.
        assertEquals(CarChrome.ALL, CarChrome.shown("", CarChrome.NONE))
    }

    @Test
    fun alwaysShowAndAlwaysHideIgnoreTheCar() {
        for (car in listOf(CarChrome.NONE, clockOnly, CarChrome.ALL)) {
            assertEquals(CarChrome.ALL, CarChrome.shown(Settings.CHROME_SHOW, car))
            assertEquals(CarChrome.NONE, CarChrome.shown(Settings.CHROME_HIDE, car))
        }
    }
}
