package net.prototux.aare.ui.car

import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import net.prototux.aare.R
import net.prototux.aare.notify.PhoneGuidance
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.ui.theme.LocalCarDimens
import net.prototux.aare.ui.theme.LocalCarTheme
import net.prototux.aare.ui.theme.SegmentFont
import net.prototux.aare.ui.theme.appIconFilter

/**
 * Root of the car screen: a rail on the driver's side and the content area.
 *
 * [appContent] renders an app full screen (templates or the media browser); [navMap] renders a
 * navigation app's map surface, both on the dashboard card and behind its templates.
 */
@Composable
fun CarShell(
    state: CarUiState,
    actions: CarActions,
    appContent: @Composable (CarAppEntry, Modifier) -> Unit,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    /** Drawn over everything but notices: the voice assistant's bar. */
    overlay: @Composable (Modifier) -> Unit = {},
    /** Insets the UI into the head unit's visible area (the codec frame minus its margins). */
    modifier: Modifier = Modifier,
) {
    when (state.layout) {
        CarLayout.DOCK -> { DockShell(state, actions, appContent, navMap, overlay, modifier); return }
        CarLayout.CLASSIC -> { ClassicShell(state, actions, appContent, navMap, overlay, modifier); return }
        CarLayout.TASKBAR -> Unit
    }
    // A long press on a rail or launcher app asks which pane to open it in (see PaneChooser).
    var paneChoice by remember { mutableStateOf<PaneChoice?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // A long press on the launcher button shows the shortcuts next to it (see ShortcutList).
    var shortcutsAt by remember { mutableStateOf<Pair<Offset, Boolean>?>(null) }
    val menus = remember(state) {
        RailMenus(
            choosePane = { app, center, horizontal -> paneChoice = PaneChoice(app, center - origin, horizontal, state.railOnRight) },
            showShortcuts = { center, horizontal -> shortcutsAt = center - origin to horizontal },
        )
    }
    // Closed by the car's Back key, and when something else takes the screen (a call, the assistant).
    BackHandler(enabled = paneChoice != null || shortcutsAt != null) { paneChoice = null; shortcutsAt = null }
    LaunchedEffect(state.screen) { paneChoice = null; shortcutsAt = null }
    val fullScreen = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    CompositionLocalProvider(LocalFullScreen provides fullScreen, LocalRailMenus provides menus) { Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().then(modifier).onGloballyPositioned { origin = it.positionInRoot() }) {
            val portrait = maxHeight > maxWidth
            // The visible area, not LocalConfiguration: that is the whole codec frame, margins included.
            val area = DpSize(maxWidth, maxHeight)
            if (portrait || state.railBottom) {
                Column(Modifier.fillMaxSize()) {
                    ContentArea(state, actions, appContent, navMap, Modifier.weight(1f))
                    Rail(state, actions, horizontal = true, area)
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    if (!state.railOnRight) Rail(state, actions, horizontal = false, area)
                    ContentArea(state, actions, appContent, navMap, Modifier.weight(1f))
                    if (state.railOnRight) Rail(state, actions, horizontal = false, area)
                }
            }
            shortcutsAt?.let { (at, horizontal) -> ShortcutList(state, actions, at, horizontal) { shortcutsAt = null } }
            paneChoice?.let { c ->
                PaneChooser(c, onPick = { left -> paneChoice = null; actions.openInPane(c.app, left) }, onDismiss = { paneChoice = null })
            }
            fullScreen.value?.let { content -> Box(Modifier.fillMaxSize().background(Color.Black)) { content() } }
            overlay(Modifier.align(Alignment.BottomCenter))
            NoticeOverlay(state, actions, Modifier.align(Alignment.TopCenter))
            CallOverlay(state, actions, Modifier.align(Alignment.TopCenter))
        }
    } }
}

/** Columns of the shortcuts' grid for [count] apps: the smallest square that holds them, at most four wide. */
internal fun shortcutColumns(count: Int): Int = (1..4).firstOrNull { it * it >= count } ?: 4

/**
 * The launcher button's shortcuts, picked on the phone, beside the held button ([at], in the
 * shell's coordinates) and over everything else. Each works like a rail app: a tap opens it, a
 * long press offers the panes. A tap elsewhere closes it.
 */
