package net.prototux.aare.host

import net.prototux.aare.ui.car.carClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.media.Placeholder
import net.prototux.aare.ui.car.LaunchedAppSurface
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * A phone assistant without a car app, opened on the car screen through [AppLauncher] instead of
 * the phone (an opt-in setting). Positioned like [AssistantBar], but hosts the app's own voice UI
 * (ACTION_VOICE_COMMAND / ACTION_ASSIST) on a virtual display rather than a car app template. It is
 * an ordinary phone app, so parked only like [net.prototux.aare.ui.car.StandaloneAppScreen]'s:
 * the runtime closes it when driving starts (not in passenger mode) and opens the assistant on the
 * phone instead while driving.
 */
@Composable
internal fun StandaloneAssistantBar(packageName: String, runtime: CarRuntime, modifier: Modifier, onClose: () -> Unit) {
    val context = LocalContext.current
    val launcher = remember(packageName) { AppLauncher(context) }
    var started by remember(packageName) { mutableStateOf(false) }

    // No Back handler of its own: the car's Back closes the bar (CarRuntime.back), and one installed
    // here would replace the handler of the screen underneath.
    DisposableEffect(packageName) {
        onDispose { launcher.release() }
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
            LaunchedAppSurface(launcher, packageName, runtime.densityDpi, Modifier.fillMaxSize(),
                start = { surface, w, h -> launcher.startAssistant(surface, w, h, runtime.densityDpi, packageName) },
                onStarted = { ok ->
                    if (ok) started = true
                    else { launcher.release(); runtime.assistantStandaloneFailed(packageName) }
                })
            if (!started) Placeholder(stringResource(R.string.standalone_starting), loading = true)
            Box(
                Modifier.align(Alignment.TopEnd).size(d.touchTarget).clip(CircleShape).carClickable(CircleShape, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.common_close), tint = Color.White)
            }
        }
    }
}
