package net.prototux.aare.mirror

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.view.MotionEvent
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.prototux.aare.Settings
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import net.prototux.aare.R
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.ui.car.HostedAppSurface
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.Placeholder
import net.prototux.aare.notify.CarNotificationListener
import net.prototux.aare.service.ProjectionService

/**
 * The phone's screen on the car screen, for apps AARE can't host (Google Maps, Waze…). Touches
 * go back to the phone through [MirrorTouchService]. While driving it only shows a navigation
 * app, which needs [MirrorTouchService] to know the app in front. It is paused, with no frames
 * sent and no touch replayed, while the phone is locked.
 */
@Composable
internal fun MirrorScreen(runtime: CarRuntime, driving: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val locked = rememberKeyguardLocked()
    val settings = remember { Settings(context) }
    // Shizuku or root: touches go to the phone live, and it can turn to landscape or show a desktop of its own.
    val helper = remember { AppLauncher.available(context) }
    // Parked, as chosen: a desktop of the phone's own at the car screen's size, instead of its mirror.
    // Not while the phone is locked: its display would let anyone in the car use it.
    if (!driving && helper && settings.phoneScreenMode == Settings.PHONE_SCREEN_DESKTOP) {
        if (locked) Placeholder(stringResource(R.string.mirror_locked_placeholder), loading = false, icon = Icons.Rounded.Lock, modifier = modifier.background(Color.Black))
        else PhoneDesktop(runtime, modifier)
        return
    }
    val guidance by CarNotificationListener.guidance.collectAsState()
    val serviceFront by MirrorTouchService.foregroundApp.collectAsState()
    // With Shizuku or root the helper tells the app in front, while driving, so the accessibility service isn't needed.
    var helperFront by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(helper, driving) {
        if (!helper || !driving) return@LaunchedEffect
        while (true) {
            helperFront = withContext(Dispatchers.IO) { PhoneMirror.topApp(context) }
            delay(FRONT_POLL_MS)
        }
    }
    val foreground = if (helper) helperFront else serviceFront
    val navApps = KNOWN_NAVIGATION + listOfNotNull(guidance?.packageName) + runtime.navigationPackages()
    // Driving: only when the app in front is known to be a navigation app. An unknown one (no
    // accessibility service) could be anything, a video included, even while a route is guiding.
    val allowed = !locked && (!driving || (foreground != null && foreground in navApps))
    var mirroring by remember { mutableStateOf(true) }
    // The screen-capture consent. Car audio through Shizuku, root or an audio policy never asks
    // for it, so the phone screen can be the only thing that needs it (#159).
    val consent = ProjectionService.state.collectAsState().value.audioCapture

    DisposableEffect(Unit) {
        // With a helper the key itself, as touches go; without, the accessibility service's Back. Not in the
        // on-phone preview: the phone's screen is the preview, and its Back would come straight back here.
        val back: () -> Boolean = {
            if (helper && runtime.primary) PhoneMirror.key(context, android.view.KeyEvent.KEYCODE_BACK) else MirrorTouchService.instance?.back() == true
        }
        runtime.setScreenBack(CarRuntime.PHONE_SCREEN_ID, back)
        // The next screen installs its own handler before this one fades out: leave that one alone.
        onDispose {
            runtime.clearScreenBack(CarRuntime.PHONE_SCREEN_ID, back)
            PhoneMirror.detach()
        }
    }

    Box(modifier.background(Color.Black)) {
        if (allowed) key(consent) {
            // A new view when the consent comes or goes, so the mirror starts as soon as it's given.
            MirrorSurface(runtime, Modifier.fillMaxSize()) { mirroring = it }
            if (helper) {
                // Landscape, as wide as the car screen, and its panel dark if chosen; all back once it's left.
                DisposableEffect(Unit) {
                    PhoneMirror.phoneScreen(context, true, settings.phoneScreenOff)
                    onDispose { PhoneMirror.phoneScreen(context, false, false) }
                }
                LiveTouch(context)
            } else Box(Modifier.fillMaxSize().pointerInput(Unit) {
                // Touches are caught above the mirror and replayed on the phone.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.uptimeMillis
                    val points = arrayListOf(down.position)
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        points += c.position
                        c.consume()
                        if (!c.pressed) {
                            replay(context, size.width, size.height, points.map { it.x to it.y }, c.uptimeMillis - start, viewConfiguration.touchSlop)
                            break
                        }
                    }
                }
            })
        }
        when {
            locked -> Placeholder(stringResource(R.string.mirror_locked_placeholder),
                loading = false, icon = Icons.Rounded.Lock)
            !allowed -> Placeholder(
                if (asksForAccessibility(helper, MirrorTouchService.instance != null)) stringResource(R.string.mirror_driving_no_accessibility)
                else stringResource(R.string.mirror_driving_nav_only),
                loading = false, icon = Icons.Rounded.PauseCircle)
            !mirroring -> Placeholder(stringResource(if (consent) R.string.mirror_capture_failed else R.string.mirror_need_screen_capture),
                loading = false, icon = Icons.Rounded.PhoneAndroid, modifier = Modifier.background(Color.Black))
            !helper && MirrorTouchService.instance == null -> Placeholder(stringResource(R.string.mirror_need_accessibility),
                loading = false, icon = Icons.Rounded.TouchApp, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The phone's own desktop on a display of the car screen's size: its home screen, navigation bar
 * and any app opened from there, landscape and full size, with the car's touches going straight to
 * it as they happen. The phone's screen keeps showing what it showed.
 */
@Composable
private fun PhoneDesktop(runtime: CarRuntime, modifier: Modifier) {
    val context = LocalContext.current
    val launcher = remember { AppLauncher(context, "desktop") }
    var started by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val back: () -> Boolean = {
            val t = android.os.SystemClock.uptimeMillis()
            launcher.key(t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)
            launcher.key(t, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK)
            launcher.connected
        }
        runtime.setScreenBack(CarRuntime.PHONE_SCREEN_ID, back)
        onDispose {
            runtime.clearScreenBack(CarRuntime.PHONE_SCREEN_ID, back)
            launcher.release()
        }
    }
    Box(modifier.background(Color.Black)) {
        HostedAppSurface(launcher, AppLauncher.PHONE_DESKTOP, runtime.densityDpi, Modifier.fillMaxSize()) { started = it }
        // Kept awake: untouched, the phone would lock, and the desktop pause with it. Put back when the helper stops.
        LaunchedEffect(started) { if (started) withContext(Dispatchers.IO) { launcher.phoneScreen(landscape = false, awake = true, panelOff = false) } }
        if (!started) Placeholder(stringResource(R.string.standalone_starting), loading = true)
    }
}

/** Whether the phone is locked (lock screen up or screen off), kept current from the screen and unlock broadcasts. */
@Composable
internal fun rememberKeyguardLocked(): Boolean {
    val context = LocalContext.current
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    var locked by remember { mutableStateOf(keyguard.isKeyguardLocked) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { locked = keyguard.isKeyguardLocked }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        locked = keyguard.isKeyguardLocked
        onDispose { context.unregisterReceiver(receiver) }
    }
    return locked
}

/** The car's touches on the mirror, sent to the phone as they happen through the helper: a drag scrolls as it goes. */
@Composable
private fun LiveTouch(context: Context) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val phone = PhoneMirror.phoneScreenSize(context)
            fun send(action: Int, x: Float, y: Float, time: Long): Boolean {
                val (px, py) = toPhone(x, y, size.width, size.height, phone.x, phone.y, clamp = action != MotionEvent.ACTION_DOWN) ?: return false
                return PhoneMirror.touch(context, down.uptimeMillis, time, action, px, py)
            }
            // A touch beside the phone's picture starts nothing.
            if (!send(MotionEvent.ACTION_DOWN, down.position.x, down.position.y, down.uptimeMillis)) return@awaitEachGesture
            down.consume()
            var last = down.position
            var lifted = false
            try {
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    c.consume()
                    last = c.position
                    if (!c.pressed) {
                        send(MotionEvent.ACTION_UP, c.position.x, c.position.y, c.uptimeMillis)
                        lifted = true
                        break
                    }
                    send(MotionEvent.ACTION_MOVE, c.position.x, c.position.y, c.uptimeMillis)
                }
            } finally {
                // The finger gone without a lift (the screen changed, the phone locked): the phone would hold it down.
                if (!lifted) send(MotionEvent.ACTION_CANCEL, last.x, last.y, android.os.SystemClock.uptimeMillis())
            }
        }
    })
}

