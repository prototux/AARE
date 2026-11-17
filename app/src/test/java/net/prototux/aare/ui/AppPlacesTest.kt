package net.prototux.aare.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPlacesTest {
    private val none = AppPlaces(emptyList(), emptyList(), emptyList(), emptySet(), emptySet())

    @Test
    fun anAndroidAutoAppIsInTheOverviewUntilSwitchedOff() {
        assertEquals(AppPlace.OVERVIEW, none.placeOf("app.comaps", optIn = false))
        assertEquals(AppPlace.OFF, none.copy(hidden = setOf("app.comaps")).placeOf("app.comaps", optIn = false))
    }

    @Test
    fun aPhoneAppIsOffUntilPicked() {
        assertEquals(AppPlace.OFF, none.placeOf("com.android.vending", optIn = true))
        assertEquals(AppPlace.OVERVIEW, none.copy(picked = setOf("com.android.vending")).placeOf("com.android.vending", optIn = true))
    }

    @Test
    fun movingToTheRailLeavesEveryOtherPlace() {
        val start = none.copy(shortcuts = listOf("a", "b"), order = listOf("b"), hidden = setOf("b"))
        val moved = start.moved("b", AppPlace.RAIL, optIn = false)
        assertEquals(listOf("b"), moved.rail)
        assertEquals(listOf("a"), moved.shortcuts)
        assertEquals(emptySet<String>(), moved.hidden)
        assertEquals(AppPlace.RAIL, moved.placeOf("b", optIn = false))
    }

    @Test
    fun aPhoneAppSwitchedOffIsNoLongerPicked() {
        val start = none.copy(rail = listOf("p"), picked = setOf("p"))
        val moved = start.moved("p", AppPlace.OFF, optIn = true)
        assertEquals(emptyList<String>(), moved.rail)
        assertEquals(emptySet<String>(), moved.picked)
        assertEquals(emptySet<String>(), moved.hidden)
    }

    @Test
    fun theOverviewTakesTheAppAtItsEnd() {
        val moved = none.copy(order = listOf("x", "y"), rail = listOf("z")).moved("z", AppPlace.OVERVIEW, optIn = false)
        assertEquals(listOf("x", "y", "z"), moved.order)
    }

    @Test
    fun shiftedMovesInsideTheList() {
        assertEquals(listOf("b", "a", "c"), listOf("a", "b", "c").shifted(0, 1))
        assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").shifted(2, -5))
    }

    @Test
    fun aDragKeepsTheAppsThePageDoesNotListYet() {
        // A phone app on the rail, while the phone apps still load: the drag mustn't drop it.
        assertEquals(listOf("b", "a", "maps"), keptOrder(listOf("b", "a"), listOf("a", "maps", "b")))
        assertEquals(listOf("a", "b"), keptOrder(listOf("a", "b"), listOf("b", "a")))
    }
}
