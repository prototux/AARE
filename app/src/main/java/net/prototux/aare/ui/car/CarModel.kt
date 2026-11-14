package net.prototux.aare.ui.car

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
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
    /** Split screen is on (a setting): an app can be opened beside the current one. */
    var splitScreenSetting by mutableStateOf(false)
    /** The app sharing the screen with the current one, when split; null for one pane. */
    var secondaryAppId by mutableStateOf<String?>(null)
    /** The primary pane's share of the content width while split; one of [snapSplitRatio]'s rest points. */
    var splitRatio by mutableStateOf(0.5f)
    /** Which pane the rotary/D-pad knob is in, while split: where a physical back key applies. */
    var focusedPane by mutableStateOf(CarPane.PRIMARY)

    fun app(id: String?): CarAppEntry? = apps.firstOrNull { it.id == id }
}

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
    /** Opens [app] beside the current one (a long-press in the rail): only for [canOpenBeside] kinds. */
    fun openBeside(app: CarAppEntry)
    /** Closes the second pane; the current app stays, full screen. */
    fun closeSplit()
    /** The divider was released at this raw ratio (0..1 of the primary pane's share): snaps it, or
     * closes whichever pane the drag reached the edge of. */
    fun commitSplitRatio(raw: Float)
    /** The rotary/D-pad knob (or a tap) moved into this pane. */
    fun focusPane(pane: CarPane)
}