@Composable
private fun ShortcutList(state: CarUiState, actions: CarActions, at: Offset, horizontal: Boolean, onDone: () -> Unit) {
    val menus = LocalRailMenus.current
    val apps = state.shortcutAppIds.mapNotNull(state::app)
    val gap = with(LocalDensity.current) { (LocalCarDimens.current.railButton / 2 + 12.dp).toPx() }
    val half = with(LocalDensity.current) { (LocalCarDimens.current.railButton / 2).toPx() }
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDone() } }) {
        Column(
            Modifier
                // Placed once measured: beside a side rail's button, its bottom level with the
                // button's; above a bottom bar's. Kept on screen, scrolling when taller than it.
                .layout { m, c ->
                    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                    val x = when {
                        horizontal -> at.x - p.width / 2
                        state.railOnRight -> at.x - gap - p.width
                        else -> at.x + gap
                    }
                    val y = if (horizontal) at.y - gap - p.height else at.y + half - p.height
                    layout(c.maxWidth, c.maxHeight) {
                        p.place(x.roundToInt().coerceIn(0, maxOf(0, c.maxWidth - p.width)), y.roundToInt().coerceIn(0, maxOf(0, c.maxHeight - p.height)))
                    }
                }
                .shadow(12.dp, shape).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A small window of apps in a square-ish grid: 2×2, 3×3, then four columns.
            apps.chunked(shortcutColumns(apps.size)).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { app ->
                        val pane = RailPane(choose = { center: Offset -> onDone(); menus.choosePane(app, center, horizontal) })
                        RailApp(app, Icons.Rounded.Apps, state.screen.shows(app.id), pane) { onDone(); actions.openApp(app) }
                    }
                }
            }
        }
    }
}

/** What the car shell draws over the whole car screen, rail included (a video in full screen), or null. Null outside the shell. */
val LocalFullScreen = compositionLocalOf<MutableState<(@Composable () -> Unit)?>?> { null }

/** The screen's content: the dashboard (or, with [launcherHome], the app grid as the home screen), the app grid or an app. */
@Composable
internal fun ContentArea(
    state: CarUiState,
    actions: CarActions,
    appContent: @Composable (CarAppEntry, Modifier) -> Unit,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    modifier: Modifier,
    launcherHome: Boolean = false,
) {
    val gutter = LocalCarDimens.current.gutter
    // Give rotary / D-pad head units a starting point: focus lands on the first focusable of each
    // screen when it opens, so the highlight is visible without a touch. Harmless on touch units.
    // Asked again on an input mode change: in touch mode the request fails, so the first knob
    // detent (which leaves touch mode) asks again. Not when the content still holds focus (a tap
    // between two knob turns), or focus would jump back to the first item.
    val focusEntry = remember { FocusRequester() }
    var contentFocused by remember { mutableStateOf(false) }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(state.screen) { runCatching { focusEntry.requestFocus() } }
    LaunchedEffect(inputMode) { if (!contentFocused) runCatching { focusEntry.requestFocus() } }
    // Each app's screen is movable content, so it moves between full screen and a pane (or from one
    // pane to the other) instead of being torn down and built again: the phone screen, a standalone
    // app and the browser would lose their picture, process or page. For the same reason app
    // screens and splits share one content key and don't cross-fade: a fade composes both at once.
    val currentAppContent by rememberUpdatedState(appContent)
    val appScreens = remember { HashMap<String, @Composable (Modifier) -> Unit>() }
    val appScreen: @Composable (CarAppEntry, Modifier) -> Unit = { app, mod ->
        appScreens.getOrPut(app.id) { movableContentOf { m: Modifier -> state.app(app.id)?.let { currentAppContent(it, m) } } }(mod)
    }
    AnimatedContent(
        targetState = state.screen,
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
        contentKey = { if (it is CarScreen.App || it is CarScreen.Split) "apps" else it },
        modifier = modifier.fillMaxHeight().onFocusChanged { contentFocused = it.hasFocus }
            .focusRequester(focusEntry).focusGroup(),
        label = "screen",
    ) { screen ->
        when (screen) {
            CarScreen.Dashboard -> if (launcherHome) Launcher(state, actions, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize()) {
                Wallpaper(state, Modifier.fillMaxSize())
                Dashboard(state, actions, navMap, Modifier.fillMaxSize().padding(gutter))
            }
            CarScreen.Launcher -> Launcher(state, actions, Modifier.fillMaxSize())
            is CarScreen.App -> {
                val app = state.app(screen.appId)
                when {
                    app != null -> appScreen(app, Modifier.fillMaxSize())
                    launcherHome -> Launcher(state, actions, Modifier.fillMaxSize())
                    else -> Dashboard(state, actions, navMap, Modifier.fillMaxSize().padding(gutter))
                }
            }
            is CarScreen.Split -> SplitPane(screen, state, actions, appScreen, Modifier.fillMaxSize())
        }
    }
}

