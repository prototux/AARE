package net.prototux.aare.transport

import net.prototux.aare.aap.wireless.WirelessHandshake.ADAPTER
import net.prototux.aare.aap.wireless.WirelessHandshake.BUILT_IN
import net.prototux.aare.transport.wireless.HandshakeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeChoiceTest {
    @Test
    fun adapterNamesAreRecognised() {
        listOf("AAWireless-1A2B", "AA Wireless", "Carlinkit-7F3C", "AutoKit-0042", "OTTOCAST U2-AIR", "Motorola MA1", "MA1-5C21", "CPC200-CCPA")
            .forEach { assertTrue(it, HandshakeChoice.looksLikeAdapter(it)) }
    }

    @Test
    fun carNamesAreNot() {
        listOf("MY CAR", "Uconnect", "SYNC", "Audi MMI 2041", "Mazda", "Cinema1", "Toyota Entune", "", null)
            .forEach { assertFalse("$it", HandshakeChoice.looksLikeAdapter(it)) }
    }

    @Test
    fun autoPrefersWhatWorkedThenTheName() {
        assertEquals(BUILT_IN, HandshakeChoice.first(HandshakeChoice.AUTO, null, "Uconnect"))
        assertEquals(ADAPTER, HandshakeChoice.first(HandshakeChoice.AUTO, null, "AAWireless-1A2B"))
        // what worked last time wins over the name, both ways
        assertEquals(BUILT_IN, HandshakeChoice.first(HandshakeChoice.AUTO, BUILT_IN, "AAWireless-1A2B"))
        assertEquals(ADAPTER, HandshakeChoice.first(HandshakeChoice.AUTO, ADAPTER, "Uconnect"))
    }

    @Test
    fun aFixedSettingIgnoresMemoryAndName() {
        assertEquals(BUILT_IN, HandshakeChoice.first(HandshakeChoice.BUILT_IN, ADAPTER, "AAWireless-1A2B"))
        assertEquals(ADAPTER, HandshakeChoice.first(HandshakeChoice.ADAPTER, BUILT_IN, "Uconnect"))
        // an unknown stored value behaves as Auto
        assertEquals(ADAPTER, HandshakeChoice.first("something", null, "Carlinkit"))
    }

    @Test
    fun theOtherHandshakeIsTriedOnceAfterANegotiationFailureInAuto() {
        assertEquals(ADAPTER, HandshakeChoice.switchAfterFailure(HandshakeChoice.AUTO, BUILT_IN, whileNegotiating = true, alreadySwitched = false))
        assertEquals(BUILT_IN, HandshakeChoice.switchAfterFailure(HandshakeChoice.AUTO, ADAPTER, whileNegotiating = true, alreadySwitched = false))
        assertNull("once per connection", HandshakeChoice.switchAfterFailure(HandshakeChoice.AUTO, ADAPTER, whileNegotiating = true, alreadySwitched = true))
        assertNull("a Wi-Fi or TCP failure isn't the order", HandshakeChoice.switchAfterFailure(HandshakeChoice.AUTO, BUILT_IN, whileNegotiating = false, alreadySwitched = false))
        assertNull("a fixed setting is kept", HandshakeChoice.switchAfterFailure(HandshakeChoice.BUILT_IN, BUILT_IN, whileNegotiating = true, alreadySwitched = false))
        assertNull(HandshakeChoice.switchAfterFailure(HandshakeChoice.ADAPTER, ADAPTER, whileNegotiating = true, alreadySwitched = false))
    }

    @Test
    fun memoryIsPerAddressAndCaseInsensitive() {
        var entries = HandshakeChoice.remember(emptyList(), "aa:bb:cc:dd:ee:01", ADAPTER)
        entries = HandshakeChoice.remember(entries, "AA:BB:CC:DD:EE:02", BUILT_IN)
        entries = HandshakeChoice.remember(entries, "AA:BB:CC:DD:EE:01", BUILT_IN)
        assertEquals(mapOf("AA:BB:CC:DD:EE:01" to BUILT_IN, "AA:BB:CC:DD:EE:02" to BUILT_IN), HandshakeChoice.parse(entries))
        assertEquals("the updated address moves to the end", "AA:BB:CC:DD:EE:01\tbuiltin", entries.last())
    }

    @Test
    fun memoryDropsTheOldestAndIgnoresJunk() {
        var entries = emptyList<String>()
        repeat(HandshakeChoice.MAX_REMEMBERED + 3) { i -> entries = HandshakeChoice.remember(entries, "00:00:00:00:00:%02X".format(i), ADAPTER) }
        val parsed = HandshakeChoice.parse(entries)
        assertEquals(HandshakeChoice.MAX_REMEMBERED, parsed.size)
        assertFalse("oldest dropped", "00:00:00:00:00:00" in parsed)
        assertTrue("newest kept", "00:00:00:00:00:%02X".format(HandshakeChoice.MAX_REMEMBERED + 2) in parsed)

        assertEquals(mapOf("AA:BB:CC:DD:EE:01" to ADAPTER), HandshakeChoice.parse(listOf("garbage", "\tadapter", "AA:BB:CC:DD:EE:02\tother", "AA:BB:CC:DD:EE:01\tadapter")))
    }
}
