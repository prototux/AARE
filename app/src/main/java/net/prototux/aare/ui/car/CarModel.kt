package net.prototux.aare.ui.car

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** The car UI's navigation: the dashboard, the app grid, one app full screen, or two side by side. */
sealed interface CarScreen {
    data object Dashboard : CarScreen
    data object Launcher : CarScreen
    data class App(val appId: String) : CarScreen
    /** Two apps side by side, never the same one twice. */
    data class Split(val left: String, val right: String) : CarScreen
}

/** Whether the app [id] is on screen, full screen or in either pane. */
fun CarScreen.shows(id: String?): Boolean = when (this) {
    is CarScreen.App -> appId == id
    is CarScreen.Split -> left == id || right == id
    else -> false
}

/** A pane released within this share of the width from an edge closes. */
const val SPLIT_SNAP = 0.1f

/**
 * The screen after opening [appId] in the left or right pane. The other pane keeps what is on
 * screen: the app open full screen, or the other pane of a split (the same app swaps sides). With
 * nothing on screen it takes the most recent other app, and with none the app opens full screen.
 */
fun splitWith(current: CarScreen, appId: String, left: Boolean, recent: List<String>): CarScreen {
    val onScreen = when (current) {
        is CarScreen.App -> current.appId
        is CarScreen.Split -> when (appId) {
            current.left -> current.right
            current.right -> current.left
            else -> if (left) current.right else current.left
        }
        else -> null
    }
    val other = onScreen?.takeIf { it != appId } ?: recent.firstOrNull { it != appId } ?: return CarScreen.App(appId)
    return if (left) CarScreen.Split(appId, other) else CarScreen.Split(other, appId)
}

/**
 * The screen once the apps for which [gone] is true have left the app list (hidden, uninstalled,
 * switched off): the other app of a split takes the whole screen, and with both gone the
 * dashboard. A single app full screen stays as it is (the shell shows the dashboard for it).
 */
fun CarScreen.withoutApps(gone: (String) -> Boolean): CarScreen = when {
    this !is CarScreen.Split -> this
    gone(left) && gone(right) -> CarScreen.Dashboard
    gone(left) -> CarScreen.App(right)
    gone(right) -> CarScreen.App(left)
    else -> this
}

/** The screen once the divider is released at [fraction] (the left pane's share of the width). */
fun snapSplit(split: CarScreen.Split, fraction: Float): CarScreen = when {
    fraction < SPLIT_SNAP -> CarScreen.App(split.right)
    fraction > 1 - SPLIT_SNAP -> CarScreen.App(split.left)
    else -> split
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
    val apps = mutableStateListOf<CarAppEntry>()
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
    /** The next calendar event, for the dashboard. */
    var nextEvent by mutableStateOf<NextEvent?>(null)
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
    /** The dashboard's cards, in order (a setting). */
    var dashboardCards by mutableStateOf(DashCard.DEFAULT)
    /** The weather next to the clock (a setting; the weather card is one of [dashboardCards]). */
    var weatherInBar by mutableStateOf(true)
    /** The theme (a setting, with the phone's accent colours if asked). */
    var theme by mutableStateOf(AareTheme)
    /** Where things go: a taskbar and dashboard, or a dock and app pages (a setting). */
    var layout by mutableStateOf(CarLayout.TASKBAR)
    /** The rail runs along the bottom instead of a side. */
    var railBottom by mutableStateOf(false)
    /** The left pane's share of the width in a split, kept between splits. */
    var splitFraction by mutableFloatStateOf(0.5f)
    /** The pane touched last in a split: the car's Back key goes to its app. */
    var splitActiveLeft by mutableStateOf(true)

    fun app(id: String?): CarAppEntry? = apps.firstOrNull { it.id == id }
}

/** A phone call, ringing or on, for the car's call card. */
@Stable
data class CarCall(val ringing: Boolean, val name: String, val sinceMs: Long)

/** Actions the car UI asks for; implemented by the session. */
interface CarActions {
    fun openApp(app: CarAppEntry)
    /** Opens [app] in the left or right pane, next to what is on screen ([splitWith]). */
    fun openInPane(app: CarAppEntry, left: Boolean)
    /** The split's divider was let go at [fraction]: near an edge that pane closes ([snapSplit]), else the panes take that size. */
    fun releaseDivider(fraction: Float)
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