// ---------------------------------------------------------------- rail

/**
 * The rail, laid out like Android Auto's taskbar: the voice button at the driver's end with the
 * menu button (the app grid on the home screen, home anywhere else) next to it, the apps in the
 * middle (navigation, media, phone, settings), the clock and the phone's status at the far end.
 */
@Composable
private fun Rail(state: CarUiState, actions: CarActions, horizontal: Boolean, area: DpSize) {
    // The "dark bar" setting: night colors for the rail even when the rest is in day colors.
    if (state.darkBar && !state.night) {
        CarTheme(night = true) { RailContent(state, actions, horizontal, area) }
    } else {
        RailContent(state, actions, horizontal, area)
    }
}

/** What a side rail has room for besides the clock, bell, menu and mic, from [sideRailFit]. */
internal data class SideRailFit(val recentSlots: Int, val status: Boolean, val settings: Boolean)

/**
 * Fits the side rail to its [height] inside the padding. Everything takes about 534 dp (clock and
 * bell 72, phone status and weather 54, the four apps 276, menu and mic 132), so on shorter screens
 * the phone status and weather go first, then Settings (the launcher has it); below that the apps
 * scroll. Dropping items rather than shrinking them keeps every button its full size.
 */
internal fun sideRailFit(height: Dp) = SideRailFit(
    // A recent app when one more button (64 dp and its gap) fits besides everything else.
    recentSlots = if (height >= 602.dp) 1 else 0,
    status = height >= 534.dp,
    settings = height >= 480.dp,
)

