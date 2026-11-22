package net.prototux.aare.ui.car

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.prototux.aare.car.CarChrome
import net.prototux.aare.car.NextEvent
import net.prototux.aare.car.Weather
import net.prototux.aare.notify.PhoneGuidance
import net.prototux.aare.ui.theme.AareTheme

/**
 * What kind of app an entry in the launcher is, which decides where its UI goes. [EXIT] has no UI:
 * it hands the screen back to the car's own.
 */
enum class CarAppKind { NAVIGATION, MEDIA, TEMPLATED, ASSISTANT, MESSAGING, PHONE_SCREEN, BROWSER, CAR_DATA, STANDALONE, PHONE, SETTINGS, NOTIFICATIONS, EXIT }

/** One launchable app on the car screen. */
@Stable
class CarAppEntry(
    val id: String,
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val kind: CarAppKind,
)

@Stable
class NowPlaying(
    val appId: String,
    val title: String,
    val subtitle: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
)

/** The car UI's navigation: the dashboard, the app grid, or one app full screen. */
sealed interface CarScreen {
    data object Dashboard : CarScreen
    data object Launcher : CarScreen
    data class App(val appId: String) : CarScreen
}

/** A transient card, e.g. an incoming message or a toast from a car app. */
@Stable
class CarNotice(
    val key: String,
    val title: String,
    val text: String,
    val icon: Bitmap?,
    val actions: List<Pair<String, () -> Unit>> = emptyList(),
    val timeMs: Long = System.currentTimeMillis(),
    /** A message from a contact: its text is hidden while driving, like in the messaging screens. */
    val message: Boolean = false,
)

/** Head-unit geometry: the drawable area inside the video margins, and the rail side. */
data class CarGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int,
    /** Rail on the left (left-hand drive) or right. */
    val railStart: Boolean = true,
)

/**
 * All state the car UI reads. The session and hosts write it from the main thread.
 */
@Stable
class CarUiState {
    var night by mutableStateOf(true)
    var screen by mutableStateOf<CarScreen>(CarScreen.Dashboard)
    /**
     * Every app the car can open, in the user's order: also the built-ins hidden from the app grid,
     * since the bell, the call card, the rail's phone button and the steering-wheel keys open them
     * from here. The grids show [launcherApps].
     */
    val apps = mutableStateListOf<CarAppEntry>()
    /** Ids of built-in apps (CarRuntime.HIDEABLE_BUILTIN_IDS) the user hid from the app grid only. */
    var launcherHidden by mutableStateOf<Set<String>>(emptySet())
    /** What the app grid and launcher list show: [apps] without the ones in [launcherHidden]. */
    val launcherApps: List<CarAppEntry> get() = apps.withoutLauncherHidden(launcherHidden)
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
    /** The navigation app whose map is shown on the dashboard. */
    var activeNavAppId by mutableStateOf<String?>(null)
    /** The media app the dashboard's media card controls. */
    var activeMediaAppId by mutableStateOf<String?>(null)
    val notices = mutableStateListOf<CarNotice>()
    var drivingRestricted by mutableStateOf(false)
    var headUnitName by mutableStateOf("")
    var listening by mutableStateOf(false)
    /** Recent notifications (messages), newest first, for the notification centre. */
    val history = mutableStateListOf<CarNotice>()
    /** Notifications not seen in the notification centre yet. */
    var unseen by mutableStateOf(0)
    /** The dashboard's wallpaper: a built-in name, or "custom" with [wallpaperImage]. */
    var wallpaper by mutableStateOf("none")
    var wallpaperImage by mutableStateOf<Bitmap?>(null)
    /** The hosted navigation app's trip (next step, distances), for the cluster's turn card. */
    var trip by mutableStateOf<androidx.car.app.navigation.model.Trip?>(null)
    /** The package that sent [trip]: its maneuver icons are opened on that app's behalf. */
    var tripPackage by mutableStateOf<String?>(null)
    /** The weather next to the clock, if known and wanted. */
    var weather by mutableStateOf<Weather?>(null)
    /** The next calendar events, earliest first (a setting decides how many), for the dashboard. */
    var nextEvents by mutableStateOf<List<NextEvent>>(emptyList())
    /** The phone call ringing or on, if any. */
    var call by mutableStateOf<CarCall?>(null)
    /** The apps opened last, newest first (ids): the rail shows the recent ones. */
    val recentAppIds = mutableStateListOf<String>()
    /** The voice assistant showing as a bar over the screen (an app entry id), or null. */
    var assistantAppId by mutableStateOf<String?>(null)
    /** Turn-by-turn from a navigation app running on the phone (Google Maps, Waze…). */
    var phoneGuidance by mutableStateOf<PhoneGuidance?>(null)
    /** Right-hand drive: the rail goes to the right, next to the driver. */
    var railOnRight by mutableStateOf(false)
    /** The driver sits on the right (right-hand drive): the voice button goes on that side. */
    var driverOnRight by mutableStateOf(false)
    /** The rail stays dark even with day colors (a setting). */
    var darkBar by mutableStateOf(false)
    /** The rail's status widgets, in order (a setting). */
    var railWidgets by mutableStateOf(RailWidget.DEFAULT)
    /** The rail's chosen apps, in order (a setting; empty = the rail's built-in set). */
    var railApps by mutableStateOf<List<String>>(emptyList())
    /** A narrower rail: smaller icons, tighter spacing (a setting). */
    var compactRail by mutableStateOf(false)
    /** The dashboard's cards, in order (a setting). */
    var dashboardCards by mutableStateOf(DashCard.DEFAULT)
    /** The map keeps the big space wherever it is in [dashboardCards] (a setting). */
    var dashboardMapBig by mutableStateOf(false)
    /** The weather next to the clock (a setting; the weather card is one of [dashboardCards]). */
    var weatherInBar by mutableStateOf(true)
    /** The clock, phone signal and battery the bar shows: not those the car draws itself (and a setting). */
    var chrome by mutableStateOf(CarChrome.ALL)
    /** The theme (a setting, with the phone's accent colours if asked). */
    var theme by mutableStateOf(AareTheme)
    /** Where things go: a taskbar and dashboard, or a dock and app pages (a setting). */
    var layout by mutableStateOf(CarLayout.TASKBAR)
    /** The rail runs along the bottom instead of a side. */
    var railBottom by mutableStateOf(false)
    /** Font-scale multiplier for the car screen's text (a setting). Independent of [controlScale]. */
    var textScale by mutableStateOf(1f)
    /** Multiplier for [net.prototux.aare.ui.theme.CarDimens] (touch targets, icons…) (a setting). */
    var controlScale by mutableStateOf(1f)

    fun app(id: String?): CarAppEntry? = apps.firstOrNull { it.id == id }
}

/** These apps without the [hidden] ones, for the app grid: everything else still reaches them. */
internal fun List<CarAppEntry>.withoutLauncherHidden(hidden: Set<String>): List<CarAppEntry> =
    if (hidden.isEmpty()) this else filter { it.id !in hidden }

/** A phone call, ringing or on, for the car's call card. */
@Stable
data class CarCall(val ringing: Boolean, val name: String, val sinceMs: Long)

/** Actions the car UI asks for; implemented by the session. */
interface CarActions {
    fun openApp(app: CarAppEntry)
    fun goHome()
    fun openLauncher()
    fun assistant()
    fun mediaPlayPause()
    fun mediaNext()
    fun mediaPrevious()
    fun dismissNotice(notice: CarNotice)
    fun answerCall()
    fun endCall()
    /** Navigate to a place (an address or a name) in the car's navigation app. */
    fun navigateTo(place: String)
}
