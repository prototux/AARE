package net.prototux.aare.ui.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.ui.theme.LocalCarTheme
import net.prototux.aare.ui.theme.appIconFilter

/**
 * The dock layout, like CarPlay's: a dock on the driver's side with the time, three apps, voice and
 * the home button; a home screen of app icons in pages; the dashboard. The theme gives the colours
 * and shapes; the dock and the home screen stay in night colours, as CarPlay's do.
 */
@Composable
internal fun DockShell(
    state: CarUiState,
    actions: CarActions,
    appContent: @Composable (CarAppEntry, Modifier) -> Unit,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    overlay: @Composable (Modifier) -> Unit,
    /** Insets the UI into the head unit's visible area (the codec frame minus its margins). */
    modifier: Modifier = Modifier,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoxWithConstraints(Modifier.fillMaxSize().then(modifier)) {
            val content: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier) {
                    when (val screen = state.screen) {
                        CarScreen.Dashboard -> Dashboard(state, actions, navMap, Modifier.fillMaxSize().padding(12.dp))
                        CarScreen.Launcher -> Home(state, actions)
                        is CarScreen.App -> state.app(screen.appId)?.let { appContent(it, Modifier.fillMaxSize()) } ?: Home(state, actions)
                    }
                }
            }
            if (maxHeight > maxWidth) {
                // Portrait screens: the dock along the bottom, as in CarPlay on tall screens.
                Column(Modifier.fillMaxSize()) {
                    content(Modifier.weight(1f).fillMaxWidth())
                    Dock(state, actions, horizontal = true)
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    if (!state.driverOnRight) Dock(state, actions)
                    content(Modifier.weight(1f).fillMaxHeight())
                    if (state.driverOnRight) Dock(state, actions)
                }
            }
            overlay(Modifier.align(Alignment.BottomCenter))
            NoticeOverlay(state, actions, Modifier.align(Alignment.TopCenter))
            CallOverlay(state, actions, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun Dock(state: CarUiState, actions: CarActions, horizontal: Boolean = false) = CarTheme(night = true) {
    val nav = state.app(state.activeNavAppId) ?: state.apps.firstOrNull { it.kind == CarAppKind.NAVIGATION }
    val media = state.app(state.activeMediaAppId) ?: state.apps.firstOrNull { it.kind == CarAppKind.MEDIA }
    val phone = state.apps.firstOrNull { it.kind == CarAppKind.PHONE }
    val voice: @Composable () -> Unit = {
        val scheme = MaterialTheme.colorScheme
        Box(Modifier.size(52.dp).clip(CircleShape).background(if (state.listening) scheme.primary else scheme.secondaryContainer)
            .clickable { actions.assistant() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Mic, "Voice", tint = if (state.listening) scheme.onPrimary else scheme.onSecondaryContainer)
        }
    }
    // The home button: the app grid, or the dashboard when already on it.
    val homeButton: @Composable () -> Unit = {
        val home = state.screen == CarScreen.Launcher
        Box(Modifier.size(52.dp).clip(LocalCarTheme.current.appIcon).background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable { if (home) actions.goHome() else actions.openLauncher() }, contentAlignment = Alignment.Center) {
            Icon(if (home) Icons.Rounded.SpaceDashboard else Icons.Rounded.Apps, if (home) "Dashboard" else "Home",
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    if (horizontal) {
        Row(
            Modifier.fillMaxWidth().height(96.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Clock(); PhoneStatusIcons() }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOfNotNull(nav, media, phone).forEach { app -> DockIcon(app, 60) { actions.openApp(app) } }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { voice(); homeButton() }
        }
        return@CarTheme
    }
    Column(
        Modifier.fillMaxHeight().width(96.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Clock()
        PhoneStatusIcons()
        WeatherChip(state.weather.takeIf { state.weatherInBar })
        // Only the apps are weighted: on a short screen they scroll, and voice and home keep their size.
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                listOfNotNull(nav, media, phone).forEach { app -> DockIcon(app, 60) { actions.openApp(app) } }
            }
        }
        voice()
        Spacer(Modifier.height(12.dp))
        homeButton()
    }
}

/** The home screen: app icons in pages of two rows (more on portrait screens), with page dots. */
@Composable
private fun Home(state: CarUiState, actions: CarActions) = CarTheme(night = true) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(scheme.primaryContainer, scheme.surfaceContainerLowest, scheme.background))),
    ) {
        val columns = maxOf(3, (maxWidth / 150.dp).toInt())
        // Two rows in landscape; a portrait screen has room for more.
        val rows = if (maxHeight > maxWidth) (maxHeight / 170.dp).toInt().coerceIn(2, 5) else 2
        val perPage = columns * rows
        val apps = state.apps
        val pages = maxOf(1, (apps.size + perPage - 1) / perPage)
        val pager = rememberPagerState { pages }
        Column(Modifier.fillMaxSize().padding(vertical = 24.dp)) {
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                    apps.drop(page * perPage).take(perPage).chunked(columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { app ->
                                Column(Modifier.width(130.dp).clickable { actions.openApp(app) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    DockIcon(app, 88, null)
                                    Spacer(Modifier.height(8.dp))
                                    Text(app.label, color = scheme.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.width(130.dp)) }
                        }
                    }
                }
            }
            if (pages > 1) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                    repeat(pages) { i ->
                        Box(Modifier.padding(4.dp).size(8.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) scheme.onSurface else scheme.onSurface.copy(alpha = 0.35f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun DockIcon(app: CarAppEntry, sizeDp: Int, onClick: (() -> Unit)?) {
    val shape = LocalCarTheme.current.appIcon
    val m = Modifier.size(sizeDp.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer)
    Box(if (onClick != null) m.clickable(onClick = onClick) else m, contentAlignment = Alignment.Center) {
        val icon = app.icon
        if (icon != null) Image(icon.asImageBitmap(), app.label, Modifier.size((sizeDp * 0.82f).dp).clip(shape), colorFilter = appIconFilter())
        else Text(app.label.take(1), color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = (sizeDp / 2.5f).sp, fontWeight = FontWeight.SemiBold)
    }
}
