package net.prototux.aare.transport

import net.prototux.aare.aap.wireless.WirelessHandshake.ADAPTER
import net.prototux.aare.aap.wireless.WirelessHandshake.BUILT_IN
import net.prototux.aare.transport.wireless.HandshakeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeChoiceTest {
    @Test
    fun autoUsesTheStandardOrderUnlessTheAdapterOrderWorkedWithThisCar() {
        assertEquals(BUILT_IN, HandshakeChoice.pick(HandshakeChoice.AUTO, null))
        assertEquals(BUILT_IN, HandshakeChoice.pick(HandshakeChoice.AUTO, BUILT_IN))
        // only remembered after the driver chose Adapter for this car
        assertEquals(ADAPTER, HandshakeChoice.pick(HandshakeChoice.AUTO, ADAPTER))
    }

    @Test
    fun aFixedSettingIgnoresMemory() {
        assertEquals(BUILT_IN, HandshakeChoice.pick(HandshakeChoice.BUILT_IN, ADAPTER))
        assertEquals(ADAPTER, HandshakeChoice.pick(HandshakeChoice.ADAPTER, BUILT_IN))
        assertEquals(ADAPTER, HandshakeChoice.pick(HandshakeChoice.ADAPTER, null))
    }

    @Test
    fun anUnknownSettingIsAuto() {
        assertEquals(BUILT_IN, HandshakeChoice.pick("something", null))
        assertEquals(ADAPTER, HandshakeChoice.pick("something", ADAPTER))
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
