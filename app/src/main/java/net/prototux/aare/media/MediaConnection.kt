package net.prototux.aare.media

import android.content.Context
import android.graphics.Bitmap
import android.media.browse.MediaBrowser as FwBrowser
import android.media.session.MediaController as FwController
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import net.prototux.aare.R

/**
 * Our one media3 [MediaBrowser] connection to [app], which works for media3 MediaLibraryService
 * and legacy MediaBrowserService(Compat) apps alike. Main thread only.
 *
 * Legacy apps also get a framework [FwBrowser] next to it, for what media3 cannot do the way
 * Android Auto does: ask for the root with Android Auto's hints, and play with playFromMediaId.
 *
 * It reconnects when the app's process dies, exposes the library root and paged browse/search,
 * and is a [PlaybackSource] for the now-playing view.
 */
@OptIn(UnstableApi::class)
internal class MediaConnection(
    private val context: Context,
    parent: CoroutineScope,
    val app: MediaApp,
    private val art: ArtLoader,
) : PlaybackSource {

    sealed interface Status {
        data object Connecting : Status
        data class Ready(val root: MediaItem, val hints: StyleHints, val searchSupported: Boolean) : Status
        data class Failed(val error: MediaError) : Status
    }

    override val key = "browser:${app.packageName}"
    override val packageName: String = app.packageName
    override val isConnection = true

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Main.immediate)
    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _state = MutableStateFlow(SourceState())
    override val state: StateFlow<SourceState> = _state.asStateFlow()

    private var browser: MediaBrowser? = null

    /** Our browser is connected; [status] may still be [Status.Connecting] (root loading) or [Status.Failed] (root failed). */
    val connected: Boolean get() = browser?.isConnected == true

    /** The app is a media3 MediaLibraryService, from its manifest (not [MediaBrowser.getConnectedToken]). */
    private var library = false

    /**
     * Legacy apps: connected with [rootHints] for the root and its extras, which media3 cannot give
     * (its getLibraryRoot makes its own connection, with hints of its own); its session token is
     * for [play]. Lives as long as [browser]; null when it could not connect.
     */
    private var legacyBrowser: FwBrowser? = null
    private var legacyController: FwController? = null

    /** Parent ids subscribed to (see [watch]); [pendingSubscriptions] are not acknowledged yet. */
    private var watched = emptySet<String>()
    private val pendingSubscriptions = HashSet<String>()

    /** Told the parent id when the app says the children of a [watch]ed node changed. */
    var childrenChanged: ((String) -> Unit)? = null

    private var connectJob: Job? = null
    private var reconnects = 0
    private var released = false
    private val searchDone = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var artKey: String? = null
    private var artBitmap: Bitmap? = null
    private var artJob: Job? = null
    private var notice: MediaError? = null
    private var noticeJob: Job? = null

    /** Connects if not connected or connecting; safe to call often. */
    fun connect() {
        if (released || connectJob?.isActive == true || browser?.isConnected == true) return
        connectJob = scope.launch { doConnect() }
    }

    /** User asked to try again: reload the root if connected, else reconnect. */
    fun retry() {
        reconnects = 0
        val b = browser
        if (b != null && b.isConnected) {
            connectJob?.cancel()
            connectJob = scope.launch { loadRoot(b) }
        } else {
            connect()
        }
    }

    private suspend fun doConnect() {
        _status.value = Status.Connecting
        val future = try {
            val token = SessionToken(context, app.service)
            library = token.type == SessionToken.TYPE_LIBRARY_SERVICE
            MediaBrowser.Builder(context, token)
                .setConnectionHints(rootHints())
                .setListener(listener)
                .setApplicationLooper(Looper.getMainLooper())
                .buildAsync()
        } catch (e: IllegalArgumentException) {
            // the service is gone (app updated or disabled since discovery)
            fail(context.getString(R.string.media_error_no_service, app.label))
            return
        }
        val b = try {
            withTimeout(CONNECT_TIMEOUT_MS) { future.await() }
        } catch (e: TimeoutCancellationException) {
            MediaController.releaseFuture(future)
            fail(context.getString(R.string.media_error_not_responding, app.label))
            return
        } catch (e: CancellationException) {
            MediaController.releaseFuture(future)
            throw e
        } catch (e: Exception) {
            // SecurityException: the app rejected us (caller validation); not worth retrying alone
            Log.w(TAG, "connect ${app.packageName} failed: $e")
            MediaController.releaseFuture(future)
            fail(context.getString(if (e is SecurityException) R.string.media_error_refused else R.string.media_error_connect, app.label))
            return
        }
        if (released) {
            b.release()
            return
        }
        browser = b
        b.addListener(playerListener)
        watched.forEach { subscribe(b, it) }
        updateState()
        loadRoot(b)
    }

    private suspend fun loadRoot(b: MediaBrowser) {
        if (!library) {
            // Legacy apps: the root as Android Auto gets it; children still come through media3
            // (its connection was made with the same hints, given as connection hints).
            val fw = legacyBrowser?.takeIf { it.isConnected } ?: connectLegacy()
            if (browser !== b) return
            if (fw != null) {
                val extras = fw.extras
                val root = MediaItem.Builder()
                    .setMediaId(fw.root)
                    .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).setExtras(extras).build())
                    .build()
                reconnects = 0
                _status.value = Status.Ready(root, hintsOf(extras), extras?.getBoolean(MediaKeys.SEARCH_SUPPORTED, false) == true)
                return
            }
            // no framework connection: media3's root. Without params it sends no
            // EXTRA_RECENT/OFFLINE/SUGGESTED (LibraryParams always add them, even false, and VLC
            // then answers with its one-item "suggested" root), but no other hint either.
        }
        val params = if (library) LibraryParams.Builder().setExtras(libraryHints()).build() else null
        val result = try {
            b.getLibraryRoot(params).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (browser === b) fail(context.getString(R.string.media_error_open, app.label))
            return
        }
        if (browser !== b) return
        val root = result.value
        if (result.resultCode != LibraryResult.RESULT_SUCCESS || root == null) {
            _status.value = Status.Failed(errorOf(result))
            return
        }
        reconnects = 0
        // root hints may come in the result's params (media3, legacy root extras) or on the root item
        val extras = Bundle()
        result.params?.extras?.let(extras::putAll)
        root.mediaMetadata.extras?.let(extras::putAll)
        // media3 apps grant the search command; legacy apps say it in their root extras
        val search = if (library) b.isSessionCommandAvailable(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
        else extras.getBoolean(MediaKeys.SEARCH_SUPPORTED, false)
        _status.value = Status.Ready(root, hintsOf(extras), search)
    }

    /** Connects [legacyBrowser] with Android Auto's [rootHints]; null when the app refuses or hangs. */
    private suspend fun connectLegacy(): FwBrowser? {
        dropLegacy()
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val callback = object : FwBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onConnectionFailed() {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                // kept in the field at once, so whatever cancels us (onLost, release) disconnects it
                legacyBrowser = FwBrowser(context, app.service, callback, rootHints()).also { it.connect() }
            }
        } == true
        if (!connected) {
            Log.w(TAG, "framework browser for ${app.packageName} did not connect; using media3's root")
            dropLegacy()
        }
        return legacyBrowser
    }

    private fun dropLegacy() {
        legacyBrowser?.disconnect()
        legacyBrowser = null
        legacyController = null
    }

    private fun fail(message: String) {
        _status.value = Status.Failed(MediaError(message))
    }

    /** The error for a list that failed without the app saying why. */
    fun loadListError() = MediaError(context.getString(R.string.media_error_load_list))

    /** One page of [parentId]'s children. Throws [BrowseException]. */
    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val b = connected()
        val result = try {
            b.getChildren(parentId, page, pageSize, null).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BrowseException(loadListError())
        }
        if (result.resultCode != LibraryResult.RESULT_SUCCESS) throw BrowseException(errorOf(result))
        return result.value.orEmpty()
    }

    /**
     * Searches: media3 delivers results in two steps, `search()` then `onSearchResultChanged`, then
     * `getSearchResult()`. Some apps never notify, so we fetch anyway after a short wait.
     */
    suspend fun search(query: String, pageSize: Int): List<MediaItem> = coroutineScope {
        val b = connected()
        val notified = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(SEARCH_NOTIFY_TIMEOUT_MS) { searchDone.first { it == query } }
        }
        val started = try {
            b.search(query, null).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BrowseException(MediaError(context.getString(R.string.media_error_search)))
        }
        if (started.resultCode != LibraryResult.RESULT_SUCCESS) {
            notified.cancel()
            throw BrowseException(errorOf(started))
        }
        notified.await()
        val result = try {
            b.getSearchResult(query, 0, pageSize, null).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BrowseException(MediaError(context.getString(R.string.media_error_search)))
        }
        if (result.resultCode != LibraryResult.RESULT_SUCCESS) throw BrowseException(errorOf(result))
        result.value.orEmpty()
    }

    /**
     * Plays a browse item the way Android Auto does: we hand the app only the chosen item and it
     * builds the queue itself (rest of the album, playlist...). Passing our own list as the queue
     * would bypass the app's logic and make it resolve every item.
     *
     * Legacy apps get playFromMediaId, like from Android Auto (search results too): media3 would
     * turn an item with a media URI into playFromUri/prepareFromUri, which many apps ignore.
     * media3 apps resolve the item in onAddMediaItems/onSetMediaItems.
     */
    fun play(item: MediaItem) {
        val b = browser ?: return connect()
        val legacy = legacyController()
        if (legacy != null) {
            legacy.transportControls.playFromMediaId(item.mediaId, null)
            return
        }
        b.setMediaItem(item)
        // playWhenReady before prepare, so the app starts as soon as it is ready
        b.play()
        b.prepare()
    }

    /** A framework controller of the legacy app's session, from [legacyBrowser]'s token. */
    private fun legacyController(): FwController? {
        if (library) return null
        val fw = legacyBrowser?.takeIf { it.isConnected } ?: return null
        legacyController?.let { return it }
        return try {
            FwController(context, fw.sessionToken).also { legacyController = it }
        } catch (e: RuntimeException) {
            Log.w(TAG, "no session controller for ${app.packageName}: $e")
            null
        }
    }

    /**
     * Subscribes to exactly [ids] (the levels on screen), so the app can tell us when their children
     * change ([childrenChanged]): a library scan finishing, a sign-in... Kept across reconnects.
     */
    fun watch(ids: Set<String>) {
        val wanted = ids.filterTo(HashSet()) { it.isNotEmpty() } // media3 throws on empty ids
        val b = browser?.takeIf { it.isConnected }
        if (b != null) {
            (watched - wanted).forEach {
                pendingSubscriptions -= it
                b.unsubscribe(it)
            }
            (wanted - watched).forEach { subscribe(b, it) }
        }
        watched = wanted
    }

    /**
     * Legacy apps answer a subscription with the children right away, which media3 reports as a
     * change: until the subscription is acknowledged, a "change" is only that first answer.
     */
    private fun subscribe(b: MediaBrowser, parentId: String) {
        pendingSubscriptions += parentId
        val future = b.subscribe(parentId, null)
        future.addListener({ if (browser === b) pendingSubscriptions -= parentId }, MoreExecutors.directExecutor())
    }

    private fun connected(): MediaBrowser =
        browser?.takeIf { it.isConnected } ?: throw BrowseException(MediaError(context.getString(R.string.media_error_not_connected, app.label)))

    // ------------------------------------------------------------- transport (PlaybackSource)

    override fun playPause() {
        browser?.let { Util.handlePlayPauseButtonAction(it) } ?: connect()
    }

    override fun pause() {
        browser?.pause()
    }

    override fun next() {
        browser?.seekToNext()
    }

    override fun previous() {
        browser?.seekToPrevious()
    }

    override fun seekTo(positionMs: Long) {
        browser?.seekTo(positionMs)
    }

    override fun skipTo(entry: QueueEntry) {
        browser?.seekToDefaultPosition(entry.id.toInt())
    }

    // ------------------------------------------------------------- state

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateState()
    }

    private val listener = object : MediaBrowser.Listener {
        override fun onDisconnected(controller: MediaController) = onLost(controller)

        override fun onSearchResultChanged(browser: MediaBrowser, query: String, itemCount: Int, params: LibraryParams?) {
            searchDone.tryEmit(query)
        }

        override fun onChildrenChanged(browser: MediaBrowser, parentId: String, itemCount: Int, params: LibraryParams?) {
            if (browser !== this@MediaConnection.browser || parentId !in watched || parentId in pendingSubscriptions) return
            childrenChanged?.invoke(parentId)
        }

        override fun onMediaButtonPreferencesChanged(controller: MediaController, mediaButtonPreferences: MutableList<CommandButton>) = updateState()

        override fun onAvailableSessionCommandsChanged(controller: MediaController, commands: androidx.media3.session.SessionCommands) = updateState()

        /** Non-fatal errors the app sends to controllers (e.g. "sign in"); shown for a while. */
        override fun onError(controller: MediaController, sessionError: SessionError) {
            val component = app.service.flattenToString()
            if (component in TOAST_GUARDRAIL_COMPONENTS && !toastGuardrail.allow(component)) return
            val (label, intent) = errorAction(sessionError.extras)
            notice = MediaError(sessionError.message.takeIf { it.isNotBlank() && it != DEFAULT_SESSION_MESSAGE }
                ?: errorText(context, sessionError.code, app.label), label, intent)
            updateState()
            noticeJob?.cancel()
            noticeJob = scope.launch {
                delay(NOTICE_MS)
                notice = null
                updateState()
            }
        }
    }

    /** The app's process died or it dropped us: reconnect with backoff, as long as we are wanted. */
    private fun onLost(controller: MediaController) {
        if (controller !== browser) return
        Log.i(TAG, "${app.packageName} disconnected")
        browser = null
        // a root load still running for the dead connection must not overwrite the status later
        connectJob?.cancel()
        controller.release()
        dropLegacy()
        pendingSubscriptions.clear()
        updateState()
        if (released) return
        if (reconnects >= MAX_RECONNECTS) {
            fail(context.getString(R.string.media_error_stopped, app.label))
            return
        }
        _status.value = Status.Connecting
        val wait = 1_000L shl reconnects++
        connectJob = scope.launch {
            delay(wait)
            doConnect()
        }
    }

    private fun updateState() {
        val b = browser
        if (b == null || !b.isConnected) {
            _state.value = SourceState(error = notice)
            return
        }
        val md = b.mediaMetadata
        val hasMedia = b.currentMediaItem != null || md.title != null
        val info = if (!hasMedia) null else PlaybackInfo(
            packageName = packageName,
            title = (md.title ?: md.displayTitle ?: "").toString(),
            artist = (md.artist ?: md.albumArtist ?: md.subtitle ?: "").toString(),
            album = (md.albumTitle ?: "").toString(),
            art = artFor(md),
            playing = b.isPlaying,
            positionMs = b.currentPosition.coerceAtLeast(0),
            durationMs = b.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            sampledAtMs = SystemClock.elapsedRealtime(),
        )
        val error = b.playerError?.let { e ->
            val (label, intent) = errorAction(e.extras)
            MediaError(e.message?.takeIf { it.isNotBlank() } ?: errorText(context, e.errorCode, app.label), label, intent)
        } ?: notice
        _state.value = SourceState(
            info = info,
            showPause = !Util.shouldShowPlayButton(b),
            canPrevious = b.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS),
            canNext = b.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT),
            canSeek = b.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && b.isCurrentMediaItemSeekable,
            actions = actionsOf(b),
            queue = queueOf(b),
            error = error,
        )
    }

    /** Current art, loading it in the background when the metadata points at new art. */
    private fun artFor(md: MediaMetadata): Bitmap? {
        val key = ArtLoader.artKey(md.artworkUri, md.artworkData, packageName, MediaKeys.ART_SIZE_PX)
        if (key == artKey) return artBitmap
        artKey = key
        artBitmap = art.cached(key)
        artJob?.cancel()
        if (key != null && artBitmap == null) {
            val uri = md.artworkUri
            val data = md.artworkData
            artJob = scope.launch {
                val bmp = art.art(uri, data, packageName)
                if (artKey == key && bmp != null) {
                    artBitmap = bmp
                    updateState()
                }
            }
        }
        return artBitmap
    }

    /** App buttons beyond play/pause/previous/next (like, shuffle, speed...). */
    private fun actionsOf(b: MediaBrowser): List<CustomAction> =
        b.mediaButtonPreferences
            .filter { it.isEnabled && (it.sessionCommand != null || it.playerCommand !in TRANSPORT_COMMANDS) }
            .take(MAX_ACTIONS)
            .mapIndexed { i, button ->
                val own = CommandButton.getIconResIdForIconConstant(button.icon)
                val icon = when {
                    own != 0 -> IconRef(null, own)
                    button.iconResId != 0 -> IconRef(packageName, button.iconResId)
                    button.iconUri != null -> IconRef(packageName, 0, button.iconUri)
                    else -> null
                }
                CustomAction(
                    id = button.sessionCommand?.customAction ?: "player:${button.playerCommand}:$i",
                    label = button.displayName.toString(),
                    icon = icon,
                    run = { browser?.let(button::executeAction) },
                )
            }

    /** The play queue around the current item (huge playlists are cut). */
    private fun queueOf(b: MediaBrowser): List<QueueEntry> {
        if (!b.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) return emptyList()
        val timeline = b.currentTimeline
        if (timeline.isEmpty || timeline.windowCount < 2) return emptyList()
        val current = b.currentMediaItemIndex
        val start = (current - QUEUE_BEFORE).coerceAtLeast(0)
        val end = (start + MAX_QUEUE).coerceAtMost(timeline.windowCount)
        val window = Timeline.Window()
        return (start until end).map { i ->
            val item = timeline.getWindow(i, window).mediaItem
            QueueEntry(i.toLong(), item.titleText, item.subtitleText, i == current)
        }
    }

    private fun errorOf(result: LibraryResult<*>): MediaError {
        val se = result.sessionError
        val message = se?.message?.takeIf { it.isNotBlank() && it != DEFAULT_SESSION_MESSAGE } ?: errorText(context, result.resultCode, app.label)
        val (label, intent) = errorAction(se?.extras)
        return MediaError(message, label, intent)
    }

    /** Disconnects for good. */
    fun release() {
        released = true
        scope.cancel()
        browser?.release()
        browser = null
        dropLegacy()
        childrenChanged = null
    }

    companion object {
        private const val TAG = "AareMedia"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SEARCH_NOTIFY_TIMEOUT_MS = 3_000L
        private const val NOTICE_MS = 10_000L
        private const val MAX_RECONNECTS = 5
        private const val MAX_ACTIONS = 4
        private const val MAX_QUEUE = 200
        private const val QUEUE_BEFORE = 20

        /** media3's placeholder message when a session sends an error without one. */
        private const val DEFAULT_SESSION_MESSAGE = "no error message provided"

        /** Shared across every connection: a component that spams errors stays capped everywhere, not just per connection. */
        private val toastGuardrail = ToastGuardrail()

        private val TRANSPORT_COMMANDS = setOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )

        /** What Android Auto tells media apps about itself, as connection and root hints. */
        fun rootHints() = Bundle().apply {
            putInt(MediaKeys.ROOT_CHILDREN_LIMIT, MediaKeys.ROOT_CHILDREN_LIMIT_VALUE)
            putInt(MediaKeys.ROOT_CHILDREN_SUPPORTED_FLAGS, MediaKeys.FLAG_BROWSABLE)
            putInt(MediaKeys.MEDIA_ART_SIZE_PIXELS, MediaKeys.ART_SIZE_PX)
            putInt(MediaKeys.CUSTOM_BROWSER_ACTION_LIMIT, 0)
            // No EXTRA_RECENT/OFFLINE/SUGGESTED: Android Auto only sends them to ask for those special
            // roots, and some apps (VLC) only check that the key is there, not its value.
        }

        /** Android Auto's root params for media3 apps: the same, but browsable-only as media3's boolean. */
        fun libraryHints() = rootHints().apply {
            remove(MediaKeys.ROOT_CHILDREN_SUPPORTED_FLAGS)
            putBoolean(MediaConstants.EXTRA_KEY_ROOT_CHILDREN_BROWSABLE_ONLY, true)
        }
    }
}
