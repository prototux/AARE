package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** [labelAgenda]: pure, so it's tested with a fixed clock and zone instead of the real one. */
class DashboardInfoAgendaTest {
    private val zone = ZoneId.of("UTC")

    // Wednesday 2024-01-10 12:00 UTC.
    private val now = ZonedDateTime.of(2024, 1, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun eventAt(daysFromNow: Int, hour: Int = 9): NextEvent {
        val startMs = ZonedDateTime.of(2024, 1, 10 + daysFromNow, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
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
        val event = NextEvent("Late", ZonedDateTime.of(2024, 1, 11, 1, 0, 0, 0, zone).toInstant().toEpochMilli(), false, "")
        val lateNow = ZonedDateTime.of(2024, 1, 10, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(AgendaDay.Tomorrow, labelAgenda(listOf(event), lateNow, zone).single().day)
    }

    @Test
    fun laterDaysKeepTheirWeekday() {
        // +4 days from Wednesday the 10th is Sunday the 14th.
        val items = labelAgenda(listOf(eventAt(4)), now, zone)
        assertEquals(AgendaDay.Later(DayOfWeek.SUNDAY), items.single().day)
    }

    @Test
    fun labelsEachEventInOrder() {
        val events = listOf(eventAt(0), eventAt(1), eventAt(2))
        val items = labelAgenda(events, now, zone)
        assertEquals(listOf(AgendaDay.Today, AgendaDay.Tomorrow, AgendaDay.Later(DayOfWeek.FRIDAY)), items.map { it.day })
        assertEquals(events, items.map { it.event })
    }

    @Test
    fun aDifferentTimeZoneCanMoveAnEventToTheNextDay() {
        // In UTC alone, "now" (22:00) and the event (23:30) are the same calendar day. A UTC+1
        // reader's day has already turned for the event (00:30) but not yet for "now" (23:00).
        val plusOne = ZoneId.of("UTC+1")
        val nowThere = ZonedDateTime.of(2024, 1, 10, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        val event = NextEvent("Late", ZonedDateTime.of(2024, 1, 10, 23, 30, 0, 0, zone).toInstant().toEpochMilli(), false, "")
        assertEquals(AgendaDay.Tomorrow, labelAgenda(listOf(event), nowThere, plusOne).single().day)
    }

    /** An all-day event as CalendarContract stores it: BEGIN at UTC midnight of its date. */
    private fun allDay(title: String, day: Int) =
        NextEvent(title, ZonedDateTime.of(2024, 1, day, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), allDay = true, location = "")

    private fun timed(title: String, day: Int, hour: Int, at: ZoneId = zone) =
        NextEvent(title, ZonedDateTime.of(2024, 1, day, hour, 0, 0, 0, at).toInstant().toEpochMilli(), allDay = false, location = "")

    @Test
    fun anAllDayEventKeepsItsDateWestOfUtc() {
        // 2024-01-11 00:00 UTC is still the 10th in New York, but the all-day event is the 11th's.
        val ny = ZoneId.of("America/New_York")
        val nowNy = ZonedDateTime.of(2024, 1, 10, 9, 0, 0, 0, ny).toInstant().toEpochMilli()
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
        val tokyo = ZoneId.of("Asia/Tokyo")
        val nowTokyo = ZonedDateTime.of(2024, 1, 10, 8, 0, 0, 0, tokyo).toInstant().toEpochMilli()
        val rows = listOf(allDay("Yesterday", 9), allDay("Today", 10))
        assertEquals(listOf("Today"), selectAgenda(rows, 5, nowTokyo, tokyo).map { it.title })
        assertEquals(AgendaDay.Today, labelAgenda(selectAgenda(rows, 5, nowTokyo, tokyo), nowTokyo, tokyo).single().day)
    }
}
