package net.prototux.aare.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class EventWindowTest {
    private val newYork = ZoneId.of("America/New_York")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val hour = 3_600_000L

    private fun at(text: String, zone: ZoneId) = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone).toInstant().toEpochMilli()
    /** An all-day event of [date], as the calendar stores it: UTC midnight to UTC midnight. */
    private fun allDay(date: String) = Instant.parse("${date}T00:00:00Z").toEpochMilli().let { it to it + 24 * hour }

    @Test fun `west of UTC tomorrow's all-day event isn't today's`() {
        // Its UTC midnight is 19:00 the evening before in New York, well within the coming 12 hours.
        val w = EventWindow(at("2026-11-10T13:57:00", newYork), newYork)
        val (begin, end) = allDay("2026-11-11")
        assertFalse(w.shows(begin, end, allDay = true))
    }

    @Test fun `today's all-day event shows all day west of UTC`() {
        val (begin, end) = allDay("2026-11-10")
        for (time in listOf("00:05:00", "13:57:00", "23:30:00")) {
            val now = at("2026-11-10T$time", newYork)
            val w = EventWindow(now, newYork)
            assertTrue(time, w.shows(begin, end, allDay = true))
            assertTrue("$time is asked for", w.queryFrom <= end && w.queryTo >= begin)
        }
    }

    @Test fun `east of UTC yesterday's all-day event is over at midnight`() {
        // It "ends" at 01:00 in Berlin, after the day has changed there.
        val now = at("2026-11-11T00:30:00", berlin)
        val w = EventWindow(now, berlin)
        val (begin, end) = allDay("2026-11-10")
        assertFalse(w.shows(begin, end, allDay = true))
        val (b2, e2) = allDay("2026-11-11")
        assertTrue(w.shows(b2, e2, allDay = true))
        assertTrue(w.queryFrom <= e2 && w.queryTo >= b2)
    }

    @Test fun `an all-day event of several days shows on each of them`() {
        val begin = allDay("2026-11-09").first
        val end = allDay("2026-11-11").second
        assertTrue(EventWindow(at("2026-11-10T09:00:00", newYork), newYork).shows(begin, end, allDay = true))
        assertFalse(EventWindow(at("2026-11-12T09:00:00", newYork), newYork).shows(begin, end, allDay = true))
    }

    @Test fun `timed events count for the coming 12 hours, and a little after they start`() {
        val now = at("2026-11-10T13:57:00", newYork)
        val w = EventWindow(now, newYork)
        assertTrue(w.shows(now + 2 * hour, now + 3 * hour, allDay = false))
        assertTrue("under way", w.shows(now - hour, now + hour, allDay = false))
        assertFalse("too far off", w.shows(now + 13 * hour, now + 14 * hour, allDay = false))
        assertFalse("over", w.shows(now - 2 * hour, now - hour, allDay = false))
    }
}
