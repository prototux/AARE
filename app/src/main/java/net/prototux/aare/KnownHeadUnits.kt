package net.prototux.aare

import android.content.Context
import androidx.core.content.edit
import net.prototux.aare.car.HeadUnitIdentity
import net.prototux.aare.car.VehicleMatch
import org.json.JSONObject

/**
 * Who each head unit said it was in its last session, by Bluetooth address, on this phone only.
 * Some quirks are needed before a session tells us who the car is: projection doesn't start
 * wireless projection with head units known not to support it, looked up the same way (vehicles.json
 * `wireless: false`).
 */
class KnownHeadUnits(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("known_head_units", Context.MODE_PRIVATE)

    fun get(address: String): HeadUnitIdentity? = prefs.getString(key(address), null)?.let { decode(it) }

    fun put(address: String, id: HeadUnitIdentity) {
        if (address.isBlank()) return
        prefs.edit { putString(key(address), encode(id)) }
    }

    /** A wireless session to [address] got as far as service discovery once: it does work wirelessly. */
    fun markWirelessWorked(address: String) {
        if (address.isBlank() || wirelessWorked(address)) return
        prefs.edit { putBoolean(key(address) + WIRELESS_WORKED, true) }
    }

    fun wirelessWorked(address: String): Boolean = prefs.getBoolean(key(address) + WIRELESS_WORKED, false)

    /**
     * Whether to leave out wireless for [address], given what its known identity resolves to
     * ([deniedByRule]): never for an address a wireless session already worked with, whatever a
     * rule says.
     */
    fun skipWireless(address: String, deniedByRule: (HeadUnitIdentity) -> Boolean): HeadUnitIdentity? =
        skipWireless(wirelessWorked(address), get(address), deniedByRule)

    companion object {
        private const val WIRELESS_WORKED = "#wireless_worked"

        private fun key(address: String) = address.trim().uppercase()

        /** The head unit to leave wireless out for, or null: see the instance [skipWireless]. */
        fun skipWireless(worked: Boolean, known: HeadUnitIdentity?, deniedByRule: (HeadUnitIdentity) -> Boolean): HeadUnitIdentity? =
            if (worked) null else known?.takeIf(deniedByRule)

        fun encode(id: HeadUnitIdentity): String = JSONObject()
            .put("make", id.make).put("model", id.model).put("name", id.name).put("year", id.year)
            .put("headUnitMake", id.headUnitMake).put("headUnitModel", id.headUnitModel).put("headUnitBuild", id.headUnitBuild)
            .apply { id.protocol?.let { (major, minor) -> put("protocol", "$major.$minor") } }
            .toString()

        /** Null for anything unreadable: a quirk is then simply not known before the session. */
        fun decode(json: String): HeadUnitIdentity? = runCatching {
            val o = JSONObject(json)
            HeadUnitIdentity(
                make = o.optString("make"), model = o.optString("model"), name = o.optString("name"), year = o.optString("year"),
                headUnitMake = o.optString("headUnitMake"), headUnitModel = o.optString("headUnitModel"),
                headUnitBuild = o.optString("headUnitBuild"),
                protocol = o.optString("protocol").takeIf { it.isNotEmpty() }?.let { VehicleMatch.version(it) },
            )
        }.getOrNull()
    }
}
