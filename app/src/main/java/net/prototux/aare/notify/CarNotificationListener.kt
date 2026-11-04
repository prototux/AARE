package net.prototux.aare.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.prototux.aare.R
import net.prototux.aare.media.ArtLoader
import java.util.Locale

/** One conversation, from a messaging app's MessagingStyle notification. */
class Conversation(
    /** The notification's key. */
    val key: String,
    val packageName: String,
    /** The contact or group name. */
    val title: String,
    /** Oldest first. */
    val messages: List<Line>,
    val icon: Bitmap?,
    val timeMs: Long,
    val markRead: PendingIntent?,
    /** The reply action, with its RemoteInput. */
    val reply: NotificationCompat.Action?,
) {
    /** [fromUser]: written by the phone's owner (a reply), not by the contact. */
    class Line(val sender: String, val text: String, val timeMs: Long, val fromUser: Boolean = false)

    val canReply: Boolean get() = reply?.remoteInputs?.isNotEmpty() == true

    /** What is read aloud: the latest messages, each with its sender. */
    fun spoken(): String = messages.takeLast(5).joinToString(". ") { if (it.sender.isNotEmpty() && it.sender != title) "${it.sender}: ${it.text}" else it.text }
        .let { "$title. $it" }
}

/**
 * Messages in the car. Conversations posted as MessagingStyle notifications (what Android Auto
 * reads too) appear as banners on the car screen, with "Read aloud" and "Mark as read", and in
 * each messaging app's screen (see MessagingScreen). Navigation apps' guidance notifications
 * become [guidance], for the dashboard and the instrument cluster.
 *
 * Also what lets the media hub see other apps' media sessions (notification access).
 */
