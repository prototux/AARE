package net.prototux.aare

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

/** User settings, in one SharedPreferences file. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** User-opt-in route diagnostics, disabled by default and effective only in debug builds.
     * Records road and cue text into a bounded, app-private JSONL file.
     */
    var navigationDiagnostics: Boolean
        get() = prefs.getBoolean("navigation_diagnostics", false)
        set(v) = prefs.edit { putBoolean("navigation_diagnostics", v) }

    /** Serve the developer "head unit server" like Android Auto does, through adb (for DHU/openauto/headunit-revived). */
    var headUnitServer: Boolean
        get() = prefs.getBoolean("hu_server", false)
        set(v) = prefs.edit { putBoolean("hu_server", v) }

    /** Also accept head units from the network (TCP 5277, no authentication). Switched off again after one session if [headUnitServerLanOnce]. */
    var headUnitServerLan: Boolean
        get() = prefs.getBoolean("hu_server_lan", false)
        set(v) = prefs.edit { putBoolean("hu_server_lan", v) }

    /** Switch [headUnitServerLan] off once a network session starts. Off keeps it on, for openauto/Headunit Revived. */
    var headUnitServerLanOnce: Boolean
        get() = prefs.getBoolean("hu_server_lan_once", true)
        set(v) = prefs.edit { putBoolean("hu_server_lan_once", v) }

    /** Offer "Phone screen" on head unit server sessions too: the server's peer is unauthenticated, so off by default. */
    var headUnitServerMirror: Boolean
        get() = prefs.getBoolean("head_unit_server_mirror", false)
        set(v) = prefs.edit { putBoolean("head_unit_server_mirror", v) }

    /** Connect wirelessly to bonded head units that advertise wireless Android Auto. */
    var wireless: Boolean
        get() = prefs.getBoolean("wireless", true)
        set(v) = prefs.edit { putBoolean("wireless", v) }

    /** Largest video height we accept from the head unit's offers (480, 720, 1080, 1440, 2160). */
    var maxVideoHeight: Int
        get() = prefs.getInt("max_video_height", 1080)
        set(v) = prefs.edit { putInt("max_video_height", v) }

    /** Prefer 60 fps configurations when the head unit offers them. */
    var prefer60fps: Boolean
        get() = prefs.getBoolean("prefer_60fps", true)
        set(v) = prefs.edit { putBoolean("prefer_60fps", v) }

    /** Where the car screen's bar goes: next to the driver ([RAIL_AUTO]), [RAIL_LEFT], [RAIL_RIGHT] or [RAIL_BOTTOM]. */
    var railPosition: String
        get() = prefs.getString("rail_position", null)
            ?: if (prefs.contains("rail_right")) (if (prefs.getBoolean("rail_right", false)) RAIL_RIGHT else RAIL_LEFT) else RAIL_AUTO
        set(v) = prefs.edit { putString("rail_position", v); remove("rail_right") }

    /** The car screen's layout: [STYLE_AUTO] (Android Auto's) or [STYLE_CARPLAY] (an easter egg). */
    var carStyle: String
        get() = prefs.getString("car_style", STYLE_AUTO) ?: STYLE_AUTO
        set(v) = prefs.edit { putString("car_style", v) }

    /**
     * Passenger mode: ignore the car's "driving" signal, so a passenger can type a destination or
     * browse long lists while moving. The driver must not use the screen then.
     */
    var ignoreDriving: Boolean
        get() = prefs.getBoolean("ignore_driving", false)
        set(v) = prefs.edit { putBoolean("ignore_driving", v) }

    /** The weather next to the clock (from Open-Meteo, with an approximate position). */
    var showWeather: Boolean
        get() = prefs.getBoolean("show_weather", true)
        set(v) = prefs.edit { putBoolean("show_weather", v) }

    /** The next calendar event on the dashboard. */
    var showCalendar: Boolean
        get() = prefs.getBoolean("show_calendar", true)
        set(v) = prefs.edit { putBoolean("show_calendar", v) }

    /** Behind the dashboard: none, one of the built-in gradients, or [WALLPAPER_CUSTOM] (filesDir/wallpaper.jpg). */
    var wallpaper: String
        get() = prefs.getString("wallpaper", "none") ?: "none"
        set(v) = prefs.edit { putString("wallpaper", v) }

    /** Keep the car screen's bar dark even with day colors. */
    var darkBar: Boolean
        get() = prefs.getBoolean("dark_bar", false)
        set(v) = prefs.edit { putBoolean("dark_bar", v) }

    /** Car screen colors: follow the car's day/night signal ([THEME_AUTO]), or always [THEME_DARK] / [THEME_LIGHT]. */
    var carTheme: String
        get() = prefs.getString("car_theme", THEME_AUTO) ?: THEME_AUTO
        set(v) = prefs.edit { putString("car_theme", v) }

    /** Stream phone audio to the car (needs the screen-capture consent for audio). */
    var carAudio: Boolean
        get() = prefs.getBoolean("car_audio", true)
        set(v) = prefs.edit { putBoolean("car_audio", v) }

    /**
     * Package of the assistant the steering-wheel voice button opens (a car app such as Lapin, or a
     * phone assistant); null = follow the phone's default digital assistant.
     */
    var assistantPackage: String?
        get() = prefs.getString("assistant_pkg", null)
        set(v) = prefs.edit { if (v == null) remove("assistant_pkg") else putString("assistant_pkg", v) }

    /** Offer "Phone screen" in the car: the phone's own screen, for apps AARE can't host (Google Maps, Waze). */
    var phoneScreen: Boolean
        get() = prefs.getBoolean("phone_screen", false)
        set(v) = prefs.edit { putBoolean("phone_screen", v) }

    /** While connected, the car's GPS becomes the phone's location (needs AARE as mock location app). */
    var carGpsForPhone: Boolean
        get() = prefs.getBoolean("car_gps", true)
        set(v) = prefs.edit { putBoolean("car_gps", v) }

    /** Start the last media app playing when the car connects. */
    var resumeMedia: Boolean
        get() = prefs.getBoolean("resume_media", false)
        set(v) = prefs.edit { putBoolean("resume_media", v) }

    /** Show incoming messages as banners on the car screen. */
    var messageBanners: Boolean
        get() = prefs.getBoolean("message_banners", true)
        set(v) = prefs.edit { putBoolean("message_banners", v) }

    /** Also show messages from group conversations. */
    var groupMessages: Boolean
        get() = prefs.getBoolean("group_messages", true)
        set(v) = prefs.edit { putBoolean("group_messages", v) }

    /** Do Not Disturb (priority only) while connected to the car. */
    var dndWhileDriving: Boolean
        get() = prefs.getBoolean("dnd_driving", false)
        set(v) = prefs.edit { putBoolean("dnd_driving", v) }

    /** Answer people who write while connected, once per conversation per drive. */
    var autoReply: Boolean
        get() = prefs.getBoolean("auto_reply", false)
        set(v) = prefs.edit { putBoolean("auto_reply", v) }

    var autoReplyText: String
        get() = prefs.getString("auto_reply_text", null)?.takeIf { it.isNotBlank() } ?: "I'm driving right now, I'll answer later."
        set(v) = prefs.edit { putString("auto_reply_text", v) }

    /** Read incoming messages aloud as they arrive. */
    var readMessagesAloud: Boolean
        get() = prefs.getBoolean("read_messages", false)
        set(v) = prefs.edit { putBoolean("read_messages", v) }

    /** Cars AARE connected to: name to the last connection time (ms). */
    val knownCars: Map<String, Long>
        get() = prefs.getStringSet("known_cars", emptySet()).orEmpty().mapNotNull { e ->
            val i = e.lastIndexOf('\t')
            if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1).toLongOrNull() ?: 0L)
        }.toMap()

    fun rememberCar(name: String) {
        if (name.isBlank()) return
        val cars = knownCars + (name to System.currentTimeMillis())
        prefs.edit { putStringSet("known_cars", cars.map { (n, t) -> "$n\t$t" }.toSet()) }
    }

    fun forgetCar(name: String) {
        prefs.edit { putStringSet("known_cars", (knownCars - name).map { (n, t) -> "$n\t$t" }.toSet()) }
    }

    /** Last navigation and media apps used, restored on the next connection. */
    var lastNavApp: String?
        get() = prefs.getString("last_nav", null)
        set(v) = prefs.edit { putString("last_nav", v) }
    var lastMediaApp: String?
        get() = prefs.getString("last_media", null)
        set(v) = prefs.edit { putString("last_media", v) }

    /** The car launcher's order: app keys (package names, or the built-in apps' ids), first first. */
    var appOrder: List<String>
        get() = prefs.getString("app_order", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = prefs.edit { putString("app_order", v.joinToString("\n")) }

    /** Apps hidden from the car launcher. */
    var hiddenApps: Set<String>
        get() = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet("hidden_apps", v) }

    /** Stable random id sent to head units so they recognise this phone. */
    val instanceId: String
        get() = prefs.getString("instance_id", null) ?: UUID.randomUUID().toString().also { prefs.edit { putString("instance_id", it) } }

    /** Index of the certificate to offer first (rotates after a head unit rejects one). */
    var certificateIndex: Int
        get() = prefs.getInt("cert_index", 0)
        set(v) = prefs.edit { putInt("cert_index", v) }

    companion object {
        const val RAIL_AUTO = "auto"
        const val RAIL_LEFT = "left"
        const val RAIL_RIGHT = "right"
        const val RAIL_BOTTOM = "bottom"
        const val THEME_AUTO = "auto"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val STYLE_AUTO = "auto"
        const val STYLE_CARPLAY = "carplay"
        const val WALLPAPER_CUSTOM = "custom"
        /** The user's wallpaper photo, in the app's files. */
        const val WALLPAPER_FILE = "wallpaper.jpg"
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(l)
    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(l)
}