@Composable
private fun RailContent(state: CarUiState, actions: CarActions, horizontal: Boolean, area: DpSize) {
    val d = LocalCarDimens.current
    val nav = state.app(state.activeNavAppId)
    val media = state.app(state.activeMediaAppId)
    val phone = state.apps.firstOrNull { it.kind == CarAppKind.PHONE }
    val settings = state.apps.firstOrNull { it.kind == CarAppKind.SETTINGS }
    fun on(app: CarAppEntry?) = app != null && state.screen.shows(app.id)
    // Held: offer the two panes for it, next to the icon.
    val menus = LocalRailMenus.current
    val hold: (CarAppEntry) -> RailPane = { app -> RailPane({ center -> menus.choosePane(app, center, horizontal) }) }
    // Like Android Auto's taskbar: the apps used last, besides the fixed ones (one on a side rail, for height).
    val fixed = setOfNotNull(nav?.id, media?.id, phone?.id, settings?.id)
    val recent = state.recentAppIds.filter { it !in fixed }.mapNotNull(state::app)
    // A bottom bar on a portrait screen is short of width (76 dp per button): fewer apps and no
    // weather there, the launcher has them all. Landscape bars keep everything.
    val tightBar = horizontal && area.height > area.width && area.width < 800.dp
    val tighterBar = tightBar && area.width < 640.dp
    // Picked on the phone: those, all of them, scrolling when they don't fit.
    val picked = state.railAppIds.mapNotNull(state::app)
    val apps: @Composable (Int, Boolean) -> Unit = auto@{ recentSlots, withSettings ->
        if (picked.isNotEmpty()) {
            picked.forEach { app -> RailApp(app, Icons.Rounded.Apps, on(app), hold(app)) { actions.openApp(app) } }
            return@auto
        }
        RailApp(nav, Icons.Rounded.Map, on(nav), nav?.let(hold)) { nav?.let(actions::openApp) ?: actions.openLauncher() }
        RailApp(media, Icons.Rounded.MusicNote, on(media), media?.let(hold)) { media?.let(actions::openApp) ?: actions.openLauncher() }
        recent.take(recentSlots).forEach { app -> RailApp(app, Icons.Rounded.Apps, on(app), hold(app)) { actions.openApp(app) } }
        phone?.let { RailApp(it, Icons.Rounded.Call, on(it), hold(it)) { actions.openApp(it) } }
        settings?.takeIf { withSettings }?.let { RailApp(it, Icons.Rounded.Settings, on(it), hold(it)) { actions.openApp(it) } }
    }
    // Held: the shortcuts picked on the phone, if any.
    val shortcuts = RailPane({ center -> menus.showShortcuts(center, horizontal) }).takeIf { state.shortcutAppIds.isNotEmpty() }
    val menu: @Composable () -> Unit = {
        if (state.screen == CarScreen.Dashboard) RailButton(Icons.Rounded.Apps, stringResource(R.string.dash_menu_apps), selected = false, shortcuts) { actions.openLauncher() }
        else RailButton(Icons.Rounded.Home, stringResource(R.string.dash_menu_home), selected = false, shortcuts) { actions.goHome() }
    }
    val mic: @Composable () -> Unit = { MicButton(state.listening) { actions.assistant() } }
    val bell = { state.apps.firstOrNull { it.kind == CarAppKind.NOTIFICATIONS }?.let(actions::openApp) }
    val container = MaterialTheme.colorScheme.surfaceContainerLowest
    if (horizontal) {
        Row(
            Modifier.fillMaxWidth().height(d.railWidth).background(container).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val driverEnd: @Composable () -> Unit = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.driverOnRight) { menu(); mic() } else { mic(); menu() }
            } }
            val status: @Composable () -> Unit = { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Clock(); if (!tightBar) WeatherChip(state.weather.takeIf { state.weatherInBar }); PhoneStatusIcons(); NotificationBell(state) { bell() }
            } }
            if (state.driverOnRight) status() else driverEnd()
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) { apps(if (tightBar) 0 else 2, !tighterBar) }
            }
            if (state.driverOnRight) driverEnd() else status()
        }
    } else {
        val fit = sideRailFit(area.height - 24.dp)
        Column(
            Modifier.fillMaxHeight().width(d.railWidth).background(container).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Clock()
            if (fit.status) {
                Spacer(Modifier.height(6.dp))
                PhoneStatusIcons()
                Spacer(Modifier.height(4.dp))
                WeatherChip(state.weather.takeIf { state.weatherInBar })
            }
            NotificationBell(state) { bell() }
            // The apps are the only weighted child, so they are measured last and are the part that
            // gives up space: menu and mic (the only way home on touch-only units) keep their size.
            // Grouped under the clock like a dock, so spare height collects above the menu instead
            // of opening gaps around the apps.
            Box(Modifier.weight(1f).padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { apps(fit.recentSlots, fit.settings) }
            }
            menu()
            Spacer(Modifier.height(4.dp))
            mic()
        }
    }
}

@Composable
internal fun Clock() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000 - now % 1_000 + 5)
        }
    }
    // Hours and minutes only: "AM/PM" doesn't fit the rail, and the car shows the period anyway.
    val text = android.text.format.DateFormat.format(
        if (android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)) "H:mm" else "h:mm", now,
    ).toString()
    if (LocalCarTheme.current.segmentClock) SegmentClock(text)
    else Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
}

/**
 * The time on a seven-segment display: the lit digits over the unlit "88:88", as on an old digital
 * dashboard. 22 sp keeps "88:88" (3.5 em) inside the 96 dp side bar.
 */
@Composable
private fun SegmentClock(time: String) {
    val style = MaterialTheme.typography.titleMedium.copy(fontFamily = SegmentFont, fontSize = 22.sp, letterSpacing = 0.sp)
    val lit = MaterialTheme.colorScheme.onSurface
    Box(contentAlignment = Alignment.CenterEnd) {
        Text("88:88", style = style.copy(shadow = null), color = lit.copy(alpha = 0.12f), maxLines = 1)
        // "!" is the font's digit-wide blank: "9:41" becomes "!9:41", over "88:88".
        Text(time.padStart(5, '!'), style = style, color = lit, maxLines = 1)
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, selected: Boolean, pane: RailPane? = null, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(d.railButton).clip(MaterialTheme.shapes.medium).background(bg).carFocusHighlight(MaterialTheme.shapes.medium).railClick(pane, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(d.railIcon))
    }
}

