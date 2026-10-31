package net.prototux.aare.mirror

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import net.prototux.aare.R
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.Placeholder
import net.prototux.aare.notify.CarNotificationListener

/**
 * The phone's screen on the car screen, for apps AARE can't host (Google Maps, Waze…). Touches
 * go back to the phone through [MirrorTouchService]. While driving it only shows a navigation
 * app, which needs [MirrorTouchService] to know the app in front. It is paused, with no frames
 * sent and no touch replayed, while the phone is locked.
 */
@Composable
internal fun MirrorScreen(runtime: CarRuntime, driving: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val guidance by CarNotificationListener.guidance.collectAsState()
    val foreground by MirrorTouchService.foregroundApp.collectAsState()
    val navApps = KNOWN_NAVIGATION + listOfNotNull(guidance?.packageName) + runtime.navigationPackages()
    // Driving: only when the app in front is known to be a navigation app. An unknown one (no
    // accessibility service) could be anything, a video included, even while a route is guiding.
    val locked = rememberKeyguardLocked()
    val allowed = !locked && (!driving || (foreground != null && foreground in navApps))
    var mirroring by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        runtime.screenBack = { MirrorTouchService.instance?.back() == true }
        onDispose { runtime.screenBack = null; PhoneMirror.detach() }
    }

    Box(modifier.background(Color.Black)) {
        if (allowed) {
            AndroidView(
                factory = { ctx ->
                    var surface: Surface? = null
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                surface = Surface(st)
                                mirroring = PhoneMirror.attach(runtime.projection(), surface!!, w, h, runtime.densityDpi)
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                surface?.let { mirroring = PhoneMirror.attach(runtime.projection(), it, w, h, runtime.densityDpi) }
                            }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                PhoneMirror.detach()
                                surface?.release(); surface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Touches are caught above the mirror and replayed on the phone.
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
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
                if (MirrorTouchService.instance == null) stringResource(R.string.mirror_driving_no_accessibility)
                else stringResource(R.string.mirror_driving_nav_only),
                loading = false, icon = Icons.Rounded.PauseCircle)
            !mirroring -> Placeholder(stringResource(R.string.mirror_need_car_audio),
                loading = false, icon = Icons.Rounded.PhoneAndroid, modifier = Modifier.background(Color.Black))
            MirrorTouchService.instance == null -> Placeholder(stringResource(R.string.mirror_need_accessibility),
                loading = false, icon = Icons.Rounded.TouchApp, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Whether the phone is locked (lock screen up or screen off), kept current from the screen and unlock broadcasts. */
@Composable
private fun rememberKeyguardLocked(): Boolean {
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

/** Replays a car gesture on the phone: car pixels to phone pixels, through the letterboxing. */
private fun replay(context: android.content.Context, viewW: Int, viewH: Int, points: List<Pair<Float, Float>>, durationMs: Long, slop: Float) {
    val touch = MirrorTouchService.instance ?: return
    val phone = PhoneMirror.phoneScreenSize(context)
    // The mirror keeps the phone's aspect ratio, centered.
    val scale = minOf(viewW.toFloat() / phone.x, viewH.toFloat() / phone.y)
    val ox = (viewW - phone.x * scale) / 2
    val oy = (viewH - phone.y * scale) / 2
    val mapped = points.map { (x, y) -> (x - ox) / scale to (y - oy) / scale }
        .filter { (x, y) -> x in 0f..phone.x.toFloat() && y in 0f..phone.y.toFloat() }
    if (mapped.isEmpty()) return
    val (x0, y0) = points.first()
    val moved = points.any { (x, y) -> kotlin.math.hypot(x - x0, y - y0) > slop }
    if (moved) touch.swipe(mapped.filterIndexed { i, _ -> i % 3 == 0 || i == mapped.lastIndex }, durationMs)
    else if (durationMs > 500) touch.swipe(listOf(mapped.first()), durationMs) // long press
    else touch.tap(mapped.first().first, mapped.first().second)
}

/** Navigation apps that can't be hosted, recognised even before they post guidance. */
private val KNOWN_NAVIGATION = setOf(
    "com.google.android.apps.maps",
    "com.waze",
    "com.here.app.maps",
    "com.sygic.aura",
    "com.tomtom.gplay.navapp",
)
