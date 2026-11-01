package net.prototux.aare.media

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Which part of the media screen is shown. */
internal enum class MediaView { BROWSE, NOW_PLAYING, QUEUE, SEARCH }

/** Children of one node, loaded page by page as the user scrolls. */
@Stable
internal class NodeLoader(
    private val scope: CoroutineScope,
    private val fetch: suspend (page: Int, pageSize: Int) -> List<MediaItem>,
) {
    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var end by mutableStateOf(false)
        private set
    var error by mutableStateOf<MediaError?>(null)
        private set
    private var page = 0
    private var job: Job? = null

    /** Bumped by [refresh] so a cancelled load cannot touch the fresh state. */
    private var generation = 0

    /** Loads the next page unless one is loading or the end was reached. */
    fun loadMore() {
        if (loading || end) return
        loading = true
        val gen = generation
        val p = page
        job = scope.launch {
            try {
                val got = fetch(p, PAGE_SIZE)
                if (gen != generation) return@launch
                // the first page replaces: a refresh keeps the old items on screen until then
                val merged = mergePage(if (p == 0) emptyList() else items, got, PAGE_SIZE) { it.mediaId }
                items = merged.items
                end = merged.endReached
                page++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) {
                    error = (e as? BrowseException)?.error ?: MediaError("Could not load this list")
                    end = true
                }
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    /** Starts over, showing the spinner (the error card's retry). */
    fun retry() {
        items = emptyList()
        refresh()
    }

    /** The app said the children changed: reloads from the first page, the old items staying meanwhile. */
    fun refresh() {
        generation++
        job?.cancel()
        page = 0
        end = false
        error = null
        loading = false
        loadMore()
    }

    /** A later page failed: tries that page again, keeping what is loaded. */
    fun retryMore() {
        if (loading) return
        error = null
        end = false
        loadMore()
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}

/**
 * One browse level: a node's children, how to lay them out, and where the user scrolled to.
 * [isNode] is false for search results, which are no node of the app's tree (nothing to subscribe to).
 */
@Stable
internal class Level(val id: String, val title: String, val loader: NodeLoader, val hints: StyleHints, val isNode: Boolean = true) {
    val grid = LazyGridState()
}

/**
 * State of one app's media screen: the view, the tabs, the browse back stack. Lives in the hub
 * (not the composition) so leaving for the dashboard and coming back keeps the user's place.
 */
@Stable
internal class MediaUi(val connection: MediaConnection, private val scope: CoroutineScope) {
    var view by mutableStateOf(MediaView.BROWSE)
    var tab by mutableIntStateOf(0)
        private set
    var root by mutableStateOf<MediaConnection.Status.Ready?>(null)
        private set
    private var rootLevel by mutableStateOf<Level?>(null)
    private val tabLevels = HashMap<String, Level>()
    val stack = mutableStateListOf<Level>()
    var query by mutableStateOf("")

    /** First time the screen opens: show now playing if this app is already playing. */
    var opened = false

    init {
        connection.childrenChanged = ::onChildrenChanged
    }

    /** A new root (first connection or after the app restarted) resets browsing. */
    fun onReady(ready: MediaConnection.Status.Ready) {
        if (root?.root?.mediaId == ready.root.mediaId && rootLevel != null) {
            // same tree after a reconnect: keep the user's place, reload what failed meanwhile
            root = ready
            rootLevel?.loader?.let { if (it.error != null) it.retry() }
            level?.loader?.let { if (it.error != null) it.retry() }
            return
        }
        root = ready
        tabLevels.clear()
        stack.clear()
        tab = 0
        rootLevel = level(ready.root.mediaId, connection.app.label, ready.hints).also { it.loader.loadMore() }
    }

    /**
     * The root's browsable children as tabs, empty for a plain list, null while still deciding.
     * Decided on the first page: a root built for Android Auto (at most 4 children asked) has one.
     */
    val tabs: List<MediaItem>?
        get() {
            val l = rootLevel?.loader ?: return null
            if (l.items.isEmpty()) return if (l.end) emptyList() else null
            val items = l.items
            return tabIndices(items.map { it.isBrowsableItem }).map { items[it] }
        }

    /** The level on screen. */
    val level: Level?
        get() = stack.lastOrNull() ?: base

    private val base: Level?
        get() {
            val t = tabs ?: return rootLevel
            if (t.isEmpty()) return rootLevel
            val i = tab.coerceIn(t.indices)
            val item = t[i]
            // Key by tab index too: two tabs with the same (buggy) mediaId must not share a level.
            return tabLevels.getOrPut("$i:${item.mediaId}") { childLevel(item) }
        }

    /**
     * Ids of the levels to keep fresh (subscribed, see [MediaConnection.watch]): the root (the tabs
     * come from it), the tabs opened so far and the back stack.
     */
    val watched: Set<String>
        get() = buildSet {
            rootLevel?.let { add(it.id) }
            base?.let { add(it.id) } // first, as it creates the current tab's level
            tabLevels.values.forEach { add(it.id) }
            stack.forEach { if (it.isNode) add(it.id) }
        }

    /** The app changed a node's children (library scanned, signed in...): reload the levels showing it. */
    private fun onChildrenChanged(parentId: String) {
        (listOfNotNull(rootLevel) + tabLevels.values + stack)
            .filter { it.isNode && it.id == parentId }
            .forEach { it.loader.refresh() }
    }

    fun selectTab(i: Int) {
        tab = i
        stack.clear()
        view = MediaView.BROWSE
    }

    /** Browsable items open a level; playable ones start playing and show now playing. */
    fun open(item: MediaItem) {
        if (item.isBrowsableItem) {
            stack += childLevel(item)
        } else {
            connection.play(item)
            view = MediaView.NOW_PLAYING
        }
    }

    /** Search results are a level of their own, so back returns to where the user was. */
    fun search(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        // One search level at a time, else Back re-shows a stale result and each search leaks a level.
        stack.removeAll { it.id.startsWith("search:") }
        stack += Level("search:$text", "“$text”", NodeLoader(scope) { page, size ->
            if (page == 0) connection.search(text, size) else emptyList()
        }, StyleHints(), isNode = false)
        view = MediaView.BROWSE
    }

    val canGoBack: Boolean get() = view != MediaView.BROWSE || stack.isNotEmpty()

    fun back() {
        when (view) {
            MediaView.QUEUE -> view = MediaView.NOW_PLAYING
            MediaView.NOW_PLAYING, MediaView.SEARCH -> view = MediaView.BROWSE
            MediaView.BROWSE -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        }
    }

    private fun childLevel(item: MediaItem): Level =
        level(item.mediaId, item.titleText, hintsOf(item.mediaMetadata.extras).over(root?.hints ?: StyleHints()))

    /** Levels start loading when first shown (see BrowseLevel), not when created. */
    private fun level(id: String, title: String, hints: StyleHints) =
        Level(id, title, NodeLoader(scope) { page, size -> connection.children(id, page, size) }, hints)
}
