package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** [labelAgenda]: pure, so it's tested with a fixed clock and zone instead of the real one. */
class DashboardInfoAgendaTest {
    private val zone = TimeZone.getTimeZone("UTC")

    private fun epochMs(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply { clear(); set(year, month - 1, day, hour, minute, 0) }.timeInMillis

    // Wednesday 2024-01-10 12:00 UTC.
    private val now = epochMs(zone, 2024, 1, 10, 12, 0)

    private fun eventAt(daysFromNow: Int, hour: Int = 9): NextEvent {
        val startMs = epochMs(zone, 2024, 1, 10 + daysFromNow, hour, 0)
        return NextEvent("Event +$daysFromNow", startMs, allDay = false, location = "")
    }

    @Test
    fun todayIsLabelledToday() {
        val items = labelAgenda(listOf(eventAt(0)), now, zone)
        assertEquals(AgendaDay.Today, items.single().day)
    }

    @Test
    fun theNextCalendarDayIsTomorrowEvenIfLessThan24HoursAway() {
        // 2024-01-10 23:00 to 2024-01-11 01:00: under two hours apart, but a different calendar day.
        val event = NextEvent("Late", epochMs(zone, 2024, 1, 11, 1, 0), false, "")
        val lateNow = epochMs(zone, 2024, 1, 10, 23, 0)
        assertEquals(AgendaDay.Tomorrow, labelAgenda(listOf(event), lateNow, zone).single().day)
    }

    @Test
    fun laterDaysKeepTheirWeekday() {
        // +4 days from Wednesday the 10th is Sunday the 14th.
        val items = labelAgenda(listOf(eventAt(4)), now, zone)
        assertEquals(AgendaDay.Later(Calendar.SUNDAY), items.single().day)
    }

    @Test
    fun labelsEachEventInOrder() {
        val events = listOf(eventAt(0), eventAt(1), eventAt(2))
        val items = labelAgenda(events, now, zone)
        assertEquals(listOf(AgendaDay.Today, AgendaDay.Tomorrow, AgendaDay.Later(Calendar.FRIDAY)), items.map { it.day })
        assertEquals(events, items.map { it.event })
    }

    @Test
    fun aDifferentTimeZoneCanMoveAnEventToTheNextDay() {
        // In UTC alone, "now" (22:00) and the event (23:30) are the same calendar day. A UTC+1
        // reader's day has already turned for the event (00:30) but not yet for "now" (23:00).
        val plusOne = TimeZone.getTimeZone("GMT+01:00")
        val nowThere = epochMs(zone, 2024, 1, 10, 22, 0)
        val event = NextEvent("Late", epochMs(zone, 2024, 1, 10, 23, 30), false, "")
        assertEquals(AgendaDay.Tomorrow, labelAgenda(listOf(event), nowThere, plusOne).single().day)
    }

    /** An all-day event as CalendarContract stores it: BEGIN at UTC midnight of its date. */
    private fun allDay(title: String, day: Int) =
        NextEvent(title, epochMs(zone, 2024, 1, day, 0, 0), allDay = true, location = "")

    private fun timed(title: String, day: Int, hour: Int, at: TimeZone = zone) =
        NextEvent(title, epochMs(at, 2024, 1, day, hour, 0), allDay = false, location = "")

    @Test
    fun anAllDayEventKeepsItsDateWestOfUtc() {
        // 2024-01-11 00:00 UTC is still the 10th in New York, but the all-day event is the 11th's.
        val ny = TimeZone.getTimeZone("America/New_York")
        val nowNy = epochMs(ny, 2024, 1, 10, 9, 0)
        assertEquals(AgendaDay.Tomorrow, labelAgenda(listOf(allDay("Holiday", 11)), nowNy, ny).single().day)
    }

    @Test
    fun theAgendaGoesDayByDayWithAllDayEventsFirst() {
        // Calendar rows come in BEGIN order; a week's agenda must not put every timed event first.
        val rows = listOf(timed("Wed 15h", 10, 15), allDay("Thu all day", 11), timed("Thu 8h", 11, 8), allDay("Wed all day", 10))
        assertEquals(listOf("Wed all day", "Wed 15h", "Thu all day", "Thu 8h"), selectAgenda(rows, 5, now, zone).map { it.title })
        assertEquals(listOf("Wed all day", "Wed 15h"), selectAgenda(rows, 2, now, zone).map { it.title })
    }

    @Test
    fun aSingleEventStillPrefersTheNextTimedOne() {
        val rows = listOf(allDay("Wed all day", 10), timed("Wed 15h", 10, 15))
        assertEquals(listOf("Wed 15h"), selectAgenda(rows, 1, now, zone).map { it.title })
        assertEquals(listOf("Wed all day"), selectAgenda(listOf(allDay("Wed all day", 10)), 1, now, zone).map { it.title })
    }

    @Test
    fun yesterdaysAllDayEventIsDroppedEastOfUtc() {
        // 08:00 on the 10th in Tokyo is 23:00 UTC on the 9th: the 9th's all-day event (to 00:00 UTC
        // on the 10th) still overlaps the query window, but that day is over there.
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val nowTokyo = epochMs(tokyo, 2024, 1, 10, 8, 0)
        val rows = listOf(allDay("Yesterday", 9), allDay("Today", 10))
        assertEquals(listOf("Today"), selectAgenda(rows, 5, nowTokyo, tokyo).map { it.title })
        assertEquals(AgendaDay.Today, labelAgenda(selectAgenda(rows, 5, nowTokyo, tokyo), nowTokyo, tokyo).single().day)
    }
}
