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
}
