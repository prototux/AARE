package net.prototux.aare

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import net.prototux.aare.launch.StandaloneDrivingRule
import net.prototux.aare.launch.formatDrivingRules
import net.prototux.aare.launch.parseDrivingRules
import net.prototux.aare.aap.wireless.WirelessHandshake
import net.prototux.aare.transport.wireless.HandshakeChoice
import java.util.UUID
import net.prototux.aare.car.CarKeyMapCodec

/** User settings, in one SharedPreferences file. */
class Settings(context: Context) {
    /** Not the Activity [Settings] may be made with, so an instance kept around does not leak it. */
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Serve the developer "head unit server" like Android Auto does, through adb (for DHU/openauto/headunit-revived). */
    var headUnitServer: Boolean
        get() = prefs.getBoolean("hu_server", false)
        set(v) = prefs.edit { putBoolean("hu_server", v) }

    /** Write the protocol frame trace to the in-app connection log too, to capture it without adb. */
    var verboseProtocolLog: Boolean
        get() = prefs.getBoolean("verbose_protocol_log", false)
        set(v) = prefs.edit { putBoolean("verbose_protocol_log", v) }

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

    /**
     * Accept head-unit connections over loopback TCP ([TcpServer.PORT]) from an Android-Auto-in-the-
     * browser bridge on this phone (webaa, paath — for cars without Android Auto, like Tesla). Any app
     * on the phone can then reach the server, so it is off by default.
     */
    var headUnitServerLocalhost: Boolean
        get() = prefs.getBoolean("hu_server_localhost", false)
        set(v) = prefs.edit { putBoolean("hu_server_localhost", v) }

    /** Connect wirelessly to bonded head units that advertise wireless Android Auto. */
    var wireless: Boolean
        get() = prefs.getBoolean("wireless", true)
        set(v) = prefs.edit { putBoolean("wireless", v) }

    /**
     * For cars associated as companion devices (CarCompanion): have Shizuku approve the car's Wi-Fi
     * network request, so Android's "connect to device" dialog doesn't come back (WifiApproval).
     * The network stays AARE's own app-only one. Off by default.
     */
    var companionWifiShizuku: Boolean
        get() = prefs.getBoolean("companion_wifi_shizuku", false)
        set(v) = prefs.edit { putBoolean("companion_wifi_shizuku", v) }

    /**
     * The wireless bootstrap's order of answers (HandshakeChoice): [HandshakeChoice.AUTO] (the
     * standard order, or the one that last worked with that car), [HandshakeChoice.BUILT_IN] (what
     * AARE always did) or [HandshakeChoice.ADAPTER]. The adapter order is never guessed.
     */
    var wirelessCompatibility: String
        get() = prefs.getString("wireless_compatibility", null) ?: HandshakeChoice.AUTO
        set(v) = prefs.edit { putString("wireless_compatibility", v) }

    /**
     * The handshake that last reached service discovery, per head unit Bluetooth address (for Auto).
     * TODO: move into KnownHeadUnits once #233 is merged.
     */
    val wirelessHandshakes: Map<String, WirelessHandshake>
        get() = HandshakeChoice.parse(wirelessHandshakeEntries)

    fun rememberWirelessHandshake(address: String, handshake: WirelessHandshake) {
        val entries = HandshakeChoice.remember(wirelessHandshakeEntries, address, handshake)
        prefs.edit { putString("wireless_handshakes", entries.joinToString("\n")) }
    }

