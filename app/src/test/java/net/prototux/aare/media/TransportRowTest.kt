package net.prototux.aare.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportRowTest {
    private val actions = listOf("a", "b", "c")

    @Test
    fun usableSkipButtonsKeepTheirSlots() {
        assertEquals(TransportRow(listOf("a"), null, null, listOf("b")), transportRow(actions, 2, true, true, false, false))
    }

    @Test
    fun anUnusableSkipButtonGivesItsSlotToAnAction() {
        assertEquals(TransportRow(listOf("b"), "a", null, listOf("c")), transportRow(actions, 2, false, true, false, false))
        assertEquals(TransportRow(listOf("c"), "a", "b", emptyList()), transportRow(actions, 2, false, false, false, false))
    }

    @Test
    fun aReservedSlotStaysTheSkipButton() {
        assertEquals(TransportRow(listOf("a"), null, null, listOf("b")), transportRow(actions, 2, false, false, true, true))
        assertEquals(TransportRow(listOf("b"), null, "a", listOf("c")), transportRow(actions, 2, false, false, true, false))
    }

    @Test
    fun withoutActionsTheSkipButtonsStay() {
        assertEquals(TransportRow<String>(emptyList(), null, null, emptyList()), transportRow(emptyList<String>(), 4, false, false, false, false))
        assertEquals(TransportRow(emptyList(), "a", null, emptyList()), transportRow(listOf("a"), 4, false, false, false, false))
    }

    @Test
    fun noRoomBesideTheButtons() {
        assertEquals(TransportRow(emptyList(), "a", null, emptyList()), transportRow(actions, -1, false, true, false, false))
    }
}
