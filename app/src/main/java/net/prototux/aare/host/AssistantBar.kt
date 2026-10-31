package net.prototux.aare.host

import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.MessageTemplate
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.prototux.aare.R
import net.prototux.aare.media.Pill
import net.prototux.aare.ui.car.AppIcon
import net.prototux.aare.ui.car.CarAppEntry
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * The voice assistant as Android Auto shows it: a bar along the bottom of whatever is on screen,
 * with the listening state, what was heard and the answer, instead of the app full screen.
 *
 * It shows the assistant's own car screen when that is a MessageTemplate (Lapin's is: a title,
 * the text, and Talk/Stop/Yes/No buttons); anything richer opens the app full screen. It closes
 * by itself once the assistant has finished and stays quiet for a while.
 */
@Composable
internal fun AssistantBar(entry: CarAppEntry, conn: CarAppConnection, runtime: CarRuntime, modifier: Modifier, onClose: () -> Unit, onExpand: () -> Unit) {
    DisposableEffect(conn) {
        runtime.acquireVisible(conn)
        onDispose { runtime.releaseVisible(conn) }
    }
    val template = conn.template
    val listening = runtime.ui.listening
    if (template != null && template !is MessageTemplate) {
        LaunchedEffect(Unit) { onExpand() }
        return
    }
    val message = template as MessageTemplate?
    val busy = message == null || message.isLoading
    // Close once the assistant is done: not listening, not working, the answer left up a while.
    LaunchedEffect(conn.template, listening) {
        if (!listening && !busy) {
            delay(if ((message?.message?.text()?.length ?: 0) > 160) 15_000 else 9_000)
            onClose()
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
        Column(
            Modifier.widthIn(max = 960.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onExpand).padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ListeningIcon(entry, listening)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val title = message?.header?.title?.text() ?: message?.title?.text() ?: entry.label
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val text = message?.message?.text().orEmpty()
                    if (text.isNotBlank()) {
                        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    } else if (listening) {
                        Text(stringResource(R.string.assistant_listening), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(Modifier.size(d.touchTarget).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (busy && !listening) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
            }
            val actions = message?.actions.orEmpty().filter { it.title != null }
            if (actions.isNotEmpty()) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    actions.take(3).forEachIndexed { i, a -> ActionPill(a, primary = i == 0) }
                }
            }
        }
    }
}

@Composable
private fun ListeningIcon(entry: CarAppEntry, listening: Boolean) {
    val pulse = if (listening) {
        val p by rememberInfiniteTransition(label = "listen").animateFloat(1f, 1.18f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
        p
    } else 1f
    val ring = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    Box(Modifier.size(64.dp).scale(pulse).border(3.dp, ring, CircleShape).padding(6.dp), contentAlignment = Alignment.Center) {
        AppIcon(entry, 48)
    }
}

@Composable
private fun ActionPill(action: Action, primary: Boolean) {
    Pill(action.title?.text().orEmpty(), selected = primary, onClick = {
        runCatching { action.onClickDelegate?.sendClick(CarAppConnection.templateDone) }
    })
}

private fun CarText.text(): String = toCharSequence().toString()
