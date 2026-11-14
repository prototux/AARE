package net.prototux.aare.notify

import android.content.Context
import android.telephony.TelephonyManager

/** ISO country codes (lowercase) where a received message's text isn't shown, only that one arrived. */
private val TEXT_BLOCKED_COUNTRIES = setOf("hk")

/**
 * Whether message text should be hidden for a device whose current country is [countryIso] (a
 * two-letter ISO code; case-insensitive). Null or blank (unknown) never blocks.
 */
fun isMessageTextBlocked(countryIso: String?): Boolean =
    !countryIso.isNullOrBlank() && countryIso.lowercase() in TEXT_BLOCKED_COUNTRIES

/**
 * The phone's current country from the network it's registered on, falling back to the SIM's
 * home country when that isn't available (airplane mode, no signal); null if neither is. Neither
 * getter needs a runtime permission.
 */
fun currentCountryIso(context: Context): String? {
    val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
    return runCatching { tm.networkCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { tm.simCountryIso }.getOrNull()?.takeIf { it.isNotBlank() }
}
