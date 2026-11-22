package net.prototux.aare.ui.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.host.templates.CarFormat
import net.prototux.aare.ui.theme.LocalCarColors
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * The classic layout, like Android Auto's from 2019 to 2022: the clock and the phone's status along
 * the top, apps full screen, the app grid as the home screen, and a bar along the bottom with the
 * home button, a pill for what goes on elsewhere (the next turn, or the music and its controls),
 * the notifications and the voice button.
 */
@Composable
internal fun ClassicShell(
    state: CarUiState,
    actions: CarActions,
    appContent: @Composable (CarAppEntry, Modifier) -> Unit,
    navMap: @Composable (CarAppEntry, Modifier) -> Unit,
    overlay: @Composable (Modifier) -> Unit,
    /** Insets the UI into the head unit's visible area (the codec frame minus its margins). */
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(modifier)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.chrome.clock) Clock()
                    Spacer(Modifier.weight(1f))
                    PhoneStatusIcons(state.chrome)
                }
                ContentArea(state, actions, appContent, navMap, Modifier.weight(1f).fillMaxWidth(), launcherHome = true)
                BottomBar(state, actions)
            }
            overlay(Modifier.align(Alignment.BottomCenter))
            NoticeOverlay(state, actions, Modifier.align(Alignment.TopCenter))
            CallOverlay(state, actions, Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun BottomBar(state: CarUiState, actions: CarActions) {
    val d = LocalCarDimens.current
    Row(
        Modifier.fillMaxWidth().height(d.touchTarget + 8.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Home: the app grid; on the app grid, back to the app used last.
        val last = state.recentAppIds.firstNotNullOfOrNull(state::app)
        RoundIcon(Icons.Rounded.RadioButtonChecked, stringResource(R.string.dash_menu_home), filled = false) {
            if (state.screen is CarScreen.App || last == null) actions.goHome() else actions.openApp(last)
        }
        Box(Modifier.weight(1f)) { ActivityPill(state, actions) }
        NotificationBell(state) { state.apps.firstOrNull { it.kind == CarAppKind.NOTIFICATIONS }?.let(actions::openApp) }
        MicButton(state.listening) { actions.assistant() }
    }
}

/**
 * What goes on away from the screen: the next turn while not on the navigation app, else the music
 * and its controls while not on the music app, else nothing.
 */
@Composable
private fun ActivityPill(state: CarUiState, actions: CarActions) {
    val screen = (state.screen as? CarScreen.App)?.appId
    val nav = state.app(state.activeNavAppId)
    val np = state.nowPlaying
    val media = state.app(np?.appId ?: state.activeMediaAppId)
    val guiding = state.trip != null || state.phoneGuidance != null
    val pill = Modifier.height(LocalCarDimens.current.touchTarget).clip(RoundedCornerShape(percent = 50))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    when {
        guiding && (nav == null || screen != nav.id) -> Row(
            pill.clickable { nav?.let(actions::openApp) }.padding(start = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TurnBadge(state)
            Spacer(Modifier.width(12.dp))
            Text(turnText(state), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        media != null && screen != media.id -> Row(pill.padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).clickable { actions.openApp(media) }) { AppIcon(media, 52) }
            RoundIcon(Icons.Rounded.SkipPrevious, stringResource(R.string.media_action_previous), filled = false) { actions.mediaPrevious() }
            RoundIcon(if (np?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(R.string.media_action_play_pause), filled = false) { actions.mediaPlayPause() }
            RoundIcon(Icons.Rounded.SkipNext, stringResource(R.string.media_action_next), filled = false) { actions.mediaNext() }
        }
    }
}

/** The turn arrow in Android Auto's green circle: the phone app's own arrow, or a plain one. */
@Composable
private fun TurnBadge(state: CarUiState) {
    val colors = LocalCarColors.current
    Box(Modifier.size(52.dp).clip(CircleShape).background(colors.accept), contentAlignment = Alignment.Center) {
        val arrow = state.phoneGuidance?.arrow
        if (state.trip == null && arrow != null) {
            Image(arrow.asImageBitmap(), null, Modifier.size(32.dp), colorFilter = ColorFilter.tint(colors.onCall, BlendMode.SrcIn))
        } else {
            Icon(Icons.Rounded.Navigation, null, tint = colors.onCall, modifier = Modifier.size(32.dp))
        }
    }
}

/** "1000 ft • Charleston Rd.": the distance to the next turn and its road. */
private fun turnText(state: CarUiState): String {
    val trip = state.trip
    if (trip != null) {
        val distance = trip.stepTravelEstimates.firstOrNull()?.remainingDistance?.let { CarFormat.distance(it.displayDistance, it.displayUnit) }
        val step = trip.steps.firstOrNull()
        val road = (step?.road ?: step?.cue ?: trip.currentRoad)?.toString()
        return listOfNotNull(distance, road?.takeIf { it.isNotBlank() }).joinToString(" • ")
    }
    val g = state.phoneGuidance ?: return ""
    return listOf(g.primary, g.secondary).filter { it.isNotBlank() }.joinToString(" • ")
}
