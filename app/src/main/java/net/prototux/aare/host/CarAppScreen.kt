package net.prototux.aare.host

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.car.app.model.CarIcon
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import net.prototux.aare.R
import net.prototux.aare.host.templates.RenderTemplate
import net.prototux.aare.host.templates.TemplateHost

/**
 * One hosted car app on the car screen: its current template, its alerts, and loading/error
 * states. [compact] is the dashboard card version (a navigation app's map and next turn).
 */
@Composable
fun CarAppScreen(conn: CarAppConnection, runtime: CarRuntime, modifier: Modifier = Modifier, compact: Boolean = false) {
    DisposableEffect(conn) {
        runtime.acquireVisible(conn)
        onDispose { runtime.releaseVisible(conn) }
    }
    val host = remember(conn) { ConnectionTemplateHost(conn, runtime) }
    Box(modifier) {
        val template = conn.template
        // The map surface outlives template changes: recreating it with each new template (as a
        // navigation app sends them every second or so) blanks the map until the app redraws.
        // Only an app with a SurfaceCallback draws on it; otherwise (PlaceListMapTemplate expects
        // the host to draw the map, which AARE can't) a plain background instead of a black hole.
        if (usesMap(template)) {
            if (conn.wantsSurface) host.MapSurface(Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer))
        }
        when {
            conn.status == CarAppConnection.Status.FAILED -> AppMessage(conn, conn.error ?: "Something went wrong", retry = { conn.connect() })
            template == null -> AppMessage(conn, null, retry = null)
            // A new screen (new template id) starts fresh; refreshes keep scroll position and input.
            else -> androidx.compose.runtime.key(conn.templateId) { RenderTemplate(template, host, Modifier.fillMaxSize()) }
        }
        conn.alert?.let { AlertCard(it, conn, host, Modifier.align(Alignment.TopCenter)) }
    }
}

/** Templates drawn over the app's own map surface (see MapScaffold). */
private fun usesMap(t: Template?): Boolean = t is NavigationTemplate || t is MapWithContentTemplate || t is PlaceListMapTemplate ||
    t is PlaceListNavigationTemplate || t is RoutePreviewNavigationTemplate || t is MapTemplate

/** Loading spinner or error with the app's icon. */
@Composable
private fun AppMessage(conn: CarAppConnection, message: String?, retry: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        conn.info.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(72.dp).clip(CircleShape)) }
        Spacer(Modifier.height(20.dp))
        if (message == null) {
            CircularProgressIndicator()
        } else {
            Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
            if (retry != null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Try again", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = retry).padding(horizontal = 32.dp, vertical = 18.dp),
                )
            }
        }
    }
}

/** Car app alerts (e.g. "Accept new route?"): a card with up to two actions and an optional timeout. */
@Composable
private fun AlertCard(alert: Alert, conn: CarAppConnection, host: TemplateHost, modifier: Modifier) {
    LaunchedEffect(alert) {
        if (alert.durationMillis > 0) {
            delay(alert.durationMillis)
            runCatching { alert.callbackDelegate?.sendCancel(AlertCallback.REASON_TIMEOUT, host.done) }
            conn.dismissAlert()
        }
    }
    Column(
        modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            alert.icon?.let { icon -> host.loadIcon(icon, 96)?.let { Image(it, null, Modifier.size(48.dp)); Spacer(Modifier.width(16.dp)) } }
            Column(Modifier.weight(1f)) {
                Text(alert.title.toString(), style = MaterialTheme.typography.titleMedium)
                alert.subtitle?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (alert.actions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                alert.actions.forEach { action ->
                    Text(
                        action.title?.toString() ?: "OK", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable {
                                runCatching { action.onClickDelegate?.sendClick(host.done) }
                                conn.dismissAlert()
                            }.padding(horizontal = 28.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * A navigation app's instrument-cluster session: only its map surface, as Android Auto shows it
 * on the cluster (the app draws the map and its own turn hints on it).
 */
@Composable
fun CarAppClusterScreen(conn: CarAppConnection, runtime: CarRuntime, modifier: Modifier = Modifier) {
    DisposableEffect(conn) {
        runtime.acquireVisible(conn)
        onDispose { runtime.releaseVisible(conn) }
    }
    val host = remember(conn) { ConnectionTemplateHost(conn, runtime) }
    Box(modifier) { host.MapSurface(Modifier.fillMaxSize()) }
}

/** Bridges the renderer to one app connection. */
private class ConnectionTemplateHost(val conn: CarAppConnection, val runtime: CarRuntime) : TemplateHost {
    override val driving: Boolean get() = runtime.ui.drivingRestricted
    override val night: Boolean get() = runtime.ui.night
    override val appLabel: String get() = conn.info.label
    override fun contentLimit(type: Int): Int = runtime.contentLimit(type)
    override val done = CarAppConnection.templateDone
    override fun back() = conn.back()
    override fun loadIcon(icon: CarIcon, sizePx: Int): ImageBitmap? =
        if (icon.type == CarIcon.TYPE_APP_ICON) appIcon(sizePx) else runtime.icons.load(icon, sizePx, conn.info.packageName)

    /** CarIcon.APP_ICON (Action.APP_ICON headers): the launcher icon we already have, scaled. */
    private fun appIcon(sizePx: Int): ImageBitmap? {
        val b = conn.info.icon ?: return null
        val scale = sizePx.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * scale).toInt()), maxOf(1, (b.height * scale).toInt()), true).asImageBitmap()
    }
    override fun showParkedOnlyMessage() = runtime.notice(runtime.context.getString(R.string.notif_parked_only_title), runtime.context.getString(R.string.notif_parked_only_text))

    @Composable
    override fun MapSurface(modifier: Modifier) {
        val owner = remember { Any() }
        val dpi = runtime.densityDpi
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            conn.surfaceAvailable(owner, holder.surface, width, height, dpi)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) = conn.surfaceDestroyed(owner)
                    })
                }
            },
        )
        DisposableEffect(owner) { onDispose { conn.surfaceDestroyed(owner) } }
    }

    override fun setVisibleArea(left: Int, top: Int, right: Int, bottom: Int) = conn.setVisibleArea(Rect(left, top, right, bottom))
    override fun setStableArea(left: Int, top: Int, right: Int, bottom: Int) = conn.setStableArea(Rect(left, top, right, bottom))
    override fun mapScroll(dx: Float, dy: Float) { conn.scroll(dx, dy) }
    override fun mapFling(vx: Float, vy: Float) { conn.fling(vx, vy) }
    override fun mapScale(focusX: Float, focusY: Float, factor: Float) { conn.scale(focusX, focusY, factor) }
    override fun mapClick(x: Float, y: Float) { conn.click(x, y) }
}
