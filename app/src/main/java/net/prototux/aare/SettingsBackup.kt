package net.prototux.aare

import android.content.Context
import androidx.core.content.edit
import net.prototux.aare.car.VehicleOverrides
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AARE's settings in one JSON file, to keep or to move to another phone: the settings, the trusted
 * head units (their certificate fingerprints) and the vehicle rules. Not what belongs to this phone
 * only: its id for head units, the update check, which permissions setup asked for, the certificate
 * in use. Certificates themselves stay out: they hold private keys.
 */
object SettingsBackup {
    private const val FORMAT = 1
    /** The preference files kept, by name. */
    private val FILES = listOf("settings", "head_unit_pins")

    /** Keys of [FILES] that belong to this phone only. */
    internal fun keeps(file: String, key: String): Boolean = when (file) {
        "settings" -> key !in DEVICE_KEYS && !key.startsWith("update_")
        else -> true
    }

    // This phone's Bluetooth address (sent to cars for wireless) and whether this phone has root
    // set up for audio belong to it, like its id.
    private val DEVICE_KEYS = setOf(
        "instance_id", "requested_permissions", "cert_index", "phone_bt_address", "root_audio",
        "cert_update_last_check_ms", "cert_expiry_notified", "cert_expiry_dismissed",
    )

    fun export(context: Context): String {
        val prefs = JSONObject()
        for (name in FILES) {
            val values = JSONObject()
            for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (!keeps(name, key) || value == null) continue
                values.put(key, JSONObject().put("type", typeOf(value) ?: continue).put("value", if (value is Set<*>) JSONArray(value.toList()) else value))
            }
            prefs.put(name, values)
        }
        val vehicles = vehiclesFile(context)?.takeIf { it.isFile }?.readText()
        return JSONObject().put("app", "AARE").put("format", FORMAT).put("prefs", prefs)
            .apply { if (vehicles != null) put("vehicles", vehicles) }.toString(2)
    }

    /** Replaces the kept settings with those in [json]. Throws [IllegalArgumentException] for a file that isn't one. */
    fun import(context: Context, json: String) {
        val root = runCatching { JSONObject(json) }.getOrNull()
        require(root != null && root.optString("app") == "AARE" && root.optInt("format") in 1..FORMAT) { "not an AARE settings file" }
        val prefs = root.getJSONObject("prefs")
        for (name in FILES) {
            val values = prefs.optJSONObject(name) ?: continue
            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            sp.edit {
                // What this file keeps is replaced; what belongs to this phone stays.
                sp.all.keys.filter { keeps(name, it) }.forEach(::remove)
                for (key in values.keys()) {
                    if (!keeps(name, key)) continue
                    val entry = values.getJSONObject(key)
                    when (entry.getString("type")) {
                        "boolean" -> putBoolean(key, entry.getBoolean("value"))
                        "int" -> putInt(key, entry.getInt("value"))
                        "long" -> putLong(key, entry.getLong("value"))
                        "float" -> putFloat(key, entry.getDouble("value").toFloat())
                        "string" -> putString(key, entry.getString("value"))
                        "set" -> putStringSet(key, entry.getJSONArray("value").let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } })
                    }
                }
            }
        }
        root.optString("vehicles").takeIf { it.isNotBlank() }?.let { text -> vehiclesFile(context)?.apply { parentFile?.mkdirs() }?.writeText(text) }
    }

    private fun typeOf(value: Any): String? = when (value) {
        is Boolean -> "boolean"
        is Int -> "int"
        is Long -> "long"
        is Float -> "float"
        is String -> "string"
        is Set<*> -> "set"
        else -> null
    }

    /** Null while the app's external storage isn't available. */
    private fun vehiclesFile(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, VehicleOverrides.FILE) }
}
