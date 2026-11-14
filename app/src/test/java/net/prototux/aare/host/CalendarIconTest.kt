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
    fun nullWhenTheArrayDoesNotCoverThatDay() {
        assertNull(dynamicCalendarIconIndex(31, arrayLength = 28))
        assertNull(dynamicCalendarIconIndex(1, arrayLength = 0))
    }
}
