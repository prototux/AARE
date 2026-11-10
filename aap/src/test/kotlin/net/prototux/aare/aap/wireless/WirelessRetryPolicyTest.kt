package net.prototux.aare.aap.wireless

import net.prototux.aare.aap.wireless.WirelessRetryPolicy.BtEvent
import net.prototux.aare.aap.wireless.WirelessRetryPolicy.Decision
import net.prototux.aare.aap.wireless.WirelessRetryPolicy.EventAction
import net.prototux.aare.aap.wireless.WirelessRetryPolicy.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessRetryPolicyTest {
    private val policy = WirelessRetryPolicy()
    private val dongleMac = "AA:BB:CC:11:22:33"
    private val mazdaMac = "DE:AD:BE:EF:00:01"

    @Test
    fun retriesFollowBackoffScheduleAndTotalThirtySeconds() {
        assertEquals(7, policy.maxAttempts)
        assertEquals(30_000L, policy.backoffMs.sum())

        val expectedDelays = listOf(500L, 1_500L, 3_000L, 5_000L, 8_000L, 12_000L)
        for ((index, expectedMs) in expectedDelays.withIndex()) {
            val attempt = index + 1
            val decision = policy.afterFailure(
                attempt = attempt,
                wirelessEnabled = true,
                sessionActive = false,
                missingPermissions = false,
                aclConnected = true,
            )
            assertEquals(Decision.Retry(nextAttempt = attempt + 1, delayMs = expectedMs), decision)
        }

        val finalDecision = policy.afterFailure(
            attempt = policy.maxAttempts,
            wirelessEnabled = true,
            sessionActive = false,
            missingPermissions = false,
            aclConnected = true,
        )
        assertTrue(finalDecision is Decision.GiveUp)
        assertTrue((finalDecision as Decision.GiveUp).reason.contains("exhausted"))
    }

    @Test
    fun stopsImmediatelyWhenAclDisconnectsOrSessionStartsOrWirelessTurnsOff() {
        assertNull(
            policy.beforeAttempt(
                wirelessEnabled = true,
                sessionActive = false,
                missingPermissions = false,
                aclConnected = true,
            )
        )

        val aclGone = policy.afterFailure(
            attempt = 1,
            wirelessEnabled = true,
            sessionActive = false,
            missingPermissions = false,
            aclConnected = false,
        )
        assertEquals(Decision.GiveUp("Bluetooth ACL disconnected"), aclGone)

        val sessionUp = policy.afterFailure(
            attempt = 2,
            wirelessEnabled = true,
            sessionActive = true,
            missingPermissions = false,
            aclConnected = true,
        )
        assertEquals(Decision.GiveUp("a session is already active"), sessionUp)

        val wirelessOff = policy.beforeAttempt(
            wirelessEnabled = false,
            sessionActive = false,
            missingPermissions = false,
            aclConnected = true,
        )
        assertEquals(Decision.GiveUp("wireless Android Auto was turned off"), wirelessOff)

        val noPerms = policy.beforeAttempt(
            wirelessEnabled = true,
            sessionActive = false,
            missingPermissions = true,
            aclConnected = true,
        )
        assertEquals(Decision.GiveUp("missing permissions"), noPerms)
    }

    @Test
    fun freshAclOrUuidForTargetAbortsAndRestarts() {
        for (stage in listOf(Stage.RFCOMM, Stage.WPP_NEGOTIATING, Stage.WIFI_JOINING, Stage.TCP_CONNECTING, Stage.BACKOFF)) {
            assertEquals(
                EventAction.ABORT_AND_RESTART,
                policy.onBtEvent(BtEvent.ACL_CONNECTED, dongleMac.lowercase(), dongleMac, stage),
            )
            assertEquals(
                EventAction.ABORT_AND_RESTART,
                policy.onBtEvent(BtEvent.ACTION_UUID, dongleMac, dongleMac, stage),
            )
        }

        // Events for the car's own Bluetooth (Mazda) or while idle must not disturb the dongle attempt.
        assertEquals(EventAction.IGNORE, policy.onBtEvent(BtEvent.ACL_CONNECTED, mazdaMac, dongleMac, Stage.RFCOMM))
        assertEquals(EventAction.IGNORE, policy.onBtEvent(BtEvent.ACTION_UUID, mazdaMac, dongleMac, Stage.WPP_NEGOTIATING))
        assertEquals(EventAction.IGNORE, policy.onBtEvent(BtEvent.ACL_CONNECTED, dongleMac, dongleMac, Stage.IDLE))
    }

    @Test
    fun aclDisconnectAbortsRfcommAndWppButAllowsWifiAndTcpToFinish() {
        assertEquals(EventAction.ABORT_AND_STOP, policy.onBtEvent(BtEvent.ACL_DISCONNECTED, dongleMac, dongleMac, Stage.RFCOMM))
        assertEquals(EventAction.ABORT_AND_STOP, policy.onBtEvent(BtEvent.ACL_DISCONNECTED, dongleMac, dongleMac, Stage.WPP_NEGOTIATING))
        assertEquals(EventAction.ABORT_AND_STOP, policy.onBtEvent(BtEvent.ACL_DISCONNECTED, dongleMac, dongleMac, Stage.BACKOFF))

        // Dongles often drop BT once WPP hands off to Wi-Fi/TCP.
        assertEquals(EventAction.IGNORE, policy.onBtEvent(BtEvent.ACL_DISCONNECTED, dongleMac, dongleMac, Stage.WIFI_JOINING))
        assertEquals(EventAction.IGNORE, policy.onBtEvent(BtEvent.ACL_DISCONNECTED, dongleMac, dongleMac, Stage.TCP_CONNECTING))
        assertEquals(EventAction.IGNORE, policy.onBtEvent(BtEvent.ACL_DISCONNECTED, mazdaMac, dongleMac, Stage.RFCOMM))
    }

    @Test
    fun sdpQueryThrottledForCarNativeBluetooth() {
        val t0 = 1_000_000_000L
        val oneHourLater = t0 + 60 * 60 * 1_000L
        val twentyFiveHoursLater = t0 + 25 * 60 * 60 * 1_000L

        // Unbonded or already known AA head unit never triggers SDP.
        assertFalse(policy.shouldQuerySdp(bonded = false, hasAaUuid = false, cachedUuidsEmpty = true, lastQueriedAtMs = null, nowMs = t0))
        assertFalse(policy.shouldQuerySdp(bonded = true, hasAaUuid = true, cachedUuidsEmpty = false, lastQueriedAtMs = null, nowMs = t0))

        // Bonded device with null/empty cached UUIDs is queried.
        assertTrue(policy.shouldQuerySdp(bonded = true, hasAaUuid = false, cachedUuidsEmpty = true, lastQueriedAtMs = t0, nowMs = oneHourLater))

        // Car's native BT (non-empty cached UUIDs without AA_UUID): queried once initially, then skipped for 24 h.
        assertTrue(policy.shouldQuerySdp(bonded = true, hasAaUuid = false, cachedUuidsEmpty = false, lastQueriedAtMs = null, nowMs = t0))
        assertFalse(policy.shouldQuerySdp(bonded = true, hasAaUuid = false, cachedUuidsEmpty = false, lastQueriedAtMs = t0, nowMs = oneHourLater))
        assertTrue(policy.shouldQuerySdp(bonded = true, hasAaUuid = false, cachedUuidsEmpty = false, lastQueriedAtMs = t0, nowMs = twentyFiveHoursLater))
    }
}
