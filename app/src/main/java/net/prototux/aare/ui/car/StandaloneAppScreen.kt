package net.prototux.aare.ui.car

import android.graphics.SurfaceTexture
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.media.Placeholder

/**
 * Hosts an ordinary phone app on the car screen (NewPipe, an emulator, a browser app…). A
 * [TextureView] gives its surface to the privileged [AppLauncher], which puts a virtual display
 * there, launches [packageName] on it, and receives the car's touches we forward.
 *
 * Not an Android Auto app, so — like the phone-screen mirror — it is paused while driving
 * ([driving]; false for a navigation app, which runs on). The
 * privileged core is device-tested; here it just wires the surface, touches and lifecycle.
 */
@Composable
internal fun StandaloneAppScreen(runtime: CarRuntime, driving: Boolean, packageName: String, modifier: Modifier, backId: String = CarRuntime.standaloneId(packageName), card: Boolean = false) {
    val context = LocalContext.current
    // A helper per app: a hosted app and a media app's video each keep their own display. The map card
    // has one of its own, laid out as on a tablet so the map keeps most of the small card.
    val launcher = remember(packageName) { AppLauncher(context, (if (card) "card-" else "launch-") + packageName) }
    var started by remember(packageName) { mutableStateOf(false) }

    DisposableEffect(packageName) {
        // Hardware / head-unit Back goes to the launched app.
        val back: () -> Boolean = {
            val t = android.os.SystemClock.uptimeMillis()
            launcher.key(t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
            launcher.key(t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
            launcher.connected
        }
        runtime.setScreenBack(backId, back)
        onDispose {
            runtime.clearScreenBack(backId, back)
            // A navigation it never got to (left before the app was up) isn't started when it is opened later by hand.
            // Not by the map card: leaving it is how the request reaches the app full screen.
            if (!card && runtime.ui.navigationRequest?.first == packageName) runtime.ui.navigationRequest = null
            launcher.release()
        }
    }

    Box(modifier.background(Color.Black)) {
        if (!driving) {
            HostedAppSurface(launcher, packageName, runtime.densityDpi, Modifier.fillMaxSize(), tablet = card) { started = it }
            // A navigation asked for in this app (an event on the calendar card, say): its address, opened once it runs.
            // Not by the map card: the request opens the app full screen, which takes the card, and the address, away.
            LaunchedEffect(started, runtime.ui.navigationRequest) {
                val request = runtime.ui.navigationRequest?.takeIf { started && !card && it.first == packageName } ?: return@LaunchedEffect
                // Cleared only once done: the request is this effect's key, and clearing it cancels the effect.
                try { withContext(Dispatchers.IO) { launcher.openLink(request.second, packageName) } }
                finally { if (runtime.ui.navigationRequest == request) runtime.ui.navigationRequest = null }
            }
            if (!started) Placeholder(stringResource(R.string.standalone_starting), loading = true)
        } else {
            Placeholder(stringResource(R.string.standalone_paused_driving), loading = false, icon = Icons.Rounded.PauseCircle)
        }
    }
}

/**
 * [packageName] running on the privileged [launcher]'s virtual display, drawn here at this size,
 * with the car's touches forwarded to it. The first surface launches the app; a later one (the
 * same app shown elsewhere, full screen say) only moves the display onto it. [onStarted] says
 * whether the launch worked. With [tablet], the display's density is lowered until it is at
 * least 600 dp each way: apps then lay out as on a tablet, and many stop locking themselves to
 * portrait, which on a short wide car pane left them a sliver in the middle.
 */
@Composable
internal fun HostedAppSurface(launcher: AppLauncher, packageName: String, dpi: Int, modifier: Modifier, tablet: Boolean = false, onStarted: (Boolean) -> Unit = {}) {
    fun dpiFor(w: Int, h: Int) = if (tablet) hostedDpi(w, h, dpi) else dpi
    Box(modifier) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val surface = Surface(st)
                            // connect() blocks (binds Shizuku / spawns su), so do it off the main thread.
                            Thread {
                                // Already running (the kept assistant): only moved onto this surface.
                                if (launcher.connected) { launcher.resize(surface, w, h, dpiFor(w, h)); onStarted(true) }
                                else if (launcher.connect()) onStarted(launcher.start(surface, w, h, dpiFor(w, h), packageName, Settings(ctx).standaloneKeepScreenAwake) >= 0)
                            }.apply { isDaemon = true }.start()
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            launcher.resize(Surface(st), w, h, dpiFor(w, h))
                        }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Forward the car's touches to the launched app's display, every finger: two pinch to zoom a map.
        Box(Modifier.fillMaxSize().pointerInput(packageName) {
            awaitPointerEventScope {
                var fingers = emptyList<Finger>()
                var downTime = 0L
                val ids = HashMap<androidx.compose.ui.input.pointer.PointerId, Int>()
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    ids.keys.retainAll(pressed.mapTo(HashSet()) { it.id })
                    // A new finger gets an id no finger had a moment ago: one lifting just now still counts as down until its UP.
                    val now = pressed.map { c -> Finger(ids.getOrPut(c.id) { (0..ids.size + fingers.size).first { id -> id !in ids.values && fingers.none { it.id == id } } }, c.position.x, c.position.y) }
                    val t = event.changes.maxOf { it.uptimeMillis }
                    if (fingers.isEmpty() && now.isNotEmpty()) downTime = t
                    touchEvents(fingers, now).forEach { launcher.touches(downTime, t, it) }
                    fingers = now
                    event.changes.forEach { it.consume() }
                }
            }
        })
    }
}

/** A finger on the car screen: its pointer [id] (0 for the first) and where it is. */
data class Finger(val id: Int, val x: Float, val y: Float)

/** A touch event for an app's display: its MotionEvent [action] and every finger down then, in order. */
data class Touch(val action: Int, val fingers: List<Finger>)

/**
 * The touch events from the fingers [before] to those [after], as Android expects them: every event
 * names exactly the fingers Android counts as down then, in one order. First a MOVE if one moved
 * (lifted ones too, still down until their UP), then fingers lifted (a POINTER_UP, the last an UP),
 * then new fingers (the first a DOWN, any more a POINTER_DOWN). Android drops an event naming other
 * fingers, and from then on every event, until the app took no touch at all any more.
 */
internal fun touchEvents(before: List<Finger>, after: List<Finger>): List<Touch> = buildList {
    fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    // Those Android counts as down, where they are now (a lifted one where it was last).
    var held = before.map { b -> after.firstOrNull { it.id == b.id } ?: b }
    if (held != before) add(Touch(MotionEvent.ACTION_MOVE, held))
    for (f in held.filter { h -> after.none { it.id == h.id } }) {
        add(Touch(if (held.size == 1) MotionEvent.ACTION_UP else pointer(MotionEvent.ACTION_POINTER_UP, held.indexOf(f)), held))
        held = held - f
    }
    for (f in after.filter { n -> before.none { it.id == n.id } }) {
        held = held + f
        add(Touch(if (held.size == 1) MotionEvent.ACTION_DOWN else pointer(MotionEvent.ACTION_POINTER_DOWN, held.size - 1), held))
    }
}

/** The density for a [w]×[h] pixel display to be at least 600 dp each way, no higher than [dpi] and no lower than the helper's floor of 120. */
internal fun hostedDpi(w: Int, h: Int, dpi: Int): Int = minOf(dpi, minOf(w, h) * 160 / 600).coerceAtLeast(120)
