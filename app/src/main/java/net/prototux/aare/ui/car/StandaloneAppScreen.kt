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
 * Not an Android Auto app, so — like the phone-screen mirror — it is paused while driving. The
 * privileged core is device-tested; here it just wires the surface, touches and lifecycle.
 */
@Composable
internal fun StandaloneAppScreen(runtime: CarRuntime, driving: Boolean, packageName: String, modifier: Modifier) {
    val context = LocalContext.current
    // A helper per app: a hosted app and a media app's video each keep their own display.
    val launcher = remember(packageName) { AppLauncher(context, "launch-$packageName") }
    var started by remember(packageName) { mutableStateOf(false) }

    DisposableEffect(packageName) {
        // Hardware / head-unit Back goes to the launched app.
        val back: () -> Boolean = {
            val t = android.os.SystemClock.uptimeMillis()
            launcher.key(t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
            launcher.key(t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
            launcher.connected
        }
        runtime.screenBack = back
        onDispose {
            if (runtime.screenBack === back) runtime.screenBack = null
            launcher.release()
        }
    }

    Box(modifier.background(Color.Black)) {
        if (!driving) {
            HostedAppSurface(launcher, packageName, runtime.densityDpi, Modifier.fillMaxSize()) { started = it }
            // A quiet reminder that this isn't an Android Auto app.
            Text(
                stringResource(R.string.standalone_not_native),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 12.dp, vertical = 3.dp),
            )
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
                                if (launcher.connected) launcher.resize(surface, w, h, dpiFor(w, h))
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
        // Forward the car's touches to the launched app's display (single pointer for now).
        Box(Modifier.fillMaxSize().pointerInput(packageName) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val t0 = down.uptimeMillis
                launcher.touch(t0, t0, MotionEvent.ACTION_DOWN, down.position.x, down.position.y, 0)
                down.consume()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) {
                        launcher.touch(t0, change.uptimeMillis, MotionEvent.ACTION_MOVE, change.position.x, change.position.y, 0)
                    } else {
                        launcher.touch(t0, change.uptimeMillis, MotionEvent.ACTION_UP, change.position.x, change.position.y, 0)
                        change.consume()
                        break
                    }
                    change.consume()
                }
            }
        })
    }
}

/** The density for a [w]×[h] pixel display to be at least 600 dp each way, no higher than [dpi] and no lower than the helper's floor of 120. */
internal fun hostedDpi(w: Int, h: Int, dpi: Int): Int = minOf(dpi, minOf(w, h) * 160 / 600).coerceAtLeast(120)
