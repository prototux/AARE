package net.prototux.aare.transport.wireless

import net.prototux.aare.aap.wireless.WirelessHandshake

/**
 * Which [WirelessHandshake] to use with a wireless head unit (the "Wireless compatibility"
 * setting): built-in head units and aftermarket adapters each refuse the other's order, and
 * nothing in the Bluetooth link says which one is on the other end.
 *
 * Auto uses what worked last time with that Bluetooth address, else guesses from its name, and
 * tries the other order once when the first one fails while negotiating.
 */
internal object HandshakeChoice {
    const val AUTO = "auto"
    const val BUILT_IN = "builtin"
    const val ADAPTER = "adapter"

    /**
     * Lower-case fragments of the Bluetooth names adapters ship with, compared with the name's
     * letters and digits only ("AA-Wireless 1234" -> "aawireless1234").
     */
    private val ADAPTER_NAME_FRAGMENTS = listOf(
        "aawireless", "wirelessaa", "carlinkit", "autokit", "ottocast", "aibox", "cpc200",
        "motorolama1", "dongle", "wirelessadapter",
    )

    /** Short adapter model names that only count as a whole word ("MA1-ABCD", not "Cinema1"). */
    private val ADAPTER_NAME_WORDS = setOf("ma1")

    /** Whether a Bluetooth [name] looks like an aftermarket wireless adapter's. */
    fun looksLikeAdapter(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        val squashed = lower.filter { it.isLetterOrDigit() }
        if (ADAPTER_NAME_FRAGMENTS.any { it in squashed }) return true
        return lower.split(Regex("[^a-z0-9]+")).any { it in ADAPTER_NAME_WORDS }
    }

    /** The order to start with, for the [setting] and what [remembered] for this address. */
    fun first(setting: String, remembered: WirelessHandshake?, name: String?): WirelessHandshake = when (setting) {
        BUILT_IN -> WirelessHandshake.BUILT_IN
        ADAPTER -> WirelessHandshake.ADAPTER
        else -> remembered ?: if (looksLikeAdapter(name)) WirelessHandshake.ADAPTER else WirelessHandshake.BUILT_IN
    }

    /**
     * The order to try at once after [failed] did not work, or null to go on as without this
     * setting. Only in Auto, only for a failure while negotiating (the head unit hung up or went
     * silent before telling us its network, which is how either kind refuses the other's order),
     * and once per connection ([alreadySwitched]).
     */
    fun switchAfterFailure(setting: String, failed: WirelessHandshake, whileNegotiating: Boolean, alreadySwitched: Boolean): WirelessHandshake? =
        if (setting == AUTO && whileNegotiating && !alreadySwitched) failed.other else null

    // ---- what worked per Bluetooth address, stored as "AA:BB:CC:DD:EE:FF\tadapter" lines ----

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
