package net.prototux.aare

import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * AARE's settings (Settings.kt's SharedPreferences) as JSON, for exporting to a file and
 * importing it back. Pure mapping, with no SharedPreferences or Context of its own, so it's
 * tested directly; MainActivity does the actual file picking (SAF) and overwrite confirmation.
 *
 * Only the keys in [ALLOWED] move, each with the SharedPreferences type Settings.kt reads it
 * with. A file is not trusted: an entry with another key or another type is skipped, because
 * writing an Int where Settings calls getBoolean would throw ClassCastException on every launch.
 *
 * Each entry keeps its type tagged explicitly, because the exported JSON text has no
 * int/long/float distinction of its own: re-parsing `"180"` without a tag wouldn't say whether it
 * came from `putInt` or `putLong`.
 */
object SettingsBackup {
    const val FORMAT_VERSION = 1

    /** Larger than any real export by far; anything bigger isn't one, so it isn't read into memory. */
    const val MAX_FILE_BYTES = 1024 * 1024

    enum class Type(val tag: String) { STRING("s"), BOOLEAN("b"), INT("i"), LONG("l"), FLOAT("f"), STRING_SET("set") }

    /**
     * The settings that may be exported and imported, with the type Settings.kt reads each one
     * with. A setting added to Settings.kt stays out until it's added here: before adding one,
     * check it isn't in [BLOCKED], device-specific, or a secret (home-automation servers, MQTT
     * hosts and users, tokens and passwords never go in a file the user may share).
     */
    val ALLOWED: Map<String, Type> = mapOf(
        "verbose_protocol_log" to Type.BOOLEAN,
        "wireless" to Type.BOOLEAN,
        "check_updates" to Type.BOOLEAN,
        "max_video_height" to Type.INT,
        "prefer_60fps" to Type.BOOLEAN,
        "force_h264" to Type.BOOLEAN,
        "constant_bitrate_video" to Type.BOOLEAN,
        "calls_on_phone_speaker" to Type.BOOLEAN,
        "resume_after_call" to Type.BOOLEAN,
        "rail_position" to Type.STRING,
        "rail_right" to Type.BOOLEAN, // before rail_position; Settings still reads it when that's unset
        "style_theme" to Type.STRING,
        "style_layout" to Type.STRING,
        "car_style" to Type.STRING, // before style_theme/style_layout, same
        "style_accent" to Type.STRING,
        "show_weather" to Type.BOOLEAN,
        "wallpaper" to Type.STRING,
        "dark_bar" to Type.BOOLEAN,
        "dashboard_cards" to Type.STRING,
        "dashboard_media_first" to Type.BOOLEAN, // before dashboard_cards, same
        "show_calendar" to Type.BOOLEAN, // before dashboard_cards, same
        "car_theme" to Type.STRING,
        "car_audio" to Type.BOOLEAN,
        "car_audio_full_volume" to Type.BOOLEAN,
        "assistant_pkg" to Type.STRING,
        "assistant_stop" to Type.STRING,
        "phone_screen" to Type.BOOLEAN,
        "standalone_keep_screen_awake" to Type.BOOLEAN,
        "standalone_app_pkgs" to Type.STRING_SET,
        "browser" to Type.BOOLEAN,
        "browser_url" to Type.STRING,
        "car_data_app" to Type.BOOLEAN,
        "call_bar_x" to Type.FLOAT,
        "call_bar_y" to Type.FLOAT,
        "car_gps" to Type.BOOLEAN,
        "resume_media" to Type.BOOLEAN,
        "pause_while_recording" to Type.BOOLEAN,
        "message_banners" to Type.BOOLEAN,
        "group_messages" to Type.BOOLEAN,
        "muted_message_apps" to Type.STRING_SET,
        "dnd_driving" to Type.BOOLEAN,
        "auto_reply" to Type.BOOLEAN,
        "auto_reply_text" to Type.STRING,
        "read_messages" to Type.BOOLEAN,
        "voice_reply" to Type.BOOLEAN,
        "last_nav" to Type.STRING,
        "last_media" to Type.STRING,
        "app_order" to Type.STRING,
        "hidden_apps" to Type.STRING_SET,
        "rail_apps" to Type.STRING,
        "compact_rail" to Type.BOOLEAN,
        "rail_widgets" to Type.STRING,
        "split_screen" to Type.BOOLEAN,
        "car_text_size" to Type.STRING,
        "car_control_size" to Type.STRING,
        "car_chrome" to Type.STRING,
        "night_source" to Type.STRING,
        "dashboard_map_big" to Type.BOOLEAN,
        "calendar_agenda_count" to Type.INT,
        "car_key_map" to Type.STRING_SET,
    )

