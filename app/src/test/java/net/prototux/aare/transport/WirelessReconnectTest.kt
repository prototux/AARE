package net.prototux.aare.transport

import net.prototux.aare.aap.AapSession.State
import net.prototux.aare.transport.wireless.WirelessReconnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WirelessReconnectTest {
    private val drop = IOException("read failed")

    @Test
    fun onlyAFailureOnTheCarScreenIsADrop() {
        assertTrue(WirelessReconnect.linkDropped(State.RUNNING, drop, reconnecting = false, refused = false))
        assertFalse("a bye-bye from either side", WirelessReconnect.linkDropped(State.RUNNING, null, reconnecting = false, refused = false))
        assertFalse("never reached the car screen", WirelessReconnect.linkDropped(State.HANDSHAKE, drop, reconnecting = false, refused = false))
        assertFalse(WirelessReconnect.linkDropped(State.DISCOVERY, drop, reconnecting = false, refused = false))
    }

    @Test
    fun whileReconnectingAnEarlyFailureCountsButARefusalDoesNot() {
        assertTrue(WirelessReconnect.linkDropped(State.HANDSHAKE, drop, reconnecting = true, refused = false))
        assertFalse(WirelessReconnect.linkDropped(State.HANDSHAKE, drop, reconnecting = true, refused = true))
        assertFalse(WirelessReconnect.linkDropped(State.RUNNING, null, reconnecting = true, refused = false))
    }

    @Test
    fun reconnectsOnlyWhileTheCarIsStillThere() {
        assertTrue(WirelessReconnect.shouldReconnect(linkDropped = true, userEnded = false, wirelessEnabled = true, bonded = true, bluetoothDisconnected = false))
        assertFalse(WirelessReconnect.shouldReconnect(linkDropped = false, userEnded = false, wirelessEnabled = true, bonded = true, bluetoothDisconnected = false))
        assertFalse(WirelessReconnect.shouldReconnect(linkDropped = true, userEnded = true, wirelessEnabled = true, bonded = true, bluetoothDisconnected = false))
        assertFalse(WirelessReconnect.shouldReconnect(linkDropped = true, userEnded = false, wirelessEnabled = false, bonded = true, bluetoothDisconnected = false))
        assertFalse("unpaired", WirelessReconnect.shouldReconnect(linkDropped = true, userEnded = false, wirelessEnabled = true, bonded = false, bluetoothDisconnected = false))
        assertFalse("ignition off", WirelessReconnect.shouldReconnect(linkDropped = true, userEnded = false, wirelessEnabled = true, bonded = true, bluetoothDisconnected = true))
    }

    @Test
    fun bluetoothDisconnectsAroundTheDropEndIt() {
        val droppedAt = 100_000L
        assertFalse("never disconnected", WirelessReconnect.bluetoothWentDown(null, droppedAt))
        assertTrue("just before the drop", WirelessReconnect.bluetoothWentDown(droppedAt - 5_000, droppedAt))
        assertTrue("after it", WirelessReconnect.bluetoothWentDown(droppedAt + 9_000, droppedAt))
        assertFalse("an adapter that hung up its Bluetooth after the bootstrap",
            WirelessReconnect.bluetoothWentDown(droppedAt - WirelessReconnect.BLUETOOTH_DROP_WINDOW_MS - 1, droppedAt))
    }

    @Test
    fun fiveAttemptsWithGrowingWaits() {
        assertEquals(listOf(3_000L, 6_000L, 9_000L, 12_000L, 15_000L), (1..5).map { WirelessReconnect.delayBefore(it) })
        assertNull(WirelessReconnect.delayBefore(6))
        assertNull(WirelessReconnect.delayBefore(0))
    }

    @Test
    fun aSessionThatRanAWhileStartsAFreshBudget() {
        assertEquals(0, WirelessReconnect.usedAfter(sessionMs = 5 * 60_000, used = 3))
        assertEquals("a link that drops again at once keeps counting", 3, WirelessReconnect.usedAfter(sessionMs = 10_000, used = 3))
        assertEquals(0, WirelessReconnect.usedAfter(sessionMs = 10_000, used = 0))
    }
}
