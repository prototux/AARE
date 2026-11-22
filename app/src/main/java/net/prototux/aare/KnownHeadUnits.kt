package net.prototux.aare

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import net.prototux.aare.car.HeadUnitIdentity
import net.prototux.aare.car.QuirkChoice
import net.prototux.aare.car.QuirkSettings
import net.prototux.aare.car.VehicleMatch
import org.json.JSONObject

/**
 * Who each head unit said it was in its last session, by Bluetooth address, on this phone only.
 * Some quirks are needed before a session tells us who the car is: projection doesn't start
 * wireless projection with head units known not to support it, looked up the same way (vehicles.json
 * `wireless: false`). Also each head unit's "Car quirks" page choices. Forgetting a car or its
 * certificate forgets it here too, and only the [MAX] most recently seen head units are kept.
 */
class KnownHeadUnits(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("known_head_units", Context.MODE_PRIVATE)

    fun get(address: String): HeadUnitIdentity? = prefs.getString(key(address), null)?.let { decode(it) }

    /** [carName]: the name the car list shows for it (Settings.knownCars), so that forgetting the car forgets this too. */
    fun put(address: String, id: HeadUnitIdentity, carName: String) {
        if (address.isBlank()) return
        prefs.edit { putString(key(address), encode(id)); putString(key(address) + CAR, carName); seen(address) }
        trim()
    }

    /** A head unit known only by [address] (no Bluetooth address of its own: USB, the developer server), for the "Car quirks" page. */
    fun name(address: String, carName: String) {
        if (address.isBlank()) return
        prefs.edit { putString(key(address) + CAR, carName); seen(address) }
        trim()
    }

    /** The car list's name for [address], or null when it isn't known (never seen, forgotten). */
    fun carName(address: String): String? = prefs.getString(key(address) + CAR, null)

    /** [address]'s "Car quirks" choices: every one [QuirkChoice.AUTOMATIC] unless the user changed it. */
    fun quirks(address: String): QuirkSettings =
        QuirkSettings.of { QuirkChoice.of(prefs.getString(key(address) + QUIRK + it, null)) }

    fun setQuirks(address: String, quirks: QuirkSettings) {
        if (address.isBlank()) return
        prefs.edit {
            quirks.byName().forEach { (name, choice) ->
                if (choice == QuirkChoice.AUTOMATIC) remove(key(address) + QUIRK + name) else putString(key(address) + QUIRK + name, choice.pref)
            }
        }
    }

    /** A wireless session to [address] got as far as service discovery once: it does work wirelessly. [carName]: see [put]. */
    fun markWirelessWorked(address: String, carName: String) {
        if (address.isBlank()) return
        prefs.edit { putBoolean(key(address) + WIRELESS_WORKED, true); putString(key(address) + CAR, carName); seen(address) }
        trim()
    }

    /** Everything known about [address] (a trusted certificate forgotten: Bluetooth addresses only mean something here). */
    fun forget(address: String) {
        val k = key(address)
        prefs.edit { prefs.all.keys.filter { it.substringBefore('#') == k }.forEach { remove(it) } }
    }

    /** Every head unit shown in the car list as [carName]. */
    fun forgetCar(carName: String) {
        prefs.all.filter { (k, v) -> k.endsWith(CAR) && v == carName }.keys.forEach { forget(it.substringBefore('#')) }
    }

    private fun SharedPreferences.Editor.seen(address: String) {
        putLong(key(address) + SEEN, System.currentTimeMillis())
    }

    /** Drops the head units seen longest ago beyond [MAX]. */
    private fun trim() {
        val all = prefs.all
        val seen = all.keys.map { it.substringBefore('#') }.distinct().associateWith { (all[it + SEEN] as? Long) ?: 0L }
        val gone = oldest(seen, MAX)
        if (gone.isNotEmpty()) prefs.edit { all.keys.filter { it.substringBefore('#') in gone }.forEach { remove(it) } }
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
        private const val CAR = "#car"
        private const val SEEN = "#seen"
        private const val QUIRK = "#quirk_"
        /** Head units kept: more than a phone meets, few enough to stay a small file. */
        const val MAX = 32

        /** The addresses to drop so that at most [max] stay: those [seen] (address to time) longest ago. */
        fun oldest(seen: Map<String, Long>, max: Int): Set<String> =
            if (seen.size <= max) emptySet() else seen.entries.sortedBy { it.value }.take(seen.size - max).map { it.key }.toSet()

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
