package net.prototux.aare.ui.car

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.R
import net.prototux.aare.media.Pill
import net.prototux.aare.media.Placeholder

/** The notification centre: the latest messages, newest first, with their actions. */
@Composable
internal fun NotificationsScreen(ui: CarUiState, modifier: Modifier) {
    LaunchedEffect(Unit) { ui.unseen = 0 }
    val driving = ui.drivingRestricted
    if (ui.history.isEmpty()) {
        Placeholder(stringResource(R.string.notif_empty), loading = false, icon = Icons.Rounded.Notifications,
            modifier = modifier.background(MaterialTheme.colorScheme.background))
        return
    }
    LazyColumn(
        modifier.background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.notif_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Pill(stringResource(R.string.notif_clear_all), selected = false, onClick = { ui.history.clear() })
            }
        }
        items(ui.history, key = { it.key }) { n ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = n.icon
                    if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(44.dp).clip(CircleShape))
                    else Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // While driving the text stays hidden: it can be read aloud.
                        Text(if (driving) stringResource(R.string.notif_new_message_placeholder) else n.text, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(DateUtils.getRelativeTimeSpanString(n.timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE).toString(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (n.actions.isNotEmpty()) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        n.actions.forEach { (label, act) -> Pill(label, selected = false, onClick = act) }
                    }
                }
            }
        }
    }
}

/** The bell next to the clock, with the number of unseen notifications. */
@Composable
internal fun NotificationBell(ui: CarUiState, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Notifications, stringResource(R.string.notif_title), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        if (ui.unseen > 0) {
            Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center) {
                Text(if (ui.unseen > 9) stringResource(R.string.notif_unseen_overflow) else "${ui.unseen}", color = Color.White, fontSize = 10.sp)
            }
        }
    }
}
