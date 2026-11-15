package net.prototux.aare.aap.wireless

/**
 * When to refresh a bonded Bluetooth device's services over SDP while looking for wireless
 * Android Auto. Kept free of Android framework classes so it can be unit-tested on the JVM.
 */
class SdpThrottle(
    /** Minimum interval between automatic SDP refreshes for the same bonded MAC address. */
    val ttlMs: Long = DEFAULT_TTL_MS,
) {
    /**
     * Whether to call `fetchUuidsWithSdp()` on a connecting/probed Bluetooth device.
     *
     * Avoids hammering the car's native hands-free Bluetooth (e.g. Mazda Connect HFP/A2DP) with
     * SDP discovery on every ignition cycle:
     * - Never queries unbonded devices or devices that already advertise the AA wireless UUID.
     * - Queries devices whose cached UUIDs are null/empty, or at most once per [ttlMs] (~24 h).
     */
    fun shouldQuery(
        bonded: Boolean,
        hasAaUuid: Boolean,
        cachedUuidsEmpty: Boolean,
        lastQueriedAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (!bonded || hasAaUuid) return false
        if (cachedUuidsEmpty) return true
        return lastQueriedAtMs == null || nowMs - lastQueriedAtMs >= ttlMs
    }

    companion object {
        const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1_000L
    }
}
