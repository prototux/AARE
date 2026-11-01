package net.prototux.aare.notify

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.host.templates.CarKeyboard
import net.prototux.aare.host.templates.CarTextField
import net.prototux.aare.host.templates.KeyboardMode
import net.prototux.aare.host.templates.KeyboardSink
import net.prototux.aare.media.CircleButton
import net.prototux.aare.media.Pill
import net.prototux.aare.media.Placeholder
import net.prototux.aare.ui.car.AppIcon
import net.prototux.aare.ui.car.CarAppEntry
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * A messaging app on the car screen, as Android Auto shows it: the conversations of its
 * notifications, read aloud, marked as read, or answered with the car keyboard when parked.
 * Message text is hidden while driving; it is read aloud instead.
 */
@Composable
internal fun MessagingScreen(app: CarAppEntry, driving: Boolean, runtime: CarRuntime, modifier: Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CarNotificationListener.rebind(context) }
    val changes by CarNotificationListener.changes.collectAsState()
    val listener = CarNotificationListener.instance
    val conversations by produceState<List<Conversation>?>(null, changes, app.packageName, listener) {
        value = listener?.let { l -> withContext(Dispatchers.IO) { l.conversations(app.packageName) } }
    }
    var openKey by remember(app.id) { mutableStateOf<String?>(null) }
    var replying by remember(app.id) { mutableStateOf(false) }
    val open = conversations?.firstOrNull { it.key == openKey }

    // The car keyboard is for parked use only.
    LaunchedEffect(driving) { if (driving) replying = false }
    // The open conversation went away (read on the phone, or marked as read): back to the list.
    LaunchedEffect(conversations, openKey) { if (openKey != null && conversations != null && open == null) { openKey = null; replying = false } }
    DisposableEffect(app.id) {
        runtime.screenBack = {
            when {
                replying -> { replying = false; true }
                openKey != null -> { openKey = null; true }
                else -> false
            }
        }
        onDispose { runtime.screenBack = null }
    }

    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        TopBar(app, open?.title, onBack = if (open != null) ({ if (replying) replying = false else openKey = null }) else null)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !CarNotificationListener.accessGranted(context) ->
                    Placeholder(stringResource(R.string.msg_no_access), loading = false, icon = Icons.Rounded.Forum)
                // Access granted, listener not bound (yet): nothing to show, it reconnects.
                listener == null -> Placeholder(stringResource(R.string.msg_listener_not_ready), loading = false, icon = Icons.Rounded.Forum)
                conversations == null -> Placeholder(stringResource(R.string.common_loading), loading = true)
                open != null && replying -> ReplyView(open, onSent = { ok ->
                    replying = false
                    runtime.notice(open.title, if (ok) context.getString(R.string.msg_reply_sent) else context.getString(R.string.msg_reply_rejected, app.label))
                }, onCancel = { replying = false })
                open != null -> ConversationView(open, driving, onReply = { replying = true }, onMarkedRead = { openKey = null })
                conversations!!.isEmpty() -> Placeholder(stringResource(R.string.msg_no_new_messages, app.label), loading = false, icon = Icons.Rounded.Forum)
                else -> ConversationList(conversations!!, app, driving) { openKey = it.key }
            }
        }
    }
}

@Composable
private fun TopBar(app: CarAppEntry, title: String?, onBack: (() -> Unit)?) {
    val d = LocalCarDimens.current
    Row(
        Modifier.fillMaxWidth().height(d.touchTarget + 16.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) CircleButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), onClick = onBack)
        else Box(Modifier.size(d.touchTarget), contentAlignment = Alignment.Center) { AppIcon(app, 48) }
        Spacer(Modifier.width(12.dp))
        Text(title ?: app.label, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConversationList(list: List<Conversation>, app: CarAppEntry, driving: Boolean, onOpen: (Conversation) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(list, key = { it.key }) { c ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clip(RoundedCornerShape(20.dp))
                    .clickable { onOpen(c) }.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(c, app)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val preview = if (driving) messageCount(c.messages.size) else c.messages.last().text
                    Text(preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                Text(ago(c.timeMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConversationView(c: Conversation, driving: Boolean, onReply: () -> Unit, onMarkedRead: () -> Unit) {
    val listener = CarNotificationListener.instance
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (driving) {
                Placeholder(stringResource(R.string.msg_reading_aloud_hint, messageCount(c.messages.size)), loading = false, icon = Icons.Rounded.RecordVoiceOver)
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(c.messages) { m ->
                        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp)) {
                            if (m.sender.isNotEmpty() && m.sender != c.title) {
                                Text(m.sender, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(m.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(stringResource(R.string.msg_action_read_aloud), selected = true, icon = Icons.Rounded.RecordVoiceOver, onClick = { listener?.readAloud(c) })
            c.markRead?.let { pi -> Pill(stringResource(R.string.msg_action_mark_read), selected = false, icon = Icons.Rounded.DoneAll, onClick = { runCatching { pi.send() }; onMarkedRead() }) }
            if (c.canReply && !driving) Pill(stringResource(R.string.msg_action_reply), selected = false, icon = Icons.AutoMirrored.Rounded.Reply, onClick = onReply)
        }
    }
}

@Composable
private fun ReplyView(c: Conversation, onSent: (Boolean) -> Unit, onCancel: () -> Unit) {
    var draft by remember(c.key) { mutableStateOf("") }
    val sink = remember(c.key) {
        object : KeyboardSink {
            override fun type(text: String) { draft += text }
            override fun backspace() { draft = draft.dropLast(1) }
            override fun submit() {
                if (draft.isBlank()) return
                onSent(CarNotificationListener.instance?.reply(c, draft.trim()) == true)
            }
            override fun hide() = onCancel()
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        CarTextField(draft, stringResource(R.string.msg_reply_hint, c.title), editing = true, onFocus = {}, onClear = { draft = "" }, leadingIcon = null)
        Spacer(Modifier.weight(1f))
        CarKeyboard(KeyboardMode.Text, sink, Icons.AutoMirrored.Rounded.Send, Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun Avatar(c: Conversation, app: CarAppEntry) {
    val icon = c.icon
    if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(56.dp).clip(CircleShape))
    else Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { AppIcon(app, 48) }
}

@Composable
private fun messageCount(n: Int) = if (n == 1) stringResource(R.string.msg_message_count_one) else stringResource(R.string.msg_message_count_many, n)

private fun ago(timeMs: Long): String =
    DateUtils.getRelativeTimeSpanString(timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