@Composable
private fun RailApp(app: CarAppEntry?, fallback: ImageVector, selected: Boolean, pane: RailPane?, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    if (app?.icon == null) {
        RailButton(fallback, app?.label ?: "", selected, pane, onClick)
        return
    }
    val ring = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Box(
        Modifier.size(d.railButton).clip(MaterialTheme.shapes.medium).background(ring).carFocusHighlight(MaterialTheme.shapes.medium).railClick(pane, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(app.icon.asImageBitmap(), app.label, Modifier.size(44.dp).clip(LocalCarTheme.current.appIcon), colorFilter = appIconFilter())
    }
}

@Composable
internal fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val bg = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(d.railButton).clip(CircleShape).background(bg).carFocusHighlight(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, contentDescription = stringResource(R.string.assistant_mic_label), tint = fg, modifier = Modifier.size(d.railIcon))
    }
}

// ---------------------------------------------------------------- dashboard

@Composable
internal fun Dashboard(
    state: CarUiState,
    actions: CarActions,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    modifier: Modifier,
) {
    val gutter = LocalCarDimens.current.gutter
    // The map and the music always have something to show (a "where to?", "nothing playing").
    val shown: (DashCard) -> Boolean = { card ->
        when (card) {
            DashCard.MAP, DashCard.MEDIA -> true
            DashCard.CALENDAR -> state.nextEvent != null
            DashCard.NOTIFICATION -> state.history.isNotEmpty()
            DashCard.WEATHER -> state.weather != null
        }
    }
    // Keyed by card, so reordering moves a card instead of re-creating it: the map card keeps the
    // navigation app's surface (when it stays in the same row or column).
    val slot: @Composable (DashCard, Modifier, Boolean) -> Unit = { card, m, compact -> key(card) { DashSlot(card, state, actions, navMap, m, compact) } }
    // The user's cards in order (Settings > Dashboard), as many as the screen has room for: the
    // first gets the big space.
    BoxWithConstraints(modifier) {
        val narrow = maxWidth < 560.dp
        if (maxHeight > maxWidth * 1.2f && maxHeight >= 700.dp) {
            // portrait screens (720x1280, 1080x1920…): the cards stacked, the first two sharing the height
            val cards = dashboardSlots(state.dashboardCards, 3, shown)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gutter)) {
                cards.forEachIndexed { i, card ->
                    slot(card, when (i) {
                        0 -> Modifier.weight(1.6f).fillMaxWidth()
                        1 -> Modifier.weight(1f).fillMaxWidth()
                        else -> Modifier.fillMaxWidth().height(180.dp)
                    }, false)
                }
            }
        } else if (narrow) {
            // small screens: one big card, the second as a strip under it
            val cards = dashboardSlots(state.dashboardCards, 2, shown)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gutter)) {
                cards.forEachIndexed { i, card ->
                    slot(card, if (i == 0) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(112.dp), i > 0)
                }
            }
        } else if (maxWidth > maxHeight * 2.1f) {
            // very wide screens (1920x720, 2560x720…): three cards side by side, like Android Auto's
            val cards = dashboardSlots(state.dashboardCards, 3, shown)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gutter)) {
                cards.forEachIndexed { i, card ->
                    slot(card, Modifier.weight(when (i) { 0 -> 1.6f; 1 -> 1f; else -> 0.9f }).fillMaxHeight(), false)
                }
            }
        } else {
            // the big card, and the others stacked beside it, the first of them taller
            val cards = dashboardSlots(state.dashboardCards, 3, shown)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gutter)) {
                slot(cards.first(), Modifier.weight(1.65f).fillMaxHeight(), false)
                if (cards.size > 1) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gutter)) {
                    cards.drop(1).forEachIndexed { i, card ->
                        slot(card, Modifier.weight(if (i == 0 && cards.size > 2) 1.3f else 1f).fillMaxWidth(), false)
                    }
                }
            }
        }
    }
}

/** One dashboard card in its slot ([modifier] sizes it); [compact] for a thin strip. */
@Composable
private fun DashSlot(
    card: DashCard,
    state: CarUiState,
    actions: CarActions,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    modifier: Modifier,
    compact: Boolean,
) {
    when (card) {
        DashCard.MAP -> NavCard(state, actions, navMap, modifier)
        DashCard.MEDIA -> MediaCard(state, actions, compact = compact, modifier = modifier)
        DashCard.CALENDAR -> state.nextEvent?.let { event -> Card(modifier) { CalendarCard(event, actions::navigateTo, Modifier.fillMaxSize()) } }
        DashCard.NOTIFICATION -> state.history.firstOrNull()?.let { latest ->
            Card(modifier, onClick = { state.apps.firstOrNull { it.kind == CarAppKind.NOTIFICATIONS }?.let(actions::openApp) }) {
                LatestNotification(latest, state.drivingRestricted)
            }
        }
        DashCard.WEATHER -> state.weather?.let { weather -> Card(modifier) { WeatherCard(weather) } }
    }
}

