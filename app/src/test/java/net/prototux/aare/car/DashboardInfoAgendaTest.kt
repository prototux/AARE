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
}
