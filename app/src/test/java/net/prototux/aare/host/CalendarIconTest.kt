package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarIconTest {
    @Test
    fun indexIsOneLessThanTheDayOfMonth() {
        assertEquals(0, dynamicCalendarIconIndex(1, arrayLength = 31))
        assertEquals(14, dynamicCalendarIconIndex(15, arrayLength = 31))
        assertEquals(30, dynamicCalendarIconIndex(31, arrayLength = 31))
    }

    @Test
    fun theIconsAreRedrawnJustAfterMidnight() {
        val zone = java.time.ZoneId.of("Europe/Paris")
        assertEquals(60_000L + 1_000, msUntilNextDay(java.time.ZonedDateTime.of(2026, 11, 14, 23, 59, 0, 0, zone)))
        assertEquals(24 * 3_600_000L + 1_000, msUntilNextDay(java.time.ZonedDateTime.of(2026, 11, 14, 0, 0, 0, 0, zone)))
        // The night the clocks go back is 25 hours long.
        assertEquals(25 * 3_600_000L + 1_000, msUntilNextDay(java.time.ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, zone)))
    }

    @Test
    fun nullWhenTheArrayDoesNotCoverThatDay() {
        assertNull(dynamicCalendarIconIndex(31, arrayLength = 28))
        assertNull(dynamicCalendarIconIndex(1, arrayLength = 0))
    }
}
