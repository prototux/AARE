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
        val zone = java.util.TimeZone.getTimeZone("Europe/Paris")
        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            java.util.Calendar.getInstance(zone).apply { set(year, month - 1, day, hour, minute, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        assertEquals(60_000L + 1_000, msUntilNextDay(at(2026, 11, 14, 23, 59), zone))
        assertEquals(24 * 3_600_000L + 1_000, msUntilNextDay(at(2026, 11, 14, 0, 0), zone))
        // The night the clocks go back is 25 hours long.
        assertEquals(25 * 3_600_000L + 1_000, msUntilNextDay(at(2026, 10, 25, 0, 0), zone))
    }

    @Test
    fun nullWhenTheArrayDoesNotCoverThatDay() {
        assertNull(dynamicCalendarIconIndex(31, arrayLength = 28))
        assertNull(dynamicCalendarIconIndex(1, arrayLength = 0))
    }
}
