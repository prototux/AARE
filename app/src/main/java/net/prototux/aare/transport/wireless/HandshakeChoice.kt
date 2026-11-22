package net.prototux.aare.transport.wireless

import net.prototux.aare.aap.wireless.WirelessHandshake

/**
 * Which [WirelessHandshake] to use with a wireless head unit (the "Wireless compatibility"
 * setting): built-in head units and aftermarket adapters each refuse the other's order, and
 * nothing in the Bluetooth link says which one is on the other end.
 *
 * The adapter order is opt-in: only the driver's choice of [ADAPTER] turns it on, never a guess
 * from the Bluetooth name or a failed attempt. Auto uses the standard order, unless this car
 * (Bluetooth address) last connected with the adapter order, which only happens after the driver
 * chose it: so a driver with both kinds of car can set Adapter once, connect the adapter car, and go
 * back to Auto.
 */
internal object HandshakeChoice {
    const val AUTO = "auto"
    const val BUILT_IN = "builtin"
    const val ADAPTER = "adapter"

    /** The order a connection series uses, for the [setting] and what [remembered] for this address. */
    fun pick(setting: String, remembered: WirelessHandshake?): WirelessHandshake = when (setting) {
        BUILT_IN -> WirelessHandshake.BUILT_IN
        ADAPTER -> WirelessHandshake.ADAPTER
        else -> remembered ?: WirelessHandshake.BUILT_IN
    }

    // ---- what worked per Bluetooth address, stored as "AA:BB:CC:DD:EE:FF\tadapter" lines ----
    // TODO: move this per-car memory into KnownHeadUnits once #233 is merged (one place for what
    // AARE knows about each car), and drop the "wireless_handshakes" preference then.

    /** Kept per address; the oldest entries go first beyond this. */
    const val MAX_REMEMBERED = 32

    fun parse(entries: Collection<String>): Map<String, WirelessHandshake> = entries.mapNotNull { e ->
        val address = e.substringBefore('\t', "").trim().uppercase()
        val handshake = when (e.substringAfter('\t', "")) {
            BUILT_IN -> WirelessHandshake.BUILT_IN
            ADAPTER -> WirelessHandshake.ADAPTER
            else -> null
        }
        if (address.isEmpty() || handshake == null) null else address to handshake
    }.toMap()

    /**
     * [entries] (as stored, oldest first) with [address] now using [handshake]: moved to the end,
     * so the oldest are dropped past [MAX_REMEMBERED].
     */
    fun remember(entries: List<String>, address: String, handshake: WirelessHandshake): List<String> {
        val key = address.trim().uppercase()
        val kept = entries.filter { it.substringBefore('\t', "").trim().uppercase() != key }
        return (kept + "$key\t${encode(handshake)}").takeLast(MAX_REMEMBERED)
    }

    fun encode(handshake: WirelessHandshake) = if (handshake == WirelessHandshake.ADAPTER) ADAPTER else BUILT_IN
}
