package net.prototux.aare.host

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.prototux.aare.R
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.media.Placeholder
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * A phone assistant without a car app, opened on the car screen through [AppLauncher] instead of
 * the phone (an opt-in setting). Positioned like [AssistantBar], but hosts the app's own voice UI
 * on a virtual display rather than a car app template: driving doesn't pause it (unlike
 * [net.prototux.aare.ui.car.StandaloneAppScreen]'s general phone apps), since an assistant is meant
 * to be used while driving and shows only its momentary voice UI, not a full interactive app.
 */
@Composable
internal fun StandaloneAssistantBar(packageName: String, runtime: CarRuntime, modifier: Modifier, onClose: () -> Unit) {
    val context = LocalContext.current
    val launcher = remember(packageName) { AppLauncher(context) }
    var started by remember(packageName) { mutableStateOf(false) }

    DisposableEffect(packageName) {
        val back: () -> Boolean = {
            val t = SystemClock.uptimeMillis()
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

    val d = LocalCarDimens.current
    val ui = runtime.ui
    val outer = when {
        ui.railBottom -> Modifier.padding(bottom = d.railWidth + 12.dp, start = 12.dp, end = 12.dp)
        ui.railOnRight -> Modifier.padding(end = d.railWidth + 12.dp, start = 12.dp, bottom = 12.dp)
        else -> Modifier.padding(start = d.railWidth + 12.dp, end = 12.dp, bottom = 12.dp)
    }
    Box(modifier.then(outer)) {
        Box(
            Modifier.widthIn(max = 960.dp).fillMaxWidth().height(420.dp)
                .clip(MaterialTheme.shapes.large).background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                val surface = Surface(st)
                                // connect() blocks (binds Shizuku / spawns su), so do it off the main thread.
                                Thread {
                                    val connected = launcher.connect()
                                    val id = if (connected) {
                                        launcher.start(surface, w, h, runtime.densityDpi, packageName, keepDisplayAwake = false)
                                    } else -1
                                    Handler(Looper.getMainLooper()).post {
                                        if (id >= 0) started = true
                                        else { launcher.release(); runtime.assistantStandaloneFailed(packageName) }
                                    }
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
            // Forward the car's touches to the assistant's display (single pointer, as for standalone apps).
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
            if (!started) Placeholder(stringResource(R.string.standalone_starting), loading = true)
            Box(
                Modifier.align(Alignment.TopEnd).size(d.touchTarget).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.common_close), tint = Color.White)
            }
        }
    }
}
