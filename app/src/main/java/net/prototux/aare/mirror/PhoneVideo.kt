package net.prototux.aare.mirror

import android.app.PendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.KeyEvent
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import net.prototux.aare.media.CircleButton
import kotlinx.coroutines.delay
import androidx.compose.material.icons.rounded.Image
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import net.prototux.aare.ui.car.HostedAppSurface
import net.prototux.aare.launch.AppLauncher
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import net.prototux.aare.R
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.Placeholder
import net.prototux.aare.ui.car.LocalFullScreen

/**
 * A media app's own picture (its video), parked: [content] draws it with the app's player activity
 * (its media session's, or null), [shown] says whether it takes the art's place, [toggle] switches
 * between the two.
 */
class PhoneVideoSlot(val shown: Boolean, val toggle: () -> Unit, val content: @Composable (Modifier, PendingIntent?) -> Unit)

/** The [PhoneVideoSlot] for media apps; null while driving or where the phone screen can't show. */
val LocalPhoneVideo = compositionLocalOf<PhoneVideoSlot?> { null }

/**
 * The media app [packageName] itself, running on a display of exactly this size through the
 * privileged helper (Shizuku or root), whatever the phone shows: a video fills it, and touches go
 * to the app, nothing over the video. Its buttons, back to the cover ([onCover]) and full screen
 * over the whole car screen, rail included, fade out when left alone and come back on a touch.
 * Once it runs, its [player] is opened on the same display: started from the car, an app
 * otherwise shows its start screen rather than the video. The car's Back goes to the app. While it
 * runs here the app isn't on the phone's screen; it goes back there when this closes.
 */
@Composable
internal fun PhoneVideo(runtime: CarRuntime, appId: String, packageName: String, modifier: Modifier, onCover: () -> Unit, player: PendingIntent? = null) {
    val context = LocalContext.current
    val full = LocalFullScreen.current
    val available = remember { AppLauncher.available(context) }
    // Its own helper, apart from the phone apps' and other videos': releasing one leaves the others running.
    val launcher = remember(packageName) { AppLauncher(context, "video-$packageName") }
    // Null while starting, then whether the app runs here.
    var started by remember(packageName) { mutableStateOf<Boolean?>(null) }
    // Once per display: the media connection may only come after the app is up.
    var playerShown by remember(packageName) { mutableStateOf(false) }
    LaunchedEffect(started, player) {
        if (started == true && player != null && !playerShown) {
            playerShown = true
            withContext(Dispatchers.IO) { launcher.openPending(player) }
        }
    }
    // Gone (driving, another screen): so are its full-screen copy and its display.
    DisposableEffect(launcher) { onDispose { full?.value = null; launcher.release() } }
    // Back from the car (or the car screen preview) goes to the app; without it, to the cover. Back on
    // the player's first screen closes it: with no app left, the cover again, not an empty picture.
    val cover by rememberUpdatedState(onCover)
    val scope = rememberCoroutineScope()
    DisposableEffect(launcher) {
        val back: () -> Boolean = {
            if (launcher.connected) {
                val t = android.os.SystemClock.uptimeMillis()
                launcher.key(t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
                launcher.key(t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
                scope.launch {
                    delay(BACK_SETTLE_MS)
                    if (!withContext(Dispatchers.IO) { launcher.hasApp() }) cover()
                }
            } else cover()
            true
        }
        runtime.setScreenBack(appId, back)
        onDispose { runtime.clearScreenBack(appId, back) }
    }
    if (!available) {
        Placeholder(stringResource(R.string.standalone_need_root), loading = false, icon = Icons.Rounded.PhoneAndroid, modifier = modifier.background(Color.Black))
        return
    }
    VideoButtons(modifier, buttons = {
        CircleButton(Icons.Rounded.Image, stringResource(R.string.media_action_show_art), onClick = onCover)
        if (full != null) CircleButton(Icons.Rounded.Fullscreen, stringResource(R.string.video_full_screen), onClick = {
            full.value = {
                VideoButtons(Modifier.fillMaxSize(), buttons = {
                    CircleButton(Icons.Rounded.FullscreenExit, stringResource(R.string.video_exit_full_screen), onClick = { full.value = null })
                }) { HostedAppSurface(launcher, packageName, runtime.densityDpi, Modifier.fillMaxSize(), tablet = true) }
            }
        })
    }) {
        // Full screen, the one display moves onto the copy over everything.
        if (full?.value == null) HostedAppSurface(launcher, packageName, runtime.densityDpi, Modifier.fillMaxSize(), tablet = true) { started = it }
        when (started) {
            null -> Placeholder(stringResource(R.string.standalone_starting), loading = true)
            false -> Placeholder(stringResource(R.string.standalone_failed), loading = false, icon = Icons.Rounded.PhoneAndroid)
            true -> Unit
        }
    }
}

/**
 * A video with [buttons] stacked at its right edge, halfway down, fading out after a few seconds
 * untouched. A touch anywhere brings them back and still reaches the app below.
 */
@Composable
private fun VideoButtons(modifier: Modifier, buttons: @Composable () -> Unit, video: @Composable () -> Unit) {
    var touches by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(touches) { shown = true; delay(3_000); shown = false }
    Box(
        modifier.background(Color.Black).pointerInput(Unit) {
            // Seen before the app's touch forwarding, and not consumed.
            awaitPointerEventScope {
                while (true) if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.changedToDown() }) touches++
            }
        },
    ) {
        video()
        AnimatedVisibility(shown, Modifier.align(Alignment.CenterEnd).padding(12.dp), enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { buttons() }
        }
    }
}

/** How long an app takes to close after Back, before the video mode looks whether one is left. */
private const val BACK_SETTLE_MS = 700L
