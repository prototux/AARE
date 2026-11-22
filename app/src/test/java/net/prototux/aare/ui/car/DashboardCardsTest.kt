package net.prototux.aare.ui.car

import net.prototux.aare.ui.car.DashCard.CALENDAR
import net.prototux.aare.ui.car.DashCard.MAP
import net.prototux.aare.ui.car.DashCard.MEDIA
import net.prototux.aare.ui.car.DashCard.NOTIFICATION
import net.prototux.aare.ui.car.DashCard.WEATHER
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardCardsTest {
    @Test
    fun parseDropsUnknownAndRepeatedIds() {
        assertEquals(listOf(MEDIA, MAP), DashCard.parse(listOf("media", "gone", "map", "media")))
    }

    @Test
    fun slotsFollowTheOrderAndSkipCardsWithNothingToShow() {
        // No calendar event: the latest notification takes its slot, as before.
        assertEquals(listOf(MAP, MEDIA, NOTIFICATION), dashboardSlots(DashCard.DEFAULT, 3) { it != CALENDAR })
        assertEquals(listOf(MAP, MEDIA, CALENDAR), dashboardSlots(DashCard.DEFAULT, 3) { true })
    }

    @Test
    fun slotsStopAtTheScreensRoom() {
        assertEquals(listOf(MEDIA, MAP), dashboardSlots(listOf(MEDIA, MAP, WEATHER), 2) { true })
    }

    @Test
    fun theMapStandsInWhenNothingIsLeft() {
        assertEquals(listOf(MAP), dashboardSlots(listOf(CALENDAR), 3) { false })
    }

    @Test
    fun showingAddsAtTheEndAndHidingRemoves() {
        assertEquals(listOf(MAP, MEDIA, WEATHER), listOf(MAP, MEDIA).withCard(WEATHER, true))
        assertEquals(listOf(MAP, MEDIA), listOf(MAP, MEDIA).withCard(MEDIA, true))
        assertEquals(listOf(MEDIA), listOf(MAP, MEDIA).withCard(MAP, false))
    }

    @Test
    fun theLastCardCantBeHidden() {
        assertEquals(listOf(MAP), listOf(MAP).withCard(MAP, false))
    }

    @Test
    fun movingSwapsAndClamps() {
        assertEquals(listOf(MEDIA, MAP, CALENDAR), listOf(MAP, MEDIA, CALENDAR).moved(1, -1))
        assertEquals(listOf(MAP, MEDIA, CALENDAR), listOf(MAP, MEDIA, CALENDAR).moved(0, -1))
        assertEquals(listOf(MEDIA, CALENDAR, MAP), listOf(MAP, MEDIA, CALENDAR).moved(0, 5))
    }

    @Test
    fun switchOffIsTodaysSlotsWithTheFirstBig() {
        val l = dashboardLayout(listOf(MEDIA, MAP, CALENDAR), 3, mapBig = false) { true }
        assertEquals(DashSlots(listOf(MEDIA, MAP, CALENDAR), 0, bigFirst = true), l)
        assertEquals(listOf(0, 1, 2), (0..2).map(l::rank))
    }

    @Test
    fun theMapKeepsTheBigSizeAfterTheMusic() {
        val l = dashboardLayout(listOf(MEDIA, MAP, CALENDAR), 3, mapBig = true) { true }
        assertEquals(DashSlots(listOf(MEDIA, MAP, CALENDAR), 1, bigFirst = false), l)
        assertEquals(listOf(1, 0, 2), (0..2).map(l::rank))
    }

    @Test
    fun theMapFirstIsUnchangedWithTheSwitchOn() {
        assertEquals(DashSlots(listOf(MAP, MEDIA, CALENDAR), 0, bigFirst = true), dashboardLayout(DashCard.DEFAULT, 3, mapBig = true) { true })
    }

    @Test
    fun theMapAfterTheSlotLimitStillShows() {
        val l = dashboardLayout(listOf(MEDIA, CALENDAR, NOTIFICATION, MAP), 3, mapBig = true) { true }
        assertEquals(DashSlots(listOf(MEDIA, CALENDAR, MAP), 2, bigFirst = false), l)
        assertEquals(listOf(1, 2, 0), (0..2).map(l::rank))
        // Cards with nothing to show are skipped before the limit, as without the switch.
        assertEquals(
            DashSlots(listOf(MEDIA, NOTIFICATION, MAP), 2, bigFirst = false),
            dashboardLayout(listOf(MEDIA, CALENDAR, NOTIFICATION, WEATHER, MAP), 3, mapBig = true) { it != CALENDAR },
        )
    }

    @Test
    fun aHiddenMapMakesTheSwitchDoNothing() {
        val order = listOf(MEDIA, CALENDAR, WEATHER)
        assertEquals(dashboardLayout(order, 3, mapBig = false) { true }, dashboardLayout(order, 3, mapBig = true) { true })
    }

    @Test
    fun theMapAloneIsBig() {
        assertEquals(DashSlots(listOf(MAP), 0, bigFirst = false), dashboardLayout(listOf(CALENDAR, MAP), 3, mapBig = true) { it != CALENDAR })
    }

    @Test
    fun twoSlotsKeepTheMapAndTheFirstOther() {
        assertEquals(DashSlots(listOf(MEDIA, MAP), 1, bigFirst = false), dashboardLayout(listOf(MEDIA, WEATHER, MAP), 2, mapBig = true) { true })
    }

    @Test
    fun aCardBeforeTheMapKeepsItsSideWhenItComesAndGoes() {
        val order = listOf(CALENDAR, MAP, MEDIA)
        assertEquals(DashSlots(listOf(CALENDAR, MAP, MEDIA), 1, bigFirst = false), dashboardLayout(order, 3, mapBig = true) { true })
        assertEquals(DashSlots(listOf(MAP, MEDIA), 0, bigFirst = false), dashboardLayout(order, 3, mapBig = true) { it != CALENDAR })
    }
}
