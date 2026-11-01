package net.prototux.aare.ui.car

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.prototux.aare.R
import net.prototux.aare.notify.PhoneGuidance
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.ui.theme.LocalCarDimens

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
) {
    if (state.carPlayStyle) {
        CarPlayShell(state, actions, appContent, navMap, overlay)
        return
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val portrait = maxHeight > maxWidth
            if (portrait || state.railBottom) {
                Column(Modifier.fillMaxSize()) {
                    ContentArea(state, actions, appContent, navMap, Modifier.weight(1f))
                    Rail(state, actions, horizontal = true)
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    if (!state.railOnRight) Rail(state, actions, horizontal = false)
                    ContentArea(state, actions, appContent, navMap, Modifier.weight(1f))
                    if (state.railOnRight) Rail(state, actions, horizontal = false)
                }
            }
            overlay(Modifier.align(Alignment.BottomCenter))
            NoticeOverlay(state, actions, Modifier.align(Alignment.TopCenter))
            CallOverlay(state, actions, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun ContentArea(
    state: CarUiState,
    actions: CarActions,
    appContent: @Composable (CarAppEntry, Modifier) -> Unit,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    modifier: Modifier,
) {
    val gutter = LocalCarDimens.current.gutter
    AnimatedContent(
        targetState = state.screen,
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
        modifier = modifier.fillMaxHeight(),
        label = "screen",
    ) { screen ->
        when (screen) {
            CarScreen.Dashboard -> Box(Modifier.fillMaxSize()) {
                Wallpaper(state, Modifier.fillMaxSize())
                Dashboard(state, actions, navMap, Modifier.fillMaxSize().padding(gutter))
            }
            CarScreen.Launcher -> Launcher(state, actions, Modifier.fillMaxSize())
            is CarScreen.App -> {
                val app = state.app(screen.appId)
                if (app != null) appContent(app, Modifier.fillMaxSize()) else Dashboard(state, actions, navMap, Modifier.fillMaxSize().padding(gutter))
            }
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
private fun Rail(state: CarUiState, actions: CarActions, horizontal: Boolean) {
    // The "dark bar" setting: night colors for the rail even when the rest is in day colors.
    if (state.darkBar && !state.night) {
        CarTheme(night = true) { RailContent(state, actions, horizontal) }
    } else {
        RailContent(state, actions, horizontal)
    }
}

@Composable
private fun RailContent(state: CarUiState, actions: CarActions, horizontal: Boolean) {
    val d = LocalCarDimens.current
    val nav = state.app(state.activeNavAppId)
    val media = state.app(state.activeMediaAppId)
    val phone = state.apps.firstOrNull { it.kind == CarAppKind.PHONE }
    val settings = state.apps.firstOrNull { it.kind == CarAppKind.SETTINGS }
    fun on(app: CarAppEntry?) = app != null && state.screen == CarScreen.App(app.id)
    // Like Android Auto's taskbar: the apps used last, besides the fixed ones (one on a side rail, for height).
    val fixed = setOfNotNull(nav?.id, media?.id, phone?.id, settings?.id)
    val recent = state.recentAppIds.filter { it !in fixed }.mapNotNull(state::app)
    // A bottom bar on a portrait screen is short of width (76 dp per button): fewer apps and no
    // weather there, the launcher has them all. Landscape bars keep everything.
    val conf = LocalConfiguration.current
    val tightBar = horizontal && conf.screenHeightDp > conf.screenWidthDp && conf.screenWidthDp < 800
    val tighterBar = tightBar && conf.screenWidthDp < 640
    val apps: @Composable (Int) -> Unit = { recentSlots ->
        RailApp(nav, Icons.Rounded.Map, on(nav)) { nav?.let(actions::openApp) ?: actions.openLauncher() }
        RailApp(media, Icons.Rounded.MusicNote, on(media)) { media?.let(actions::openApp) ?: actions.openLauncher() }
        recent.take(recentSlots).forEach { app -> RailApp(app, Icons.Rounded.Apps, on(app)) { actions.openApp(app) } }
        phone?.let { RailApp(it, Icons.Rounded.Call, on(it)) { actions.openApp(it) } }
        settings?.takeIf { !tighterBar }?.let { RailApp(it, Icons.Rounded.Settings, on(it)) { actions.openApp(it) } }
    }
    val menu: @Composable () -> Unit = {
        if (state.screen == CarScreen.Dashboard) RailButton(Icons.Rounded.Apps, stringResource(R.string.dash_menu_apps), selected = false) { actions.openLauncher() }
        else RailButton(Icons.Rounded.Home, stringResource(R.string.dash_menu_home), selected = false) { actions.goHome() }
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
                Clock(); if (!tightBar) WeatherChip(state.weather); PhoneStatusIcons(); NotificationBell(state) { bell() }
            } }
            if (state.driverOnRight) status() else driverEnd()
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { apps(if (tightBar) 0 else 2) }
            }
            if (state.driverOnRight) driverEnd() else status()
        }
    } else {
        Column(
            Modifier.fillMaxHeight().width(d.railWidth).background(container).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Clock()
            Spacer(Modifier.height(6.dp))
            PhoneStatusIcons()
            Spacer(Modifier.height(4.dp))
            WeatherChip(state.weather)
            NotificationBell(state) { bell() }
            Spacer(Modifier.weight(1f))
            // A side rail has room for a recent app only on tall screens.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                apps(if (LocalConfiguration.current.screenHeightDp >= 800) 1 else 0)
            }
            Spacer(Modifier.weight(1f))
            menu()
            Spacer(Modifier.height(8.dp))
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
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
}

@Composable
private fun RailButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(d.touchTarget).clip(RoundedCornerShape(24.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(d.iconSize))
    }
}

@Composable
private fun RailApp(app: CarAppEntry?, fallback: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    if (app?.icon == null) {
        RailButton(fallback, app?.label ?: "", selected, onClick)
        return
    }
    val ring = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Box(
        Modifier.size(d.touchTarget).clip(RoundedCornerShape(24.dp)).background(ring).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(app.icon.asImageBitmap(), app.label, Modifier.size(48.dp).clip(CircleShape))
    }
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val d = LocalCarDimens.current
    val bg = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(d.touchTarget).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, contentDescription = stringResource(R.string.assistant_mic_label), tint = fg, modifier = Modifier.size(d.iconSize))
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
    BoxWithConstraints(modifier) {
        val narrow = maxWidth < 560.dp
        if (maxHeight > maxWidth * 1.2f && maxHeight >= 700.dp) {
            // portrait screens (720x1280, 1080x1920…): the cards stacked, the map on top
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gutter)) {
                NavCard(state, actions, navMap, Modifier.weight(1.6f).fillMaxWidth())
                MediaCard(state, actions, compact = false, modifier = Modifier.weight(1f).fillMaxWidth())
                val event = state.nextEvent
                val latest = state.history.firstOrNull()
                when {
                    event != null -> Card(Modifier.fillMaxWidth().height(180.dp)) { CalendarCard(event, actions::navigateTo, Modifier.fillMaxSize()) }
                    latest != null -> Card(Modifier.fillMaxWidth().height(180.dp), onClick = {
                        state.apps.firstOrNull { it.kind == CarAppKind.NOTIFICATIONS }?.let(actions::openApp)
                    }) { LatestNotification(latest, state.drivingRestricted) }
                }
            }
        } else if (narrow) {
            // small screens: one big card, media as a strip under it
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gutter)) {
                NavCard(state, actions, navMap, Modifier.weight(1f).fillMaxWidth())
                MediaCard(state, actions, compact = true, modifier = Modifier.fillMaxWidth().height(112.dp))
            }
        } else if (maxWidth > maxHeight * 2.1f) {
            // very wide screens (1920x720, 2560x720…): three cards side by side, like Android Auto's
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gutter)) {
                NavCard(state, actions, navMap, Modifier.weight(1.6f).fillMaxHeight())
                MediaCard(state, actions, compact = false, modifier = Modifier.weight(1f).fillMaxHeight())
                val event = state.nextEvent
                val latest = state.history.firstOrNull()
                when {
                    event != null -> Card(Modifier.weight(0.9f).fillMaxHeight()) { CalendarCard(event, actions::navigateTo, Modifier.fillMaxSize()) }
                    latest != null -> Card(Modifier.weight(0.9f).fillMaxHeight(), onClick = {
                        state.apps.firstOrNull { it.kind == CarAppKind.NOTIFICATIONS }?.let(actions::openApp)
                    }) { LatestNotification(latest, state.drivingRestricted) }
                }
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gutter)) {
                NavCard(state, actions, navMap, Modifier.weight(1.65f).fillMaxHeight())
                val event = state.nextEvent
                if (event == null) {
                    MediaCard(state, actions, compact = false, modifier = Modifier.weight(1f).fillMaxHeight())
                } else {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gutter)) {
                        MediaCard(state, actions, compact = false, modifier = Modifier.weight(1.3f).fillMaxWidth())
                        Card(Modifier.weight(1f).fillMaxWidth()) { CalendarCard(event, actions::navigateTo, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val r = LocalCarDimens.current.cardRadius
    Box(
        modifier
            .clip(RoundedCornerShape(r))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
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
        Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick).padding(start = 10.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, 44)
        Spacer(Modifier.width(12.dp))
        Text(app.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun AppIcon(app: CarAppEntry, sizeDp: Int) {
    if (app.icon != null) {
        Image(app.icon.asImageBitmap(), app.label, Modifier.size(sizeDp.dp).clip(CircleShape))
    } else {
        Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(app.label.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
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
                    LinearProgressIndicator(
                        progress = { (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) },
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
    val shape = RoundedCornerShape(16.dp)
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
private fun Launcher(state: CarUiState, actions: CarActions, modifier: Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = modifier,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Already in the user's order (CarRuntime.applyApps).
        items(state.apps, key = { it.id }) { app ->
            Column(
                Modifier.clip(RoundedCornerShape(24.dp)).clickable { actions.openApp(app) }.padding(12.dp),
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
        modifier.padding(16.dp).fillMaxWidth(0.6f).clip(RoundedCornerShape(28.dp))
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
            Text(if (state.drivingRestricted && notice.isMessage) stringResource(R.string.notif_new_message_placeholder) else notice.text,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        notice.actions.forEach { (label, run) ->
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { run(); actions.dismissNotice(notice) }.padding(16.dp))
        }
        RoundIcon(Icons.Rounded.Close, stringResource(R.string.common_dismiss), filled = false) { actions.dismissNotice(notice) }
    }
}