/**
 * A point of a [viewW]×[viewH] view showing the [phoneW]×[phoneH] phone screen in proportion and
 * centered, in phone pixels; null beside the picture, or brought to its edge with [clamp].
 */
internal fun toPhone(x: Float, y: Float, viewW: Int, viewH: Int, phoneW: Int, phoneH: Int, clamp: Boolean = false): Pair<Float, Float>? {
    val scale = minOf(viewW.toFloat() / phoneW, viewH.toFloat() / phoneH)
    val px = (x - (viewW - phoneW * scale) / 2) / scale
    val py = (y - (viewH - phoneH * scale) / 2) / scale
    if (clamp) return px.coerceIn(0f, phoneW.toFloat()) to py.coerceIn(0f, phoneH.toFloat())
    return (px to py).takeIf { px in 0f..phoneW.toFloat() && py in 0f..phoneH.toFloat() }
}

/** Replays a car gesture on the phone: car pixels to phone pixels, through the letterboxing. */
private fun replay(context: android.content.Context, viewW: Int, viewH: Int, points: List<Pair<Float, Float>>, durationMs: Long, slop: Float) {
    val touch = MirrorTouchService.instance ?: return
    val phone = PhoneMirror.phoneScreenSize(context)
    val mapped = points.mapNotNull { (x, y) -> toPhone(x, y, viewW, viewH, phone.x, phone.y) }
    if (mapped.isEmpty()) return
    val (x0, y0) = points.first()
    val moved = points.any { (x, y) -> kotlin.math.hypot(x - x0, y - y0) > slop }
    if (moved) touch.swipe(mapped.filterIndexed { i, _ -> i % 3 == 0 || i == mapped.lastIndex }, durationMs)
    else if (durationMs > 500) touch.swipe(listOf(mapped.first()), durationMs) // long press
    else touch.tap(mapped.first().first, mapped.first().second)
}