class CarNotificationListener : NotificationListenerService() {
    private var tts: TextToSpeech? = null
    /** The newest message seen per notification key (bounded), so re-posts aren't shown again. */
    private val forwarded = object : LinkedHashMap<String, Conversation.Line>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Conversation.Line>?) = size > 256
    }
    /** The dialer notification that named [caller]: only its removal clears the name. */
    private var callerKey: String? = null

    override fun onListenerConnected() {
        instance = this
        _changes.update { it + 1 }
        refreshGuidance()
    }

    override fun onListenerDisconnected() {
        instance = null
        _guidance.value = null
        // Its removal would not reach us any more.
        callerKey = null
        _caller.value = null
    }

    override fun onDestroy() {
        tts?.shutdown()
        instance = null
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        _changes.update { it + 1 }
        if (sbn.key == callerKey) { callerKey = null; _caller.value = null }
        if (sbn.key == _guidance.value?.key) refreshGuidance()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        _changes.update { it + 1 }
        // The dialer's call notification names the caller (no call log permission needed for that).
        // Only the dialer's: the car shows this name for the cellular call, so any other app could spoof it.
        if (sbn.notification.category == Notification.CATEGORY_CALL && isDialer(sbn.packageName)) {
            sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }?.let {
                _caller.value = it
                callerKey = sbn.key
            }
            return
        }
        if (sbn.packageName != packageName && PhoneGuidance.isGuidance(sbn)) {
            PhoneGuidance.from(this, sbn)?.let { _guidance.value = it }
            return
        }
        val sink = sink ?: return
        val c = parse(sbn) ?: return
        val last = c.messages.last()
        // Apps re-post the whole conversation on every change: after the user's own reply, when
        // marked read, or just refreshed. Only a last message other than the one already seen is
        // news (not "newer": senders' clocks differ), and never one the user wrote (an auto-reply
        // would otherwise show as from the contact).
        val prev = forwarded[sbn.key]
        if (prev != null && last.timeMs == prev.timeMs && last.text == prev.text && last.sender == prev.sender) return
        forwarded[sbn.key] = last
        if (last.fromUser) return
        val actions = buildList<Pair<String, () -> Unit>> {
            add(getString(R.string.msg_action_read_aloud) to { speak(if (last.sender.isNotEmpty()) "${last.sender}. ${last.text}" else last.text) })
            c.markRead?.let { pi -> add(getString(R.string.msg_action_mark_read) to { runCatching { pi.send() } }) }
        }
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val title = if (last.sender.isNotEmpty() && last.sender != c.title) "${c.title} · ${last.sender}" else c.title.ifEmpty { app }
        val group = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)?.isGroupConversation == true
        sink(Message(sbn.key, title, last.text, c.icon, actions, group) { speak(if (last.sender.isNotEmpty()) "${last.sender}. ${last.text}" else last.text) })
    }

    /** The default dialer, or the system one (some phones post the call notification from it). */
    private fun isDialer(pkg: String): Boolean = runCatching {
        val telecom = getSystemService(TelecomManager::class.java) ?: return@runCatching false
        pkg == telecom.defaultDialerPackage ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pkg == telecom.systemDialerPackage)
    }.getOrDefault(false)

    /** The conversations currently in the notification shade, newest first; all apps if [packageName] is null. */
    fun conversations(packageName: String? = null): List<Conversation> =
        runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { packageName == null || it.packageName == packageName }
            .mapNotNull(::parse)
            .sortedByDescending { it.timeMs }

    fun readAloud(c: Conversation) = speak(c.spoken())

    /** The newest guidance notification still showing, if any. */
    private fun refreshGuidance() {
        _guidance.value = runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { it.packageName != packageName && PhoneGuidance.isGuidance(it) }
            .maxByOrNull { it.postTime }
            ?.let { PhoneGuidance.from(this, it) }
    }

    /** Sends [text] through the conversation's reply action. False if it has none or it failed. */
    fun reply(c: Conversation, text: String): Boolean {
        val action = c.reply ?: return false
        val inputs = action.remoteInputs?.takeIf { it.isNotEmpty() } ?: return false
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
        return runCatching { action.actionIntent?.send(this, 0, intent) }.isSuccess
    }

    private fun parse(sbn: StatusBarNotification): Conversation? {
        val n = sbn.notification
        if (sbn.packageName == packageName) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return null
        val lines = style.messages.mapNotNull { m ->
            val text = m.text?.toString() ?: return@mapNotNull null
            // A null sender is the user, per MessagingStyle; some apps name the user instead.
            val p = m.person
            val fromUser = p == null || p === style.user || (p.key != null && p.key == style.user.key) ||
                (p.key == null && p.name != null && p.name.toString() == style.user.name?.toString())
            Conversation.Line(p?.name?.toString() ?: "", text, m.timestamp, fromUser)
        }
        if (lines.isEmpty()) return null
        val title = style.conversationTitle?.toString()
            ?: lines.lastOrNull { it.sender.isNotEmpty() && !it.fromUser }?.sender
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        return Conversation(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            messages = lines,
            icon = (n.getLargeIcon() ?: n.smallIcon)?.let { loadIcon(it, sbn.packageName) },
            timeMs = lines.last().timeMs.takeIf { it > 0 } ?: sbn.postTime,
            markRead = actionWith(n, NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)?.actionIntent,
            reply = actionWith(n, NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                ?: (0 until NotificationCompat.getActionCount(n)).asSequence().mapNotNull { NotificationCompat.getAction(n, it) }
                    .firstOrNull { !it.remoteInputs.isNullOrEmpty() },
        )
    }

    private fun actionWith(n: Notification, semantic: Int): NotificationCompat.Action? {
        val count = NotificationCompat.getActionCount(n)
        for (i in 0 until count) {
            val a = NotificationCompat.getAction(n, i) ?: continue
            if (a.semanticAction == semantic) return a
        }
        return null
    }

    /** Icons are loaded with our identity, so a URI icon must pass the same owner check as media art. */
    private fun loadIcon(icon: Icon, owner: String): Bitmap? = runCatching {
        require(ArtLoader.iconAllowed(this, icon, owner))
        icon.loadDrawable(this)?.toBitmap(96, 96)
    }.getOrNull()

    /** Speaks with the media usage, so it reaches the car along with the music. */
    private fun speak(text: String) {
        val t = tts
        if (t != null) { t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), null); return }
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) { Log.w(TAG, "no text-to-speech"); return@TextToSpeech }
            tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts?.language = Locale.getDefault()
            tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), null)
        }
    }

    /** A conversation update for the car screen. */
    class Message(
        val key: String,
        val title: String,
        val text: String,
        val icon: Bitmap?,
        val actions: List<Pair<String, () -> Unit>>,
        val group: Boolean,
        /** Reads the message aloud (through the media stream, so it reaches the car). */
        val readAloud: () -> Unit,
    )

    companion object {
        private const val TAG = "AareNotify"
        @Volatile var instance: CarNotificationListener? = null
            private set

        /** Set by the car session while connected; called on the main thread. */
        @Volatile var sink: ((Message) -> Unit)? = null

        private val _guidance = MutableStateFlow<PhoneGuidance?>(null)
        /** Turn-by-turn from a navigation app running on the phone (Google Maps, Waze…). */
        val guidance: StateFlow<PhoneGuidance?> = _guidance

        /** Whether the user gave AARE notification access (the listener may still be unbound). */
        fun accessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /**
         * Access is granted but Android hasn't bound the listener (it can stay unbound after an
         * update): ask for it again. Harmless when it's already connected.
         */
        fun rebind(context: Context) {
            if (instance == null && accessGranted(context)) {
                runCatching { requestRebind(ComponentName(context, CarNotificationListener::class.java)) }
            }
        }

        private val _caller = MutableStateFlow<String?>(null)
        /** Who is calling or on the line, from the dialer's call notification. */
        val caller: StateFlow<String?> = _caller

        private val _changes = MutableStateFlow(0)
        /** Bumped whenever a notification is posted or removed: the cue to re-read [conversations]. */
        val changes: StateFlow<Int> = _changes
    }
}
