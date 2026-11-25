package net.prototux.aare.aap.wireless

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpThrottleTest {
    private val throttle = SdpThrottle()

    @Test
    fun sdpQueryThrottledForCarNativeBluetooth() {
        val t0 = 1_000_000_000L
        val oneHourLater = t0 + 60 * 60 * 1_000L
        val twentyFiveHoursLater = t0 + 25 * 60 * 60 * 1_000L

        // Unbonded or already known AA head unit never triggers SDP.
        assertFalse(throttle.shouldQuery(bonded = false, hasAaUuid = false, cachedUuidsEmpty = true, lastQueriedAtMs = null, nowMs = t0))
        assertFalse(throttle.shouldQuery(bonded = true, hasAaUuid = true, cachedUuidsEmpty = false, lastQueriedAtMs = null, nowMs = t0))

        // Bonded device with null/empty cached UUIDs is queried.
        assertTrue(throttle.shouldQuery(bonded = true, hasAaUuid = false, cachedUuidsEmpty = true, lastQueriedAtMs = t0, nowMs = oneHourLater))

        // Car's native BT (non-empty cached UUIDs without AA_UUID): queried once initially, then skipped for 24 h.
        assertTrue(throttle.shouldQuery(bonded = true, hasAaUuid = false, cachedUuidsEmpty = false, lastQueriedAtMs = null, nowMs = t0))
        assertFalse(throttle.shouldQuery(bonded = true, hasAaUuid = false, cachedUuidsEmpty = false, lastQueriedAtMs = t0, nowMs = oneHourLater))
        assertTrue(throttle.shouldQuery(bonded = true, hasAaUuid = false, cachedUuidsEmpty = false, lastQueriedAtMs = t0, nowMs = twentyFiveHoursLater))
    }
}