    /**
     * Settings that are never exported or imported, even though they're ordinary settings: each
     * one opens AARE to the network or another app, or skips a safety check or a consent, so it
     * must be switched on by hand on this phone, never by a file. Not in [ALLOWED]; listed here so
     * that stays deliberate (a test checks the two never overlap).
     */
    val BLOCKED: Set<String> = setOf(
        "hu_server", // developer head unit server, unauthenticated
        "hu_server_lan", // the same server on the network (TCP 5277, no authentication)
        "hu_server_lan_once",
        "head_unit_server_mirror", // the phone's screen to an unauthenticated server peer
        "hu_server_localhost", // the server to any app on the phone
        "ignore_driving", // passenger mode: lifts the driving restrictions
        "root_audio", // capture through su, granted in the audio settings
        "standalone_apps", // phone apps on the car screen through root or Shizuku, behind a warning
        "assistant_links", // lets another app act on the car
        "assistant_on_car_screen", // the next five work through root or Shizuku
        "media_video",
        "car_mic_for_phone_apps",
        "auto_pair_car_bluetooth",
        "location_on_in_car", // switches the phone's location through Shizuku or root
        "location_off_after_car", // same
        "companion_wifi_shizuku",
        "standalone_driving_rules", // which phone apps show while driving
        "cert_auto_update", // a daily request to a third-party repository, replacing the certificate
    )

    /** [all]: a `SharedPreferences.getAll()` map. The file to export: [ALLOWED] keys only. */
    fun export(all: Map<String, Any?>): JSONObject {
        val settings = JSONObject()
        for ((key, value) in all) {
            val type = ALLOWED[key] ?: continue
            if (value == null) continue
            encode(type, value)?.let { settings.put(key, it) }
        }
        return JSONObject().put(FORMAT_KEY, FORMAT_VERSION).put(SETTINGS_KEY, settings)
    }

    /**
     * What a file gives: the [values] to write, by key; the keys [skipped] as unknown or of the
     * wrong type; and the [BLOCKED] keys it carried, never applied ([blocked], told to the user).
     */
    data class Imported(val values: Map<String, Any>, val skipped: List<String>, val blocked: List<String> = emptyList())

    /**
     * The reverse of [export]. Throws [IllegalArgumentException] on a file this version can't
     * read; the caller should confirm the overwrite before calling this either way.
     */
    fun import(json: JSONObject): Imported {
        val version = json.optInt(FORMAT_KEY, -1)
        require(version in 1..FORMAT_VERSION) { "unsupported settings file ($FORMAT_KEY=$version)" }
        val settings = json.optJSONObject(SETTINGS_KEY) ?: JSONObject()
        val values = LinkedHashMap<String, Any>()
        val skipped = ArrayList<String>()
        val blocked = ArrayList<String>()
        for (key in settings.keys()) {
            // Whatever a file says (a hand-edited one, or one from a later version that exports them).
            if (key in BLOCKED) { blocked += key; continue }
            val type = ALLOWED[key]
            val value = type?.let { decode(it, settings.get(key)) }
            // The photo behind "custom" isn't in the file, so it would leave the dashboard blank.
            if (value == null || (key == "wallpaper" && value == Settings.WALLPAPER_CUSTOM)) skipped += key
            else values[key] = value
        }
        return Imported(values, skipped, blocked)
    }

    /** All of [input], or [IllegalArgumentException] as soon as it passes [limit] bytes (so a huge file is never read whole). */
    fun readLimited(input: InputStream, limit: Int = MAX_FILE_BYTES): ByteArray {
        // By hand: InputStream.readNBytes is API 33, and minSdk is 26.
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            require(out.size() <= limit) { "settings file larger than $limit bytes" }
        }
    }

    private const val FORMAT_KEY = "aareSettings"
    private const val SETTINGS_KEY = "settings"
    private const val TYPE_KEY = "t"
    private const val VALUE_KEY = "v"

    private fun encode(type: Type, value: Any): JSONObject? = when {
        type == Type.STRING && value is String -> tagged(type, value)
        type == Type.BOOLEAN && value is Boolean -> tagged(type, value)
        type == Type.INT && value is Int -> tagged(type, value)
        type == Type.LONG && value is Long -> tagged(type, value)
        type == Type.FLOAT && value is Float -> tagged(type, value.toDouble())
        type == Type.STRING_SET && value is Set<*> && value.all { it is String } -> tagged(type, JSONArray(value.toList()))
        else -> null // stored with another type than Settings reads it with: nothing sane to export
    }

    private fun tagged(type: Type, value: Any): JSONObject = JSONObject().put(TYPE_KEY, type.tag).put(VALUE_KEY, value)

    /** [tagged]'s value if it's tagged [type] and holds one exactly (no rounding, no string-to-number). */
    private fun decode(type: Type, tagged: Any): Any? {
        if (tagged !is JSONObject || tagged.optString(TYPE_KEY) != type.tag) return null
        val value = tagged.opt(VALUE_KEY) ?: return null
        return when (type) {
            Type.STRING -> value as? String
            Type.BOOLEAN -> value as? Boolean
            Type.INT -> when (value) {
                is Int -> value
                is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                else -> null
            }
            Type.LONG -> (value as? Int)?.toLong() ?: value as? Long
            Type.FLOAT -> (value as? Number)?.toFloat()?.takeIf { it.isFinite() }
            Type.STRING_SET -> (value as? JSONArray)?.let { arr ->
                val items = (0 until arr.length()).map { arr.opt(it) }
                if (items.all { it is String }) items.mapTo(LinkedHashSet()) { it as String } else null
            }
        }
    }
}