    private val wirelessHandshakeEntries: List<String>
        get() = prefs.getString("wireless_handshakes", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    /** Check GitHub for a newer release and warn on the home screen. Off never touches the network. */
    var checkUpdates: Boolean
        get() = prefs.getBoolean("check_updates", true)
        set(v) = prefs.edit { putBoolean("check_updates", v) }

    // Update-check cache (see UpdateChecker): the latest release seen and its page, when we last
    // asked, and the version the user dismissed. Versions are stored without a leading "v".
    var updateLatestVersion: String?
        get() = prefs.getString("update_latest_version", null)
        set(v) = prefs.edit { putString("update_latest_version", v) }
    var updateLatestUrl: String?
        get() = prefs.getString("update_latest_url", null)
        set(v) = prefs.edit { putString("update_latest_url", v) }
    var updateLastCheckMs: Long
        get() = prefs.getLong("update_last_check_ms", 0)
        set(v) = prefs.edit { putLong("update_last_check_ms", v) }
    var updateDismissedVersion: String?
        get() = prefs.getString("update_dismissed_version", null)
        set(v) = prefs.edit { putString("update_dismissed_version", v) }

    /** Largest video height we accept from the head unit's offers (480, 720, 1080, 1440, 2160). */
    var maxVideoHeight: Int
        get() = prefs.getInt("max_video_height", 1080)
        set(v) = prefs.edit { putInt("max_video_height", v) }

    /** Prefer 60 fps configurations when the head unit offers them. */
    var prefer60fps: Boolean
        get() = prefs.getBoolean("prefer_60fps", true)
        set(v) = prefs.edit { putBoolean("prefer_60fps", v) }

    /**
     * Force H.264 video even when the head unit and phone could both do a newer codec (H.265...).
     * The newer codec is used automatically otherwise; this is the escape hatch for head units that
     * advertise one but decode it poorly.
     */
    var forceH264: Boolean
        get() = prefs.getBoolean("force_h264", false)
        set(v) = prefs.edit { putBoolean("force_h264", v) }

    /**
     * Encode the car screen at a constant bitrate (VideoEncoder.constantBitrate): for head units
     * that drop the link when the whole picture changes, as with the phone screen. Off: variable.
     */
    var constantBitrateVideo: Boolean
        get() = prefs.getBoolean("constant_bitrate_video", false)
        set(v) = prefs.edit { putBoolean("constant_bitrate_video", v) }

    /**
     * Apply the fixes AARE ships for specific head units (the built-in vehicles.json rules marked
     * `optIn`: no wireless, video size, mixed navigation voice, EV fuel type, pixel aspect, "Exit to
     * car" naming). Off: every car behaves as it did before they were shipped, unless the user's
     * own vehicles.json turns them on for it.
     */
    var builtInCarFixes: Boolean
        get() = prefs.getBoolean("builtin_car_fixes", false)
        set(v) = prefs.edit { putBoolean("builtin_car_fixes", v) }

    /**
     * Put calls on the phone's loudspeaker and microphone while connected to a car (CallSpeaker),
     * for setups whose hands-free microphone doesn't work. Off: calls go where Android routes them.
     */
    var callsOnPhoneSpeaker: Boolean
        get() = prefs.getBoolean("calls_on_phone_speaker", false)
        set(v) = prefs.edit { putBoolean("calls_on_phone_speaker", v) }

    /** Where the car screen's bar goes: next to the driver ([RAIL_AUTO]), [RAIL_LEFT], [RAIL_RIGHT] or [RAIL_BOTTOM]. */
    var railPosition: String
        get() = prefs.getString("rail_position", null)
            ?: if (prefs.contains("rail_right")) (if (prefs.getBoolean("rail_right", false)) RAIL_RIGHT else RAIL_LEFT) else RAIL_AUTO
        set(v) = prefs.edit { putString("rail_position", v); remove("rail_right") }

    /**
     * The car screen's theme, an id from `CarThemes`. Before themes, the only choice was the style
     * ("car_style"): CarPlay's keeps its look, everyone else gets AARE's.
     */
    var carThemeId: String
        get() = prefs.getString("style_theme", null) ?: if (legacyCarPlay) "carplay" else "aare"
        set(v) = prefs.edit { putString("style_theme", v) }

    /** The car screen's layout, an id from `CarLayout`: [carThemeId]'s rule for the old setting. */
    var carLayoutId: String
        get() = prefs.getString("style_layout", null) ?: if (legacyCarPlay) "dock" else "taskbar"
        set(v) = prefs.edit { putString("style_layout", v) }

    private val legacyCarPlay: Boolean get() = prefs.getString("car_style", null) == "carplay"

    /** The car screen's text size, an id from `CarTextSize`: default, larger or largest. */
    var carTextSize: String
        get() = prefs.getString("car_text_size", null) ?: "default"
        set(v) = prefs.edit { putString("car_text_size", v) }

    /** The car screen's control (touch target) size, an id from `CarControlSize`. Independent of [carTextSize]. */
    var carControlSize: String
        get() = prefs.getString("car_control_size", null) ?: "default"
        set(v) = prefs.edit { putString("car_control_size", v) }

    /**
     * Two apps side by side on the car screen (the taskbar layout only, and only when the screen is
     * wide enough): off by default, like any screen real estate change the driver didn't ask for.
     */
    var splitScreen: Boolean
        get() = prefs.getBoolean("split_screen", false)
        set(v) = prefs.edit { putBoolean("split_screen", v) }

    /**
     * The accent colour, an id from `CarAccents`: the theme's own ("theme"), the phone's ("phone",
     * Android 12 and later) or a colour. On a single-colour theme it colours everything.
     */
    var carAccent: String
        get() = prefs.getString("style_accent", null) ?: "theme"
        set(v) = prefs.edit { putString("style_accent", v) }

    /**
     * Passenger mode: ignore the car's "driving" signal, so a passenger can type a destination or
     * browse long lists while moving. The driver must not use the screen then.
     */
    var ignoreDriving: Boolean
        get() = prefs.getBoolean("ignore_driving", false)
        set(v) = prefs.edit { putBoolean("ignore_driving", v) }

    /**
     * Room (dp) kept free along the bottom of the dashboard's map card, in the visible area given
     * to the navigation app: lifts what the app draws there itself (HERE WeGo's speed, over its
     * logo). 0 gives the app the whole card.
     */
    var mapCardBottomInset: Int
        get() = prefs.getInt("map_card_bottom_inset", 0)
        set(v) = prefs.edit { putInt("map_card_bottom_inset", v) }

    /** The weather next to the clock (from Open-Meteo, with an approximate position). */
    var showWeather: Boolean
        get() = prefs.getBoolean("show_weather", true)
        set(v) = prefs.edit { putBoolean("show_weather", v) }

    /** How many upcoming events the calendar card shows (1: today's behaviour, up to 5). */
    var calendarAgendaCount: Int
        get() = prefs.getInt("calendar_agenda_count", 1).coerceIn(1, 5)
        set(v) = prefs.edit { putInt("calendar_agenda_count", v.coerceIn(1, 5)) }


    /**
     * The clock, phone signal and battery in the car screen's bar: [CHROME_AUTO] leaves out those
     * the car shows itself, [CHROME_SHOW] / [CHROME_HIDE] always show or hide all three.
     */
    var carChrome: String
        get() = prefs.getString("car_chrome", CHROME_AUTO) ?: CHROME_AUTO
        set(v) = prefs.edit { putString("car_chrome", v) }

    /** Behind the dashboard: none, one of the built-in gradients, or [WALLPAPER_CUSTOM] (filesDir/wallpaper.jpg). */
    var wallpaper: String
        get() = prefs.getString("wallpaper", "none") ?: "none"
        set(v) = prefs.edit { putString("wallpaper", v) }

    /** Keep the car screen's bar dark even with day colors. */
    var darkBar: Boolean
        get() = prefs.getBoolean("dark_bar", false)
        set(v) = prefs.edit { putBoolean("dark_bar", v) }

    /**
     * Act on `aare://` links from the chosen assistant (AssistantLinkActivity). Off by default: the
     * driver chooses to let the assistant's replies drive the car screen.
     */
    var assistantLinks: Boolean
        get() = prefs.getBoolean("assistant_links", false)
        set(v) = prefs.edit { putBoolean("assistant_links", v) }

    /**
     * The dashboard's cards in order, ids from `DashCard`. Before it was configurable, two switches
     * set it: "music first" and "next calendar event" (the latest notification came last).
     */
    var dashboardCards: List<String>
        get() = prefs.getString("dashboard_cards", null)?.split(',')?.filter { it.isNotBlank() } ?: buildList {
            if (prefs.getBoolean("dashboard_media_first", false)) addAll(listOf("media", "map")) else addAll(listOf("map", "media"))
            if (prefs.getBoolean("show_calendar", true)) add("calendar")
            add("notification")
        }
        set(v) = prefs.edit { putString("dashboard_cards", v.joinToString(",")) }

    /** The map keeps the big space on the dashboard wherever it is in the order. */
    var dashboardMapBig: Boolean
        get() = prefs.getBoolean("dashboard_map_big", false)
        set(v) = prefs.edit { putBoolean("dashboard_map_big", v) }

    /** Car screen colors: follow the car's day/night signal ([THEME_AUTO]), or always [THEME_DARK] / [THEME_LIGHT]. */
    var carTheme: String
        get() = prefs.getString("car_theme", THEME_AUTO) ?: THEME_AUTO
        set(v) = prefs.edit { putString("car_theme", v) }

    /**
     * Where [THEME_AUTO] takes night mode from: the car's own day/night signal ([NIGHT_SOURCE_CAR],
     * the default) or the phone's ambient light sensor ([NIGHT_SOURCE_LIGHT_SENSOR], with
     * hysteresis — see `LightNightDecider`). Has no effect with [THEME_DARK] or [THEME_LIGHT].
     */
    var nightSource: String
        get() = prefs.getString("night_source", NIGHT_SOURCE_CAR) ?: NIGHT_SOURCE_CAR
        set(v) = prefs.edit { putString("night_source", v) }

    /** Stream phone audio to the car (needs the screen-capture consent for audio). */
    var carAudio: Boolean
        get() = prefs.getBoolean("car_audio", true)
        set(v) = prefs.edit { putBoolean("car_audio", v) }

    /**
     * The phone's own Bluetooth MAC, for the car's in-session pairing handshake. Android hides it
     * from apps on most devices, and some head units withhold the projected screen until they know
     * which phone is connected, so the user can set it (Settings > About phone > Status). Empty: try
     * to read it, else skip.
     */
    var phoneBluetoothAddress: String
        get() = prefs.getString("phone_bt_address", "") ?: ""
        set(v) = prefs.edit { putString("phone_bt_address", v) }

    /**
     * The car's microphone for the phone's own apps while connected (audio/CarMicForApps): voice
     * typing, voice recorders and voice messages hear the car instead of the phone. Needs Shizuku
     * on Android 10+; calls keep their own microphone. Off: they record from the phone's mic.
     */
    var carMicForPhoneApps: Boolean
        get() = prefs.getBoolean("car_mic_for_phone_apps", false)
        set(v) = prefs.edit { putBoolean("car_mic_for_phone_apps", v) }

    /**
     * When the car's Bluetooth answers AARE's pairing request and the phone isn't paired with it,
     * pair with that car (and only it) through Shizuku instead of Android's dialog, accepting only
     * the passkey the car sent over Android Auto (PairingCheck, BluetoothAutoPair). Android 12+. Off by default.
     */
    var autoPairCarBluetooth: Boolean
        get() = prefs.getBoolean("auto_pair_car_bluetooth", false)
        set(v) = prefs.edit { putBoolean("auto_pair_car_bluetooth", v) }
    /** Capture car audio through su (a rooted phone), once the user has allowed it in the audio settings. */
    var rootAudio: Boolean
        get() = prefs.getBoolean("root_audio", false)
        set(v) = prefs.edit { putBoolean("root_audio", v) }

    /**
     * Package of the assistant the steering-wheel voice button opens (a car app such as Lapin, or a
     * phone assistant); null = follow the phone's default digital assistant.
     */
    var assistantPackage: String?
        get() = prefs.getString("assistant_pkg", null)
        set(v) = prefs.edit { if (v == null) remove("assistant_pkg") else putString("assistant_pkg", v) }

    /**
     * For an assistant without a car app: show its voice UI on the car screen (through the same
     * launcher as standalone apps) instead of opening it on the phone, while parked or in passenger
     * mode. Off by default (today's behaviour); needs root or Shizuku, checked separately
     * ([net.prototux.aare.launch.AppLauncher.available]).
     */
    var assistantOnCarScreen: Boolean
        get() = prefs.getBoolean("assistant_on_car_screen", false)
        set(v) = prefs.edit { putBoolean("assistant_on_car_screen", v) }

    /**
     * How the driver stops the assistant ([ASSISTANT_STOP_VOICE_BUTTON], the default: the voice button
     * turns into a stop button while the assistant is on; [ASSISTANT_STOP_RED_RESTART]: a big red stop
     * on the assistant bar, the voice button asks again; [ASSISTANT_STOP_RED_STOP]: the red stop, and
     * the voice button stops too). The steering wheel's voice key does what the voice button does.
     */
    var assistantStopStyle: String
        get() = prefs.getString("assistant_stop", ASSISTANT_STOP_VOICE_BUTTON) ?: ASSISTANT_STOP_VOICE_BUTTON
        set(v) = prefs.edit { putString("assistant_stop", v) }

    /** Offer "Phone screen" in the car: the phone's own screen, for apps AARE can't host (Google Maps, Waze). */
    var phoneScreen: Boolean
        get() = prefs.getBoolean("phone_screen", false)
        set(v) = prefs.edit { putBoolean("phone_screen", v) }

    /**
     * Launch ordinary phone apps on the car screen (NewPipe, emulators, a browser…): an opt-in
     * convenience that needs root or Shizuku. These apps are not Android Auto apps; AARE mirrors
     * their normal phone UI and injects the car's touches, so by default they are paused while
     * driving (see [standaloneDrivingRules]).
     */
    var standaloneApps: Boolean
        get() = prefs.getBoolean("standalone_apps", false)
        set(v) = prefs.edit { putBoolean("standalone_apps", v) }

    /** Keep standalone apps active when Android links their display to the phone display. */
    var standaloneKeepScreenAwake: Boolean
        get() = prefs.getBoolean("standalone_keep_screen_awake", false)
        set(v) = prefs.edit { putBoolean("standalone_keep_screen_awake", v) }

    /**
     * Offer "Show video" on the car's media screen: the media app itself is launched on the car screen
     * (as [standaloneApps] are, so it needs them on and root or Shizuku), parked only.
     */
    var mediaVideo: Boolean
        get() = prefs.getBoolean("media_video", false)
        set(v) = prefs.edit { putBoolean("media_video", v) }

    /** The packages the user allows onto the car screen (see [standaloneApps]). */
    var standaloneAppPackages: Set<String>
        get() = prefs.getStringSet("standalone_app_pkgs", emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet("standalone_app_pkgs", HashSet(v)) }

    /**
     * What each launched app may do while driving, by package: parked only (also for a package with
     * no rule), audio only, or, for navigation apps only, navigation.
     */
    var standaloneDrivingRules: Map<String, StandaloneDrivingRule>
        get() = parseDrivingRules(prefs.getStringSet("standalone_driving_rules", emptySet()).orEmpty())
        set(v) = prefs.edit { putStringSet("standalone_driving_rules", formatDrivingRules(v)) }
    /** Show a web browser (a WebView) on the car screen. Off by default; paused while driving. */
    var browser: Boolean
        get() = prefs.getBoolean("browser", false)
        set(v) = prefs.edit { putBoolean("browser", v) }

    /** The page the car browser opens. */
    var browserUrl: String
        get() = prefs.getString("browser_url", BROWSER_DEFAULT_URL) ?: BROWSER_DEFAULT_URL
        set(v) = prefs.edit { putString("browser_url", v) }
    /** Also show the car data (speed, sensors…) as an app on the car screen, for diagnostics. */
    var carDataApp: Boolean
        get() = prefs.getBoolean("car_data_app", false)
        set(v) = prefs.edit { putBoolean("car_data_app", v) }
    /**
     * Smart-home actions and tiles for the car screen (`home.HomeJson`'s versioned JSON), without
     * their secrets: the broker password and header values are in `home.HomeStore`'s own file.
     */
    var homeActions: String?
        get() = prefs.getString(HOME_ACTIONS_KEY, null)
        set(v) = prefs.edit { putString(HOME_ACTIONS_KEY, v) }
    /** Where the user dragged the in-call bar on the car screen (px offset from the top centre). */
    var callBarOffsetX: Float
        get() = prefs.getFloat("call_bar_x", 0f)
        set(v) = prefs.edit { putFloat("call_bar_x", v) }
    var callBarOffsetY: Float
        get() = prefs.getFloat("call_bar_y", 0f)
        set(v) = prefs.edit { putFloat("call_bar_y", v) }

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

    /** The default is resolved on read and never stored, so it follows the phone's language. */
    var autoReplyText: String
        get() = prefs.getString("auto_reply_text", null)?.takeIf { it.isNotBlank() } ?: appContext.getString(R.string.msg_autoreply_default)
        set(v) = prefs.edit { putString("auto_reply_text", v) }

    /**
     * Reply to a message by voice from the car screen, while driving too (notify/VoiceDictation):
     * the phone's speech recognizer listens (the car's microphone with [carMicForPhoneApps] on),
     * the text is read back and sent only on Send. Off by default.
     */
    var voiceReply: Boolean
        get() = prefs.getBoolean("voice_reply", false)
        set(v) = prefs.edit { putBoolean("voice_reply", v) }

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

    /** Also forgets who its head unit said it was (KnownHeadUnits). */
    fun forgetCar(name: String) {
        prefs.edit { putStringSet("known_cars", (knownCars - name).map { (n, t) -> "$n\t$t" }.toSet()) }
        KnownHeadUnits(appContext).forgetCar(name)
    }

    /** Last navigation and media apps used, restored on the next connection. */
    var lastNavApp: String?
        get() = prefs.getString("last_nav", null)
        set(v) = prefs.edit { putString("last_nav", v) }
    var lastMediaApp: String?
        get() = prefs.getString("last_media", null)
        set(v) = prefs.edit { putString("last_media", v) }

    /**
     * The rail's status widgets, in order: ids from `RailWidget` (ui.car). Default: today's rail
     * (the clock, the phone's battery, the weather); kept as raw ids here, not the enum, so this
     * file doesn't depend on the UI layer.
     */
    var railWidgets: List<String>
        get() = prefs.getString("rail_widgets", null)?.split(',')?.filter { it.isNotBlank() } ?: listOf("clock", "battery", "weather")
        set(v) = prefs.edit { putString("rail_widgets", v.joinToString(",")) }

    /** The car launcher's order: app keys (package names, or the built-in apps' ids), first first. */
    var appOrder: List<String>
        get() = prefs.getString("app_order", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = prefs.edit { putString("app_order", v.joinToString("\n")) }

    /** The rail's chosen apps, in order (keys as [appOrder]'s). Empty: the rail's built-in set. */
    var railApps: List<String>
        get() = prefs.getString("rail_apps", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = prefs.edit { putString("rail_apps", v.joinToString("\n")) }

    /** A narrower rail, with smaller icons and tighter spacing. */
    var compactRail: Boolean
        get() = prefs.getBoolean("compact_rail", false)
        set(v) = prefs.edit { putBoolean("compact_rail", v) }

    /** The space between the rail's apps in dp, or null for the standard one. */
    var railSpacing: Int?
        get() = if (prefs.contains("rail_spacing")) prefs.getInt("rail_spacing", 0) else null
        set(v) = prefs.edit { if (v == null) remove("rail_spacing") else putInt("rail_spacing", v) }

    /** Runtime permissions setup has asked for at least once, to tell a dismissed dialog from a refusal for good. */
    var requestedPermissions: Set<String>
        get() = prefs.getStringSet("requested_permissions", emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet("requested_permissions", v) }

    /** Apps hidden from the car launcher. */
    var hiddenApps: Set<String>
        get() = prefs.getStringSet("hidden_apps", emptySet()) ?: emptySet()
        set(v) = prefs.edit { putStringSet("hidden_apps", v) }

    /**
     * Keys AARE has seen from a car, code to a readable name (`KeyEvent.keyCodeToString`), for
     * the "Car buttons" chooser. Recorded as they arrive (`rememberCarKey`); never removed, so a
     * button pressed once on an earlier car still has a row (harmless if that car is gone).
     */
    var carKeysSeen: Map<Int, String>
        get() = CarKeyMapCodec.decode(prefs.getStringSet("car_keys_seen", emptySet()).orEmpty())
        set(v) = prefs.edit { putStringSet("car_keys_seen", CarKeyMapCodec.encode(v)) }

    fun rememberCarKey(code: Int, name: String) {
        val seen = carKeysSeen
        if (seen[code] == name) return
        carKeysSeen = seen + (code to name)
    }

    /**
     * User-chosen remaps: a car key code to a `CarKeyAction` id. A key with no entry here keeps
     * its normal behaviour (`CarSession.onKey`).
     */
    var carKeyMap: Map<Int, String>
        get() = CarKeyMapCodec.decode(prefs.getStringSet("car_key_map", emptySet()).orEmpty())
        set(v) = prefs.edit { putStringSet("car_key_map", CarKeyMapCodec.encode(v)) }

    /** Stable random id sent to head units so they recognise this phone. */
    val instanceId: String
        get() = prefs.getString("instance_id", null) ?: UUID.randomUUID().toString().also { prefs.edit { putString("instance_id", it) } }

    /**
     * Replace the downloaded certificate when a newer one is published (see CertificateUpdates).
     * Off by default: it means a daily request to a third-party repository.
     */
    var certAutoUpdate: Boolean
        get() = prefs.getBoolean("cert_auto_update", false)
        set(v) = prefs.edit { putBoolean("cert_auto_update", v) }

    // Certificate-update state (see CertificateUpdates): when the repository was last asked, and
    // the expiry warning already notified and the one dismissed, as "name|expired" keys.
    var certUpdateLastCheckMs: Long
        get() = prefs.getLong("cert_update_last_check_ms", 0)
        set(v) = prefs.edit { putLong("cert_update_last_check_ms", v) }
    var certExpiryNotified: String?
        get() = prefs.getString("cert_expiry_notified", null)
        set(v) = prefs.edit { putString("cert_expiry_notified", v) }
    var certExpiryDismissed: String?
        get() = prefs.getString("cert_expiry_dismissed", null)
        set(v) = prefs.edit { putString("cert_expiry_dismissed", v) }

    /** Index of the certificate to offer first (rotates after a head unit rejects one). */
    var certificateIndex: Int
        get() = prefs.getInt("cert_index", 0)
        set(v) = prefs.edit { putInt("cert_index", v) }

    /** The head unit connected last, by its KnownHeadUnits key: the one the "Car quirks" page is for. */
    var lastHeadUnit: String?
        get() = prefs.getString("last_head_unit", null)
        set(v) = prefs.edit { putString("last_head_unit", v) }

    /** "Don't show again" on the Android 6/7 notice (MainActivity). OK alone shows it again next launch. */
    var oldAndroidNoticeDismissed: Boolean
        get() = prefs.getBoolean("old_android_notice_dismissed", false)
        set(v) = prefs.edit { putBoolean("old_android_notice_dismissed", v) }

    companion object {
        const val BROWSER_DEFAULT_URL = "https://duckduckgo.com/"
        const val HOME_ACTIONS_KEY = "home_actions"
        const val RAIL_AUTO = "auto"
        const val RAIL_LEFT = "left"
        const val RAIL_RIGHT = "right"
        const val RAIL_BOTTOM = "bottom"
        const val ASSISTANT_STOP_VOICE_BUTTON = "voice_button"
        const val ASSISTANT_STOP_RED_RESTART = "red_restart"
        const val ASSISTANT_STOP_RED_STOP = "red_stop"
        const val THEME_AUTO = "auto"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val CHROME_AUTO = "auto"
        const val CHROME_SHOW = "show"
        const val CHROME_HIDE = "hide"
        const val NIGHT_SOURCE_CAR = "car"
        const val NIGHT_SOURCE_LIGHT_SENSOR = "light_sensor"
        const val WALLPAPER_CUSTOM = "custom"
        /** The user's wallpaper photo, in the app's files. */
        const val WALLPAPER_FILE = "wallpaper.jpg"
    }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(l)
    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(l)

    /** Every entry backing a setting, for [SettingsBackup.export] to filter and turn into JSON. */
    val all: Map<String, Any?> get() = prefs.all

    /**
     * Writes [values] (SettingsBackup.Imported.values: allowed keys with the types their getters
     * read, already checked) over the current settings. Existing keys not
     * in [values] are left alone: an older export (fewer settings than this version knows about)
     * only replaces what it has, it doesn't reset the rest to their defaults.
     */
    fun restore(values: Map<String, Any>) {
        prefs.edit {
            // SettingsBackup.import already leaves these out; a second check where they'd be written.
            for ((key, value) in values) if (key !in SettingsBackup.BLOCKED) when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
    }
}
