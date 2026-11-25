package net.prototux.aare.ui.car

import net.prototux.aare.ui.car.DashCard.CALENDAR
import net.prototux.aare.ui.car.DashCard.CLOCK
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
        assertEquals(listOf(MEDIA, MAP, CALENDAR), listOf(MAP, MEDIA, CALENDAR).movedBy(1, -1))
        assertEquals(listOf(MAP, MEDIA, CALENDAR), listOf(MAP, MEDIA, CALENDAR).movedBy(0, -1))
        assertEquals(listOf(MEDIA, CALENDAR, MAP), listOf(MAP, MEDIA, CALENDAR).movedBy(0, 5))
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

    @Test
    fun theUsersOwnCardsAreKeptInTheOrder() {
        val order = DashCard.parse(listOf("map", "face:ab12", "clock", "face:ab12"))
        assertEquals(listOf(MAP, FaceCard("ab12"), CLOCK), order)
        assertEquals(listOf("map", "face:ab12", "clock"), order.map(DashItem::id))
    }

    private fun stack(order: List<DashItem>, slots: Int = 3, mapBig: Boolean = false, shown: (DashItem) -> Boolean = { true }) =
        dashboardStack(order, dashboardLayout(order, slots, mapBig, shown), shown)

    @Test
    fun theCardsWithoutAPlaceShareTheLastOne() {
        assertEquals(listOf(WEATHER, CLOCK, FaceCard("a")), stack(listOf(MAP, MEDIA, WEATHER, CLOCK, FaceCard("a"))))
        assertEquals(listOf(WEATHER, FaceCard("a")), stack(listOf(MAP, MEDIA, WEATHER, CLOCK, FaceCard("a"))) { it != CLOCK })
        // On a small screen the music's strip is the last place.
        assertEquals(listOf(MEDIA, WEATHER), stack(listOf(MAP, MEDIA, WEATHER), slots = 2))
        assertEquals(listOf(MEDIA), stack(listOf(MAP, MEDIA)))
        assertEquals(emptyList<DashItem>(), stack(listOf(MAP)))
    }

    @Test
    fun theMapNeverSharesItsPlace() {
        assertEquals(listOf(WEATHER, CLOCK), stack(listOf(MEDIA, WEATHER, MAP, CLOCK)))
        assertEquals(listOf(WEATHER, CLOCK), stack(listOf(MEDIA, WEATHER, CLOCK, MAP), mapBig = true))
        assertEquals(listOf(CLOCK), stack(listOf(MEDIA, WEATHER, CLOCK, MAP)))
    }

    @Test
    fun theCardsTakeTurnsRoundAndRound() {
        val turning = listOf(WEATHER, MEDIA, CLOCK)
        assertEquals(MEDIA, nextInTurn(turning, WEATHER))
        assertEquals(WEATHER, nextInTurn(turning, CLOCK))
        assertEquals(null, nextInTurn(listOf(CLOCK), CLOCK))
        // One that takes no turns (the small screens' strip) stays.
        assertEquals(null, nextInTurn(listOf(WEATHER, CLOCK), MEDIA))
    }

    @Test
    fun swipesGoRoundBothWays() {
        val cards = listOf(WEATHER, MEDIA, CLOCK)
        assertEquals(WEATHER, stackNeighbour(cards, CLOCK, 1))
        assertEquals(CLOCK, stackNeighbour(cards, WEATHER, -1))
        assertEquals(CLOCK, stackNeighbour(cards, CLOCK, 0))
        // The one shown is gone: from the first.
        assertEquals(MEDIA, stackNeighbour(cards, CALENDAR, 1))
    }

    @Test
    fun aSwipeGoesAllTheWayOrBack() {
        fun step(dragged: Float, furthest: Float = dragged) = swipeStep(dragged, furthest, enough = 24f)
        assertEquals(1, step(-100f))
        assertEquals(-1, step(100f))
        assertEquals(1, step(-25f)) // short, but a swipe
        assertEquals(0, step(-20f)) // not one
        assertEquals(0, step(-60f, furthest = -150f)) // drawn back
        assertEquals(1, step(-120f, furthest = -150f)) // a finger that slips a little as it lifts
        assertEquals(0, step(0f))
    }

    @Test
    fun theListIsPartedIntoTheBigTheFixedAndTheTurningCards() {
        fun zones(vararg order: DashItem, mapBig: Boolean = false) = dashboardZones(order.toList(), mapBig).values.joinToString("") { it.name.take(1) }
        assertEquals("BFTT", zones(MAP, MEDIA, WEATHER, CLOCK))
        assertEquals("FBTT", zones(MEDIA, MAP, WEATHER, CLOCK, mapBig = true))
        assertEquals("BFT", zones(MEDIA, WEATHER, CLOCK))
        assertEquals("BF", zones(MAP, MEDIA))
        assertEquals("B", zones(MAP))
        // The map can't take turns: what it would share its place with shares the one before.
        assertEquals("BTFT", zones(MEDIA, WEATHER, MAP, CLOCK))
    }

    @Test
    fun theCalendarsBoxGoesRoundThreeStates() {
        assertEquals(CalendarCardMode.ALWAYS, CalendarCardMode.OFF.next(true))
        assertEquals(CalendarCardMode.AUTO, CalendarCardMode.ALWAYS.next(true))
        assertEquals(CalendarCardMode.OFF, CalendarCardMode.AUTO.next(true))
        assertEquals(CalendarCardMode.ALWAYS, CalendarCardMode.AUTO.next(false))
    }
}
