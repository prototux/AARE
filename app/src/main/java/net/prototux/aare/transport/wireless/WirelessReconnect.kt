package net.prototux.aare.transport.wireless

import net.prototux.aare.aap.AapSession

/**
 * Reconnecting a wireless session whose link dropped mid-drive (Wi-Fi interference, the phone
 * roaming off the car's network, a head unit that reset its Wi-Fi). Only a session that had
 * reached the car screen and ended without a bye-bye from either side: Disconnect on the phone,
 * the car's own bye-bye and the phone's stop all say goodbye first. Turning the ignition off drops
 * the link too, but it also takes the car's Bluetooth down, which ends the reconnecting.
 *
 * Its own budget, apart from the initial connect's "car not ready yet" retries: [ATTEMPTS]
 * attempts, the n-th after n × [STEP_MS].
 */
internal object WirelessReconnect {
    const val ATTEMPTS = 5
    const val STEP_MS = 3_000L
    /** A session that ran this long had recovered: its drop starts a fresh budget. */
    const val BUDGET_RESET_MS = 60_000L

    /**
     * An ignition off takes the car's Bluetooth down within seconds of its Wi-Fi, before or after
     * the phone notices the dropped link: a Bluetooth disconnect this long before the drop, or any
     * time after it, ends the reconnecting. An adapter that hangs up its Bluetooth link right after
     * the bootstrap did so long before.
     */
    const val BLUETOOTH_DROP_WINDOW_MS = 20_000L

    /** Whether the car's Bluetooth disconnected (last at [disconnectedAtMs], null never) in a way that ends reconnecting a link dropped at [droppedAtMs]. */
    fun bluetoothWentDown(disconnectedAtMs: Long?, droppedAtMs: Long): Boolean =
        disconnectedAtMs != null && disconnectedAtMs >= droppedAtMs - BLUETOOTH_DROP_WINDOW_MS

    /**
     * Whether a session's end looks like a dropped link rather than someone ending it: it failed
     * (no bye-bye) after reaching the car screen, or, while reconnecting, at any point except a
     * certificate refusal (which needs the user, not another try).
     */
    fun linkDropped(closedDuring: AapSession.State, error: Throwable?, reconnecting: Boolean, refused: Boolean): Boolean =
        error != null && !refused && (closedDuring == AapSession.State.RUNNING || reconnecting)

    /**
     * Whether to (go on trying to) reconnect: the link dropped, the user didn't end it, wireless is
     * still on, the car is still bonded and its Bluetooth didn't disconnect.
     */
    fun shouldReconnect(
        linkDropped: Boolean,
        userEnded: Boolean,
        wirelessEnabled: Boolean,
        bonded: Boolean,
        bluetoothDisconnected: Boolean,
    ): Boolean = linkDropped && !userEnded && wirelessEnabled && bonded && !bluetoothDisconnected

    /** How long to wait before the [attempt]-th reconnect (1-based), or null when the budget is spent. */
    fun delayBefore(attempt: Int): Long? = if (attempt in 1..ATTEMPTS) STEP_MS * attempt else null

    /** Attempts already used when a session that lasted [sessionMs] drops: none if it had recovered. */
    fun usedAfter(sessionMs: Long, used: Int): Int = if (sessionMs >= BUDGET_RESET_MS) 0 else used
}
