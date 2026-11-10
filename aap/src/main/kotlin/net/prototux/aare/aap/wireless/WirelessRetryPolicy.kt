package net.prototux.aare.aap.wireless

/**
 * Pure retry, event-restart, and SDP-throttling rules for wireless Android Auto bootstrap.
 *
 * Kept free of Android framework classes so the state transitions and backoff schedule can be
 * unit-tested directly on the JVM.
 */
class WirelessRetryPolicy(
    /** Backoff delays between attempts; total attempts = `backoffMs.size + 1` (~30 s total wait). */
    val backoffMs: LongArray = DEFAULT_BACKOFF_MS,
    /** Minimum interval between automatic SDP refreshes for the same bonded MAC address. */
    val sdpTtlMs: Long = DEFAULT_SDP_TTL_MS,
) {
    /** Where the current wireless connection attempt is. */
    enum class Stage {
        IDLE,
        RFCOMM,
        WPP_NEGOTIATING,
        WIFI_JOINING,
        TCP_CONNECTING,
        BACKOFF,
    }

    /** Bluetooth broadcasts that can arrive while a wireless attempt is running. */
    enum class BtEvent {
        ACL_CONNECTED,
        ACTION_UUID,
        ACL_DISCONNECTED,
    }

    /** What the running wireless attempt should do when a [BtEvent] arrives. */
    enum class EventAction {
        /** Unrelated device, or an ACL drop after WPP already handed off to Wi-Fi/TCP. */
        IGNORE,
        /** Target head unit re-announced itself over Bluetooth: abort stuck attempt and restart from attempt 1. */
        ABORT_AND_RESTART,
        /** Target lost its Bluetooth link during RFCOMM/WPP/backoff: abort immediately and stop. */
        ABORT_AND_STOP,
    }

    sealed interface Decision {
        data class Retry(val nextAttempt: Int, val delayMs: Long) : Decision
        data class GiveUp(val reason: String) : Decision
    }

    /** Total number of attempts (1 initial attempt plus [backoffMs].size retries). */
    val maxAttempts: Int
        get() = backoffMs.size + 1

    /**
     * Checks whether an attempt may start, or why the retry loop must stop now.
     */
    fun beforeAttempt(
        wirelessEnabled: Boolean,
        sessionActive: Boolean,
        missingPermissions: Boolean,
        aclConnected: Boolean,
    ): Decision.GiveUp? = when {
        !wirelessEnabled -> Decision.GiveUp("wireless Android Auto was turned off")
        sessionActive -> Decision.GiveUp("a session is already active")
        missingPermissions -> Decision.GiveUp("missing permissions")
        !aclConnected -> Decision.GiveUp("Bluetooth ACL disconnected")
        else -> null
    }

    /**
     * Decides whether to retry (and how long to wait) or give up after [attempt] (1-based) failed.
     */
    fun afterFailure(
        attempt: Int,
        wirelessEnabled: Boolean,
        sessionActive: Boolean,
        missingPermissions: Boolean,
        aclConnected: Boolean,
    ): Decision {
        beforeAttempt(wirelessEnabled, sessionActive, missingPermissions, aclConnected)?.let { return it }
        if (attempt >= maxAttempts) return Decision.GiveUp("exhausted all $maxAttempts attempts")
        val index = (attempt - 1).coerceIn(0, backoffMs.lastIndex)
        return Decision.Retry(nextAttempt = attempt + 1, delayMs = backoffMs[index])
    }

    /**
     * How an active connection loop for [targetAddress] at [stage] should react to [event] for [eventAddress].
     *
     * Note on [BtEvent.ACL_DISCONNECTED]: some wireless dongles (e.g. AAWireless / aawgd) intentionally
     * drop the Bluetooth link right after sending Wi-Fi credentials in WPP. An ACL disconnect during
     * [Stage.RFCOMM], [Stage.WPP_NEGOTIATING], or [Stage.BACKOFF] aborts the attempt, whereas during
     * [Stage.WIFI_JOINING] or [Stage.TCP_CONNECTING] the in-flight Wi-Fi/TCP attempt is allowed to finish.
     */
    fun onBtEvent(
        event: BtEvent,
        eventAddress: String,
        targetAddress: String?,
        stage: Stage,
    ): EventAction {
        if (stage == Stage.IDLE || targetAddress == null || !eventAddress.equals(targetAddress, ignoreCase = true)) {
            return EventAction.IGNORE
        }
        return when (event) {
            BtEvent.ACL_CONNECTED, BtEvent.ACTION_UUID -> EventAction.ABORT_AND_RESTART
            BtEvent.ACL_DISCONNECTED -> when (stage) {
                Stage.RFCOMM, Stage.WPP_NEGOTIATING, Stage.BACKOFF -> EventAction.ABORT_AND_STOP
                Stage.WIFI_JOINING, Stage.TCP_CONNECTING, Stage.IDLE -> EventAction.IGNORE
            }
        }
    }

    /**
     * Whether to call `fetchUuidsWithSdp()` on a connecting/probed Bluetooth device.
     *
     * Avoids hammering the car's native hands-free Bluetooth (e.g. Mazda Connect HFP/A2DP) with
     * SDP discovery on every ignition cycle:
     * - Never queries unbonded devices or devices that already advertise the AA wireless UUID.
     * - Queries devices whose cached UUIDs are null/empty, or at most once per [ttlMs] (~24 h).
     */
    fun shouldQuerySdp(
        bonded: Boolean,
        hasAaUuid: Boolean,
        cachedUuidsEmpty: Boolean,
        lastQueriedAtMs: Long?,
        nowMs: Long,
        ttlMs: Long = sdpTtlMs,
    ): Boolean {
        if (!bonded || hasAaUuid) return false
        if (cachedUuidsEmpty) return true
        return lastQueriedAtMs == null || nowMs - lastQueriedAtMs >= ttlMs
    }

    companion object {
        /** 0.5 s, 1.5 s, 3 s, 5 s, 8 s, 12 s = 30.0 s total backoff across 7 total attempts. */
        val DEFAULT_BACKOFF_MS = longArrayOf(500L, 1_500L, 3_000L, 5_000L, 8_000L, 12_000L)
        const val DEFAULT_SDP_TTL_MS = 24L * 60L * 60L * 1_000L
    }
}
