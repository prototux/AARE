package net.prototux.aare

import org.json.JSONArray
import org.json.JSONObject

/**
 * AARE's settings (Settings.kt's SharedPreferences) as JSON, for exporting to a file and
 * importing it back. Pure mapping, with no SharedPreferences or Context of its own, so it's
 * tested directly; MainActivity does the actual file picking (SAF) and overwrite confirmation.
 *
 * Each entry keeps its SharedPreferences type (string, boolean, int, long, float or a string set)
 * tagged explicitly, because the exported JSON text has no int/long/float distinction of its own:
 * re-parsing `"180"` without a tag wouldn't say whether it came from `putInt` or `putLong`.
 */
object SettingsBackup {
    const val FORMAT_VERSION = 1

    /**
     * Keys never exported or imported: device- or secret-specific, and must not move between
     * phones. Trusted head-unit certificates (PinnedHeadUnits) live in a different preferences
     * file entirely and are never read here in the first place.
     */
    val EXCLUDED_KEYS = setOf(
        "instance_id", // this phone's own random id, which head units learn to recognise it by
        "update_latest_version", "update_latest_url", "update_last_check_ms", "update_dismissed_version", // update-check cache
        "cert_update_last_check_ms", "cert_expiry_notified", "cert_expiry_dismissed", "cert_index", // certificate-update cache
        "phone_bt_address", // this phone's own Bluetooth MAC
        "known_cars", // connection history (names and times), not a setting
        "requested_permissions", // which runtime permissions this install has already asked for
    )

    /** [all]: a `SharedPreferences.getAll()` map. The file to export, without [EXCLUDED_KEYS]. */
    fun export(all: Map<String, Any?>): JSONObject {
        val settings = JSONObject()
        for ((key, value) in all) {
            if (key in EXCLUDED_KEYS || value == null) continue
            encode(value)?.let { settings.put(key, it) }
        }
        return JSONObject().put(FORMAT_KEY, FORMAT_VERSION).put(SETTINGS_KEY, settings)
    }

    /**
     * The reverse of [export]: the values to write, by key ([EXCLUDED_KEYS] dropped even if
     * present, e.g. from a hand-edited file). Throws [IllegalArgumentException] on a file this
     * version can't read; the caller should confirm the overwrite before calling this either way.
     */
    fun import(json: JSONObject): Map<String, Any> {
        val version = json.optInt(FORMAT_KEY, -1)
        require(version in 1..FORMAT_VERSION) { "unsupported settings file ($FORMAT_KEY=$version)" }
        val settings = json.optJSONObject(SETTINGS_KEY) ?: JSONObject()
        val result = LinkedHashMap<String, Any>()
        for (key in settings.keys()) {
            if (key in EXCLUDED_KEYS) continue
            decode(settings.get(key))?.let { result[key] = it }
        }
        return result
    }

    private const val FORMAT_KEY = "aareSettings"
    private const val SETTINGS_KEY = "settings"
    private const val TYPE_KEY = "t"
    private const val VALUE_KEY = "v"

    private fun encode(value: Any): JSONObject? = when (value) {
        is String -> tagged("s", value)
        is Boolean -> tagged("b", value)
        is Int -> tagged("i", value)
        is Long -> tagged("l", value)
        is Float -> tagged("f", value.toDouble())
        is Set<*> -> tagged("set", JSONArray(value.toList()))
        else -> null // an unknown SharedPreferences type: skip rather than guess at its shape
    }

    private fun tagged(type: String, value: Any): JSONObject = JSONObject().put(TYPE_KEY, type).put(VALUE_KEY, value)

    private fun decode(tagged: Any): Any? {
        if (tagged !is JSONObject) return null
        val value = tagged.opt(VALUE_KEY) ?: return null
        return when (tagged.optString(TYPE_KEY)) {
            "s" -> value as? String
            "b" -> value as? Boolean
            "i" -> (value as? Number)?.toInt()
            "l" -> (value as? Number)?.toLong()
            "f" -> (value as? Number)?.toFloat()
            "set" -> (value as? JSONArray)?.let { arr -> (0 until arr.length()).mapTo(LinkedHashSet()) { arr.getString(it) } }
            else -> null
        }
    }
}