@Composable
private fun Card(modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val r = LocalCarDimens.current.cardRadius
    val border = LocalCarTheme.current.cardBorder
    Box(
        modifier
            .clip(RoundedCornerShape(r))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .let { if (border > 0.dp) it.border(border, MaterialTheme.colorScheme.outline, RoundedCornerShape(r)) else it }
            .let { if (onClick != null) it.carFocusHighlight(RoundedCornerShape(r)).clickable(onClick = onClick) else it },
    ) { content() }
}

@Composable
private fun NavCard(state: CarUiState, actions: CarActions, navMap: @Composable (CarAppEntry, Modifier) -> Unit, modifier: Modifier) {
    val nav = state.app(state.activeNavAppId)
    val guidance = state.phoneGuidance
    Card(modifier) {
        if (guidance != null) {
            GuidanceCard(guidance)
        } else if (nav != null) {
            navMap(nav, Modifier.fillMaxSize())
        } else {
            val navApps = state.apps.filter { it.kind == CarAppKind.NAVIGATION }
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Map, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(if (navApps.isEmpty()) stringResource(R.string.nav_dashboard_no_app_title) else stringResource(R.string.nav_dashboard_where_to), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (navApps.isEmpty()) stringResource(R.string.nav_dashboard_install_hint) else stringResource(R.string.nav_dashboard_choose_app),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    navApps.take(4).forEach { app -> AppChip(app) { actions.openApp(app) } }
                }
            }
        }
    }
}

