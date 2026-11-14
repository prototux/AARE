package net.prototux.aare.ui.car

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.rememberUpdatedState
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
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.media.Placeholder
import java.util.concurrent.atomic.AtomicBoolean

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
    val launcher = remember(packageName) { AppLauncher(context) }
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
            LaunchedAppSurface(launcher, packageName, runtime.densityDpi, Modifier.fillMaxSize(),
                start = { surface, w, h -> launcher.start(surface, w, h, runtime.densityDpi, packageName, runtime.settings.standaloneKeepScreenAwake) },
                onStarted = { started = it })
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
 * A launched app's picture and touches: a [TextureView] whose surface [start] hands to [launcher]
 * (after connecting it, which blocks, so off the main thread), and the car's touches forwarded to
 * the app's display. [onStarted] gets whether it started, on the main thread.
 *
 * The connection can finish after this left the screen (the app closed, or driving began), and
 * after its owner released [launcher]: then nothing is started, or what was started is released
 * again, and [onStarted] isn't called.
 */
@Composable
internal fun LaunchedAppSurface(
    launcher: AppLauncher,
    packageName: String,
    /** The car screen's density, for the display. */
    dpi: Int,
    modifier: Modifier,
    /** Creates the display into the surface (width, height) and launches the app: its id, or -1. */
    start: (Surface, Int, Int) -> Int,
    onStarted: (Boolean) -> Unit,
) {
    val shown = remember(launcher) { AtomicBoolean(true) }
    DisposableEffect(shown) {
        shown.set(true)
        onDispose { shown.set(false) }
    }
    val latestStart by rememberUpdatedState(start)
    val latestOnStarted by rememberUpdatedState(onStarted)
    val latestDpi by rememberUpdatedState(dpi)
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        val surface = Surface(st)
                        Thread {
                            val id = if (shown.get() && launcher.connect() && shown.get()) latestStart(surface, w, h) else -1
                            if (!shown.get()) {
                                // Gone meanwhile: its owner's release() may have run before this connected or started.
                                launcher.release()
                                return@Thread
                            }
                            Handler(Looper.getMainLooper()).post { if (shown.get()) latestOnStarted(id >= 0) }
                        }.apply { isDaemon = true }.start()
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        launcher.resize(Surface(st), w, h, latestDpi)
                    }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier,
    )
    // Forward the car's touches to the launched app's display (single pointer for now).
    Box(modifier.pointerInput(packageName) {
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
