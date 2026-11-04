package net.prototux.aare.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.prototux.aare.R

/**
 * Follows every active media session on the phone through [MediaSessionManager], so "now playing"
 * and the transport buttons work for apps we never connected to (a podcast started on the phone,
 * a video app...). Main thread only.
 *
 * Needs notification access for our [LISTENER] (the system's rule for getActiveSessions). Without
 * it we expose no sources and check again every half minute.
 */
internal class SessionTracker(
    private val context: Context,
    private val scope: CoroutineScope,
    private val art: ArtLoader,
) {
    private val _sources = MutableStateFlow<List<SessionSource>>(emptyList())
    val sources: StateFlow<List<SessionSource>> = _sources.asStateFlow()

    private val _available = MutableStateFlow(false)

    /** False until notification access is granted. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, LISTENER)
    private val handler = Handler(Looper.getMainLooper())
    private val onChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        if (list.isNullOrEmpty()) recheck() else update(list)
    }
    private var listening = false
    private var poll: Job? = null

    fun start() {
        if (tryListen()) return
        poll = scope.launch {
            while (!tryListen()) delay(RETRY_MS)
        }
    }

    private fun tryListen(): Boolean {
        if (listening) return true
        return try {
            manager.addOnActiveSessionsChangedListener(onChanged, listener, handler)
            listening = true
            _available.value = true
            update(manager.getActiveSessions(listener))
            true
        } catch (e: SecurityException) {
            _available.value = false
            false
        }
    }

    /**
     * An empty list is either the last session ending or the system dropping our listener because
     * notification access was revoked. In that case go back to polling, so it listens again once
     * access is granted again.
     */
    private fun recheck() {
        try {
            update(manager.getActiveSessions(listener))
        } catch (e: SecurityException) {
            runCatching { manager.removeOnActiveSessionsChangedListener(onChanged) }
            listening = false
            _available.value = false
            update(emptyList())
            poll?.cancel()
            start()
        }
    }

    /** Keeps sources for sessions still active (by token), creates new ones, drops the rest. */
    private fun update(controllers: List<MediaController>) {
        val old = _sources.value.associateBy { it.token }
        val next = controllers
            .filter { it.packageName != context.packageName }
            .map { c -> old[c.sessionToken] ?: SessionSource(context, c, scope, art).also { it.start() } }
        val keep = next.toSet()
        old.values.filter { it !in keep }.forEach { it.release() }
        _sources.value = next
    }

    fun release() {
        poll?.cancel()
        if (listening) runCatching { manager.removeOnActiveSessionsChangedListener(onChanged) }
        listening = false
        _sources.value.forEach { it.release() }
        _sources.value = emptyList()
    }

    companion object {
        const val LISTENER = "net.prototux.aare.notify.CarNotificationListener"
        private const val RETRY_MS = 30_000L
    }
}