/** Directions from a navigation app running on the phone (Google Maps, Waze…), from its notification. */
@Composable
private fun GuidanceCard(g: PhoneGuidance) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            g.arrow?.let { arrow ->
                Image(arrow.asImageBitmap(), null, Modifier.size(112.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface, BlendMode.SrcIn))
                Spacer(Modifier.width(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(g.primary, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (g.secondary.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(g.secondary, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (g.detail.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(g.detail, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.nav_guidance_on_phone, g.appLabel), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppChip(app: CarAppEntry, onClick: () -> Unit) {
    Row(
        Modifier.height(64.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.secondaryContainer)
            .carFocusHighlight(MaterialTheme.shapes.extraLarge).clickable(onClick = onClick).padding(start = 10.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, 44)
        Spacer(Modifier.width(12.dp))
        Text(app.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun AppIcon(app: CarAppEntry, sizeDp: Int) {
    Box(contentAlignment = Alignment.BottomEnd) {
        if (app.icon != null) {
            Image(app.icon.asImageBitmap(), app.label, Modifier.size(sizeDp.dp).clip(LocalCarTheme.current.appIcon), colorFilter = appIconFilter())
        } else {
            Box(Modifier.size(sizeDp.dp).clip(LocalCarTheme.current.appIcon).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Text(app.label.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        // A corner badge marks an ordinary phone app (not an Android Auto one), so a NewPipe that
        // appears both as its car app and as a launched phone app is told apart at a glance.
        if (app.kind == CarAppKind.STANDALONE) {
            Box(
                Modifier.size((sizeDp * 0.36f).dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
                    .border((sizeDp * 0.03f).dp, MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Smartphone, stringResource(R.string.standalone_not_native),
                    tint = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.size((sizeDp * 0.22f).dp))
            }
        }
    }
}

@Composable
private fun MediaCard(state: CarUiState, actions: CarActions, compact: Boolean, modifier: Modifier) {
    val np = state.nowPlaying
    val media = state.app(np?.appId ?: state.activeMediaAppId)
    Card(modifier, onClick = { media?.let(actions::openApp) ?: actions.openLauncher() }) {
        if (np?.art != null && !compact) {
            Image(np.art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surfaceContainer))))
        }
        val content: @Composable () -> Unit = {
            if (np != null) {
                Text(np.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(np.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(media?.label ?: stringResource(R.string.media_fallback_label), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.media_nothing_playing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (compact) {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(np, 80)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) { content() }
                MediaButtons(np, actions)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Art(np, 72)
                    Spacer(Modifier.width(16.dp))
                    if (media != null) AppIcon(media, 28)
                }
                Spacer(Modifier.weight(1f))
                content()
                Spacer(Modifier.height(12.dp))
                if (np != null && np.durationMs > 0) {
                    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
                    LaunchedEffect(np.playing, np.updatedAtMs) {
                        now = android.os.SystemClock.elapsedRealtime()
                        while (np.playing) {
                            delay(500)
                            now = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                    LinearProgressIndicator(
                        progress = { mediaProgress(np.positionMs, np.durationMs, np.playing, np.updatedAtMs, now) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { MediaButtons(np, actions) }
            }
        }
    }
}

@Composable
private fun Art(np: NowPlaying?, sizeDp: Int) {
    val shape = MaterialTheme.shapes.small
    if (np?.art != null) {
        Image(np.art.asImageBitmap(), null, Modifier.size(sizeDp.dp).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(sizeDp.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size((sizeDp / 2).dp))
        }
    }
}

@Composable
private fun MediaButtons(np: NowPlaying?, actions: CarActions) {
    RoundIcon(Icons.Rounded.SkipPrevious, stringResource(R.string.media_action_previous), filled = false) { actions.mediaPrevious() }
    RoundIcon(if (np?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(R.string.media_action_play_pause), filled = true) { actions.mediaPlayPause() }
    RoundIcon(Icons.Rounded.SkipNext, stringResource(R.string.media_action_next), filled = false) { actions.mediaNext() }
}

@Composable
fun RoundIcon(icon: ImageVector, label: String, filled: Boolean, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val bg = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(d.touchTarget).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(d.iconSize))
    }
}

// ---------------------------------------------------------------- launcher

@Composable
internal fun Launcher(state: CarUiState, actions: CarActions, modifier: Modifier) {
    val menus = LocalRailMenus.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = modifier,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Already in the user's order (CarRuntime.applyApps).
        // Already in the user's order (CarRuntime.applyApps), less the rail's and the shortcuts' if so set.
        val pinned = if (state.launcherHidesPinned) (state.railAppIds + state.shortcutAppIds).toSet() else emptySet()
        items(state.apps.filter { it.id !in pinned }, key = { it.id }) { app ->
            Column(
                Modifier.clip(MaterialTheme.shapes.medium).railClick(RailPane({ center -> menus.choosePane(app, center, true) })) { actions.openApp(app) }.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIcon(app, 80)
                Spacer(Modifier.height(10.dp))
                Text(app.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- notices

@Composable
internal fun NoticeOverlay(state: CarUiState, actions: CarActions, modifier: Modifier) {
    val notice = state.notices.firstOrNull() ?: return
    LaunchedEffect(notice.key) {
        delay(8_000)
        actions.dismissNotice(notice)
    }
    Row(
        modifier.padding(16.dp).fillMaxWidth(0.6f).clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (notice.icon != null) {
            Image(notice.icon.asImageBitmap(), null, Modifier.size(56.dp).aspectRatio(1f).clip(CircleShape))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(notice.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // While driving a message shows who wrote, not what: it can be read aloud instead.
            Text(if (state.drivingRestricted && notice.message) stringResource(R.string.notif_new_message_placeholder) else notice.text,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        notice.actions.forEach { (label, run) ->
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { run(); actions.dismissNotice(notice) }.padding(16.dp))
        }
        RoundIcon(Icons.Rounded.Close, stringResource(R.string.common_dismiss), filled = false) { actions.dismissNotice(notice) }
    }
}

/** What a rail app offers besides a tap: a long press hands [choose] the button's center (root coordinates). */
private class RailPane(val choose: ((Offset) -> Unit)?)

/** The shell's menus over everything: the panes for an app at a point, and the launcher's shortcuts next to a button. */
private class RailMenus(val choosePane: (CarAppEntry, Offset, Boolean) -> Unit, val showShortcuts: (Offset, Boolean) -> Unit)

private val LocalRailMenus = staticCompositionLocalOf { RailMenus({ _, _, _ -> }, { _, _ -> }) }

/** A tap opens. With [pane], a long press offers the panes (or, on the launcher button, the shortcuts). */
private fun Modifier.railClick(pane: RailPane?, onClick: () -> Unit): Modifier = composed {
    var center by remember { mutableStateOf(Offset.Zero) }
    onGloballyPositioned { center = it.boundsInRoot().center }
        .combinedClickable(onClick = onClick, onLongClick = pane?.choose?.let { choose -> { choose(center) } })
}