/**
 * Navigation apps that can't be hosted, recognised even before they post guidance: while driving,
 * the phone screen shows only these (or the app guiding). Many have no Android Auto support, or
 * only Google's, so the phone screen is how they reach the car.
 */
internal val KNOWN_NAVIGATION = setOf(
    "com.google.android.apps.maps",
    "com.google.android.apps.mapslite", // Google Maps Go
    "com.waze",
    "com.here.app.maps",
    "com.sygic.aura",
    "com.tomtom.gplay.navapp",
    "com.generalmagic.magicearth",
    "com.mapfactor.navigator",
    "cz.seznam.mapy", // Mapy.com
    "com.huawei.maps.app", // Petal Maps, on phones without Google services
    // China (#150)
    "com.autonavi.minimap", // Amap (Gaode)
    "com.autonavi.amapauto", // Amap Auto
    "com.baidu.BaiduMap",
    "com.tencent.map",
    // Russia and neighbours
    "ru.yandex.yandexnavi",
    "ru.yandex.yandexmaps",
    "ru.dublgis.dgismobile", // 2GIS
    "com.navitel",
    // South Korea
    "com.nhn.android.nmap", // Naver Map
    "net.daum.android.map", // KakaoMap
    "com.locnall.KimGiSa", // Kakao Navi
    "com.skt.tmap.ku", // TMAP
)

/** How often the app in front is asked of the helper while driving. */
private const val FRONT_POLL_MS = 2_000L

/** Whether the paused phone screen asks for the accessibility service: not with a [helper], which tells the app in front itself. */
internal fun asksForAccessibility(helper: Boolean, service: Boolean) = !helper && !service