/** One platform media session, as a [PlaybackSource]. */
internal class SessionSource(
    context: Context,
    private val controller: MediaController,
    private val scope: CoroutineScope,
    private val art: ArtLoader,
) : PlaybackSource {
    val token: MediaSession.Token = controller.sessionToken
    override val packageName: String = controller.packageName
    override val key = "session:$packageName:${token.hashCode()}"
    override val isConnection = false

    private val label = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private val _state = MutableStateFlow(SourceState())
    override val state: StateFlow<SourceState> = _state.asStateFlow()

    private var artKey: String? = null
    private var artBitmap: Bitmap? = null
    private var artJob: Job? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = update()
        override fun onSessionDestroyed() {
            _state.value = SourceState()
        }
    }

    fun start() {
        controller.registerCallback(callback, Handler(Looper.getMainLooper()))
        update()
    }

    fun release() {
        controller.unregisterCallback(callback)
        artJob?.cancel()
        noticeJob?.cancel()
    }

    private var noticeMessage: String? = null
    private var noticeSince = 0L
    private var noticeJob: Job? = null

    private fun update() {
        val ps = controller.playbackState
        val md = controller.metadata
        val st = ps?.state ?: PlaybackState.STATE_NONE
        val actions = ps?.actions ?: 0L
        val info = md?.let {
            val d = it.description
            PlaybackInfo(
                packageName = packageName,
                title = (it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: d.title ?: "").toString(),
                artist = (it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: d.subtitle ?: "").toString(),
                album = it.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
                art = artFor(it),
                playing = st == PlaybackState.STATE_PLAYING,
                positionMs = (ps?.position ?: 0L).coerceAtLeast(0),
                durationMs = it.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
                // PlaybackState samples its position at lastPositionUpdateTime (elapsedRealtime)
                sampledAtMs = ps?.lastPositionUpdateTime?.takeIf { t -> t > 0 } ?: SystemClock.elapsedRealtime(),
            )
        }
        // STATE_ERROR is fatal. Non-fatal errors ("sign in to see your library") keep the state and
        // only set a message (and maybe a fix-it action); the framework state drops the compat error
        // code, so the message is what tells them apart.
        val message = ps?.errorMessage?.toString()?.takeIf { it.isNotBlank() }
        // A non-fatal message shows for a while only: apps that build each state from the last
        // one keep it forever ("skip limit reached").
        val now = SystemClock.elapsedRealtime()
        if (message != noticeMessage) {
            noticeMessage = message
            noticeSince = now
            noticeJob?.cancel()
            noticeJob = if (message != null) scope.launch { delay(NOTICE_MS); update() } else null
        }
        val fresh = message != null && now - noticeSince < NOTICE_MS
        val error = if (st == PlaybackState.STATE_ERROR || fresh) {
            val (actionLabel, intent) = errorAction(ps?.extras)
            MediaError(message ?: context.getString(R.string.media_error_reported, label), actionLabel, intent)
        } else null
        _state.value = SourceState(
            info = info,
            showPause = st in PLAYING_STATES,
            canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            actions = ps?.customActions.orEmpty().take(MAX_ACTIONS).map { a ->
                CustomAction(a.action, a.name.toString(), IconRef(packageName, a.icon)) {
                    controller.transportControls.sendCustomAction(a, null)
                }
            },
            queue = queueOf(ps),
            error = error,
        )
    }

    private fun queueOf(ps: PlaybackState?): List<QueueEntry> {
        val queue = controller.queue ?: return emptyList()
        if (queue.size < 2) return emptyList()
        val active = ps?.activeQueueItemId ?: MediaSession.QueueItem.UNKNOWN_ID.toLong()
        val current = queue.indexOfFirst { it.queueId == active }.coerceAtLeast(0)
        val start = (current - 20).coerceAtLeast(0)
        return queue.subList(start, (start + 200).coerceAtMost(queue.size)).map {
            QueueEntry(it.queueId, it.description.title?.toString().orEmpty(), it.description.subtitle?.toString().orEmpty(), it.queueId == active)
        }
    }

    /** Metadata bitmaps are already scaled down by the system; URIs are loaded in the background. */
    private fun artFor(md: MediaMetadata): Bitmap? {
        BITMAP_KEYS.firstNotNullOfOrNull { md.getBitmap(it) }?.let { return it }
        val uri = URI_KEYS.firstNotNullOfOrNull { md.getString(it)?.takeIf(String::isNotBlank) }?.let(Uri::parse)
        val key = ArtLoader.artKey(uri, null, packageName, MediaKeys.ART_SIZE_PX)
        if (key == artKey) return artBitmap
        artKey = key
        artBitmap = art.cached(key)
        artJob?.cancel()
        if (key != null && artBitmap == null) {
            artJob = scope.launch {
                val bmp = art.art(uri, null, packageName)
                if (artKey == key && bmp != null) {
                    artBitmap = bmp
                    update()
                }
            }
        }
        return artBitmap
    }

    // ------------------------------------------------------------- transport

    override fun playPause() {
        if (controller.playbackState?.state in PLAYING_STATES) controller.transportControls.pause()
        else controller.transportControls.play()
    }

    override fun pause() = controller.transportControls.pause()
    override fun next() = controller.transportControls.skipToNext()
    override fun previous() = controller.transportControls.skipToPrevious()
    override fun seekTo(positionMs: Long) = controller.transportControls.seekTo(positionMs)
    override fun skipTo(entry: QueueEntry) = controller.transportControls.skipToQueueItem(entry.id)

    companion object {
        private const val MAX_ACTIONS = 4
        private const val NOTICE_MS = 10_000L
        private val PLAYING_STATES = setOf(
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        )
        private val BITMAP_KEYS = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        private val URI_KEYS = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
    }
}
