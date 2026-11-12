package net.prototux.aare.ui.car

import android.graphics.SurfaceTexture
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
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
    var started by remember(packageName) { mutableStateOf<Boolean?>(null) }

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
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        // Forward the native event intact. It contains every active pointer and
                        // the action index required for multi-touch gesture recognition.
                        setOnTouchListener { _, event -> launcher.touch(event); true }
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                val surface = Surface(st)
                                // connect() blocks (binds Shizuku / spawns su), so do it off the main thread.
                                Thread {
                                    if (launcher.connect()) {
                                        val id = launcher.start(surface, w, h, runtime.densityDpi, packageName)
                                        started = id >= 0
                                    } else started = false
                                }.apply { isDaemon = true }.start()
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                launcher.resize(Surface(st), w, h, runtime.densityDpi)
                            }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // A quiet reminder that this isn't an Android Auto app.
            Text(
                stringResource(R.string.standalone_not_native),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 12.dp, vertical = 3.dp),
            )
            when (started) {
                null -> Placeholder(stringResource(R.string.standalone_starting), loading = true)
                false -> Placeholder(stringResource(R.string.carapp_error_start, packageName), loading = false)
                true -> Unit
            }
        } else {
            Placeholder(stringResource(R.string.standalone_paused_driving), loading = false, icon = Icons.Rounded.PauseCircle)
        }
    }
}
