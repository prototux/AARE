package net.prototux.aare.ui.car

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import net.prototux.aare.R
import net.prototux.aare.Settings
import android.net.Uri
import net.prototux.aare.host.CarLinkActivity
import net.prototux.aare.host.CarLinks
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.media.Placeholder
import net.prototux.aare.notify.CarNotificationListener

/**
 * Hosts an ordinary phone app on the car screen (NewPipe, an emulator, a browser app…). A
 * [TextureView] gives its surface to the privileged [AppLauncher], which puts a virtual display
 * there, launches [packageName] on it, and receives the car's touches we forward.
 *
 * Not an Android Auto app, so — like the phone-screen mirror — it is paused while driving
 * ([driving]; false for a navigation app, which runs on). The
 * privileged core is device-tested; here it just wires the surface, touches and lifecycle.
 */
@Composable
internal fun StandaloneAppScreen(runtime: CarRuntime, driving: Boolean, packageName: String, modifier: Modifier, backId: String = CarRuntime.standaloneId(packageName), card: Boolean = false) {
    val context = LocalContext.current
    // A helper per app: two phone apps side by side each keep their own display. The map card has
    // one of its own, laid out as on a tablet so the map keeps most of the small card.
    val launcher = remember(packageName) { AppLauncher(context, (if (card) "card-" else "launch-") + packageName) }
    var started by remember(packageName) { mutableStateOf(false) }
    // Voice sessions started here: each opens a fresh chat, so the car links' memory starts afresh.
    var voiceStarts by remember(packageName) { mutableIntStateOf(0) }

    DisposableEffect(packageName) {
        // Hardware / head-unit Back goes to the launched app.
        val back: () -> Boolean = {
            val t = android.os.SystemClock.uptimeMillis()
            launcher.key(t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
            launcher.key(t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
            launcher.connected
        }
        runtime.setScreenBack(backId, back)
        onDispose {
            runtime.clearScreenBack(backId, back)
            // A voice request it never got to (left before the app was up) isn't kept for the next time it opens.
            if (runtime.ui.assistantVoice == packageName) runtime.ui.assistantVoice = null
            // Nor a navigation: opened later by hand, the app would start that old route over the driver's own.
            // Not by the map card: leaving it is how the request reaches the app full screen.
            if (!card && runtime.ui.navigationRequest?.first == packageName) runtime.ui.navigationRequest = null
            launcher.release()
        }
    }

    Box(modifier.background(Color.Black)) {
        if (!driving) {
            HostedAppSurface(launcher, packageName, runtime.densityDpi, Modifier.fillMaxSize(), tablet = card) { started = it }
            // The voice button: the assistant opens straight into its voice mode, where it has one.
            LaunchedEffect(started, runtime.ui.assistantVoice) {
                if (started && runtime.ui.assistantVoice == packageName) try {
                    voiceStarts++
                    val link = Settings(context).assistantLink
                    withContext(Dispatchers.IO) {
                        startVoice(link, { launcher.openLink(it, packageName, warm = true) }, launcher::tapLabel) { launcher.openShortcut(packageName, VOICE_SHORTCUT) }
                    }
                } finally {
                    // Only once done or left: the request is this effect's key, and clearing it cancels the effect.
                    if (runtime.ui.assistantVoice == packageName) runtime.ui.assistantVoice = null
                }
            }
            // A navigation asked for in this app (a car link, a calendar event): its address, opened once it runs.
            // Not by the map card: the request opens the app full screen, which takes the card, and the address, away.
            LaunchedEffect(started, runtime.ui.navigationRequest) {
                val request = runtime.ui.navigationRequest?.takeIf { started && !card && it.first == packageName } ?: return@LaunchedEffect
                // Cleared only once done: the request is this effect's key, and clearing it cancels the effect.
                try { withContext(Dispatchers.IO) { launcher.openLink(request.second, packageName) } }
                finally { if (runtime.ui.navigationRequest == request) runtime.ui.navigationRequest = null }
            }
            // The assistant's car links, read off the car screen and run here: MacroDroid and the phone's own
            // tools only see the phone's display, not this one. Links already in the chat when it shows stay put.
            LaunchedEffect(started, voiceStarts) {
                if (!started || packageName != Settings(context).assistantPackage) return@LaunchedEffect
                var seen: Map<String, Int> = emptyMap()
                // The chat may still be drawing at first: what shows in that time is the chat's, not a new reply's.
                val since = SystemClock.uptimeMillis()
                // When AARE last tapped a link emoji: only then is an address in the app's prompt this reply's.
                var tappedAt = 0L
                while (true) {
                    // A failed look says nothing about the chat: it neither counts nor runs anything.
                    val lines = withContext(Dispatchers.IO) { launcher.carLinks(CarLinks.HOST + CarLinks.PATH, CAR_LINK_TAP) }
                    if (lines == null) { delay(CAR_LINK_POLL_MS); continue }
                    val (fresh, next) = newCarLinks(seen, lines)
                    if (SystemClock.uptimeMillis() - since >= CAR_LINK_SETTLE_MS) carLinksToRun(fresh, lines).forEach { line ->
                        val part = line.split('\t')
                        if (part[0] == "url") {
                            // Only the prompt AARE's tap opened: a link the driver tapped is theirs to open.
                            if (!ownPrompt(tappedAt, SystemClock.uptimeMillis())) return@forEach
                            tappedAt = 0L
                            // Its "Open this website?" prompt goes, by the Back key, which a tap that comes too
                            // early can't miss; then the voice conversation ends, which would otherwise go on listening.
                            // Both before the link runs: a link that shows another app (navigation) takes this
                            // screen away, and its helper with it, and the conversation went on on the phone.
                            withContext(Dispatchers.IO) {
                                val t = SystemClock.uptimeMillis()
                                launcher.key(t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
                                launcher.key(t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
                                // Its call notification's hang-up button, else the voice mode's own end button.
                                CarNotificationListener.instance?.hangUp(packageName) == true || launcher.tapLabel(END_VOICE_BUTTON, CLOSE_WAIT_MS)
                            }
                            CarLinkActivity.runCarLink(context, Uri.parse(part[1]), packageName)
                        } else withContext(Dispatchers.IO) {
                            tappedAt = SystemClock.uptimeMillis()
                            // Drawn without its address: tapped like a finger would. The app then asks whether to
                            // open the website, with the address written out, which the next look reads as a link.
                            val (x, y) = part[2].split(',').map(String::toFloat)
                            launcher.tapAt(x, y)
                        }
                    }
                    seen = next
                    delay(CAR_LINK_POLL_MS)
                }
            }
            if (!started) Placeholder(stringResource(R.string.standalone_starting), loading = true)
        } else {
            // Driving, a voice request isn't kept for later: it would open voice mode unasked once parked.
            LaunchedEffect(Unit) { if (runtime.ui.assistantVoice == packageName) runtime.ui.assistantVoice = null }
            Placeholder(stringResource(R.string.standalone_paused_driving), loading = false, icon = Icons.Rounded.PauseCircle)
        }
    }
}

/**
 * [packageName] running on the privileged [launcher]'s virtual display, drawn here at this size,
 * with the car's touches forwarded to it. The first surface launches the app; a later one (the
 * same app shown elsewhere, full screen say) only moves the display onto it. [onStarted] says
 * whether the launch worked. With [tablet], the display's density is lowered until it is at
 * least 600 dp each way: apps then lay out as on a tablet, and many stop locking themselves to
 * portrait, which on a short wide car pane left them a sliver in the middle.
 */
@Composable
internal fun HostedAppSurface(launcher: AppLauncher, packageName: String, dpi: Int, modifier: Modifier, tablet: Boolean = false, onStarted: (Boolean) -> Unit = {}) {
    fun dpiFor(w: Int, h: Int) = if (tablet) hostedDpi(w, h, dpi) else dpi
    Box(modifier) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val surface = Surface(st)
                            // connect() blocks (binds Shizuku / spawns su), so do it off the main thread.
                            Thread {
                                // Already running (the kept assistant): only moved onto this surface.
                                if (launcher.connected) { launcher.resize(surface, w, h, dpiFor(w, h)); onStarted(true) }
                                else if (launcher.connect()) onStarted(launcher.start(surface, w, h, dpiFor(w, h), packageName, Settings(ctx).standaloneKeepScreenAwake) >= 0)
                            }.apply { isDaemon = true }.start()
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            launcher.resize(Surface(st), w, h, dpiFor(w, h))
                        }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Forward the car's touches to the launched app's display, every finger: two pinch to zoom a map.
        Box(Modifier.fillMaxSize().pointerInput(packageName) {
            awaitPointerEventScope {
                var fingers = emptyList<Finger>()
                var downTime = 0L
                val ids = HashMap<androidx.compose.ui.input.pointer.PointerId, Int>()
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    ids.keys.retainAll(pressed.mapTo(HashSet()) { it.id })
                    // A new finger gets an id no finger had a moment ago: one lifting just now still counts as down until its UP.
                    val now = pressed.map { c -> Finger(ids.getOrPut(c.id) { (0..ids.size + fingers.size).first { id -> id !in ids.values && fingers.none { it.id == id } } }, c.position.x, c.position.y) }
                    val t = event.changes.maxOf { it.uptimeMillis }
                    if (fingers.isEmpty() && now.isNotEmpty()) downTime = t
                    touchEvents(fingers, now).forEach { launcher.touches(downTime, t, it) }
                    fingers = now
                    event.changes.forEach { it.consume() }
                }
            }
        })
    }
}

/** A finger on the car screen: its pointer [id] (0 for the first) and where it is. */
data class Finger(val id: Int, val x: Float, val y: Float)

/** A touch event for an app's display: its MotionEvent [action] and every finger down then, in order. */
data class Touch(val action: Int, val fingers: List<Finger>)

/**
 * The touch events from the fingers [before] to those [after], as Android expects them: every event
 * names exactly the fingers Android counts as down then, in one order. First a MOVE if one moved
 * (lifted ones too, still down until their UP), then fingers lifted (a POINTER_UP, the last an UP),
 * then new fingers (the first a DOWN, any more a POINTER_DOWN). Android drops an event naming other
 * fingers, and from then on every event, until the app took no touch at all any more.
 */
internal fun touchEvents(before: List<Finger>, after: List<Finger>): List<Touch> = buildList {
    fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    // Those Android counts as down, where they are now (a lifted one where it was last).
    var held = before.map { b -> after.firstOrNull { it.id == b.id } ?: b }
    if (held != before) add(Touch(MotionEvent.ACTION_MOVE, held))
    for (f in held.filter { h -> after.none { it.id == h.id } }) {
        add(Touch(if (held.size == 1) MotionEvent.ACTION_UP else pointer(MotionEvent.ACTION_POINTER_UP, held.indexOf(f)), held))
        held = held - f
    }
    for (f in after.filter { n -> before.none { it.id == n.id } }) {
        held = held + f
        add(Touch(if (held.size == 1) MotionEvent.ACTION_DOWN else pointer(MotionEvent.ACTION_POINTER_DOWN, held.size - 1), held))
    }
}

/** The density for a [w]×[h] pixel display to be at least 600 dp each way, no higher than [dpi] and no lower than the helper's floor of 120. */
internal fun hostedDpi(w: Int, h: Int, dpi: Int): Int = minOf(dpi, minOf(w, h) * 160 / 600).coerceAtLeast(120)

/**
 * Starts an assistant listening. With a start [link] (a project), the link first, then its voice
 * button; a project opens on its page, so a new chat there first, which has the button. Without a
 * link, or when the buttons don't show, the app's voice [shortcut], in a new chat.
 */
internal fun startVoice(link: String, openLink: (String) -> Boolean, tapLabel: (String, Long) -> Boolean, shortcut: () -> Boolean) {
    // Whichever the page shows first: a project's page has had either. After a new chat, its voice button;
    // after the voice button, nothing matches any more, so the second look just runs out.
    val linked = link.isNotEmpty() && openLink(link) &&
        tapLabel("$VOICE_BUTTON|$NEW_CHAT_BUTTON", VOICE_BUTTON_WAIT_MS).also { if (it) tapLabel(VOICE_BUTTON, VOICE_AFTER_CHAT_MS) }
    if (!linked) shortcut()
}

/** What an assistant's voice-mode shortcut has in its id (Claude: quick_action_voice). */
private const val VOICE_SHORTCUT = "voice"

/** The emojis the assistant puts car links behind (docs/assistant-links.md), when it draws them without their address. */
internal const val CAR_LINK_TAP = "^(\uD83E\uDDED|\uD83C\uDFC1|\u25B6|\u23F8|\u23ED|\u23EE|\uD83D\uDCF1|\uD83C\uDF19|\u2600|\u2139|\uD83D\uDCE8|\u2709|\u2705)\uFE0F?$"


/** The assistant's voice mode's end button (Claude: "Beenden"), pressed once a car link ran. */
private const val END_VOICE_BUTTON = "^(Beenden|End)$"
private const val CLOSE_WAIT_MS = 1_500L

/** Whether an address in the assistant's "Open this website?" prompt is from the emoji AARE tapped at [tappedAt]. */
internal fun ownPrompt(tappedAt: Long, now: Long) = tappedAt > 0 && now - tappedAt < OWN_PROMPT_MS

/** How long after AARE taps a link emoji the prompt it opens counts as that tap's. */
private const val OWN_PROMPT_MS = 15_000L
private const val CAR_LINK_POLL_MS = 1_500L

/** How long after the assistant shows its car links count as the chat's own, not a new reply's. */
private const val CAR_LINK_SETTLE_MS = 3_000L

/**
 * Of the [fresh] car links, those to run: every address, but of the emojis only the lowest on screen
 * ([now]), where a new reply ends. One higher up came into view by scrolling back.
 */
internal fun carLinksToRun(fresh: List<String>, now: List<String>): List<String> {
    val lowest = now.lastOrNull { it.startsWith("tap\t") }
    return fresh.filter { !it.startsWith("tap\t") || it == lowest }
}

/**
 * The car links in [now] (the helper's carLinks lines, top to bottom) that weren't there before: more of
 * one than ever [seen] at once. A tapped emoji counts by its text, not its place, which moves as the chat
 * scrolls. An address counts only while shown: the app's "Open this website?" prompt shows it, then goes,
 * and the same place asked for again shows it anew. Returns them in screen order, with the counts to
 * remember next time.
 */
internal fun newCarLinks(seen: Map<String, Int>, now: List<String>): Pair<List<String>, Map<String, Int>> {
    fun key(line: String) = line.split('\t').take(2).joinToString("\t")
    val counts = now.groupingBy(::key).eachCount()
    val fresh = counts.flatMap { (k, n) ->
        now.indices.filter { key(now[it]) == k }.takeLast((n - (seen[k] ?: 0)).coerceAtLeast(0))
    }.sorted().map { now[it] }
    return fresh to (seen.filterKeys { !it.startsWith("url\t") } + counts.mapValues { (k, n) -> if (k.startsWith("url\t")) n else maxOf(n, seen[k] ?: 0) })
}

/** The label of an assistant's voice-mode button, in English or German (Claude: "Voice mode", "Sprachmodus"). */
internal const val VOICE_BUTTON = "^(voice mode|sprachmodus)"

/** The label of the button that starts a chat on a project's page (Claude: "New session", "Neue Sitzung"). */
internal const val NEW_CHAT_BUTTON = "^(new session|new chat|neue sitzung|neuer chat)$"

/** How long the start link may take to show a button: a project's page can take seconds to load in the car. */
private const val VOICE_BUTTON_WAIT_MS = 8_000L

/** How long to look for the voice button after a new chat. */
private const val VOICE_AFTER_CHAT_MS = 4_000L
