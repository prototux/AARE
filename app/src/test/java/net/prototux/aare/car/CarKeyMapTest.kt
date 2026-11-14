package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Steering-wheel key remapping: the pure id lookup, dispatch resolution and the storage encoding. */
class CarKeyMapTest {
    @Test
    fun actionIdsRoundTrip() {
        assertEquals(CarKeyAction.OPEN_PHONE, CarKeyAction.byId("open_phone"))
        assertEquals(CarKeyAction.NOTHING, CarKeyAction.byId("nothing"))
    }

    @Test
    fun unknownActionIdIsNull() {
        assertNull(CarKeyAction.byId("not-a-real-action"))
    }

    @Test
    fun anUnmappedKeyResolvesToNull() {
        assertNull(resolveCarKeyAction(emptyMap(), 85))
        assertNull(resolveCarKeyAction(mapOf(4 to "back"), 85))
    }

    @Test
    fun aMappedKeyResolvesToItsAction() {
        assertEquals(CarKeyAction.ASSISTANT, resolveCarKeyAction(mapOf(84 to "assistant"), 84))
    }

    @Test
    fun aStoredButUnknownActionIdResolvesToNullNotAGuess() {
        // A newer-than-this-build export, or a hand-edited preference: ignore it, don't fall back.
        assertNull(resolveCarKeyAction(mapOf(84 to "some_future_action"), 84))
    }

    @Test
    fun codecRoundTripsCodesAndValues() {
        val map = mapOf(3 to "home", 84 to "assistant", 126 to "play_pause")
        assertEquals(map, CarKeyMapCodec.decode(CarKeyMapCodec.encode(map)))
    }

    @Test
    fun codecIgnoresAMalformedEntryRatherThanCrashing() {
        val decoded = CarKeyMapCodec.decode(setOf("3\thome", "not-tab-separated", "notanumber\tback", "84\tassistant"))
        assertEquals(mapOf(3 to "home", 84 to "assistant"), decoded)
    }
}
