package net.prototux.aare.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.home.HomeConfig
import net.prototux.aare.ui.theme.LocalCarDimens

/*
 * Smart-home buttons and values on the car screen. Every button is one tap (two with its
 * confirmation), so they stay usable while driving; nothing here asks for text.
 */

/**
 * The dashboard's Home card: a few values and the first [HomeConfig.CARD_ACTIONS] buttons marked
 * for the card, as many as its slot has room for. [compact]: a thin strip, buttons only.
 */
@Composable
internal fun HomeCard(home: HomeUi, actions: CarActions, compact: Boolean, onOpen: () -> Unit) {
    val confirming = remember { mutableStateOf<HomeButton?>(null) }
    val buttons = home.buttons.filter { it.onCard }.take(HomeConfig.CARD_ACTIONS)
    val target = LocalCarDimens.current.touchTarget
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 360.dp
        val perRow = if (wide) 2 else 1
        val showTiles = home.tiles.isNotEmpty() && !compact && maxHeight >= 220.dp
        val showHeader = !compact && maxHeight >= 160.dp
        // What's left once the header and values are drawn, in rows of buttons.
        val used = (if (showHeader) 44.dp else 0.dp) + (if (showTiles) 84.dp else 0.dp) + 40.dp
        val rows = if (compact) 1 else ((maxHeight - used) / (target + 12.dp)).toInt().coerceIn(if (buttons.isEmpty()) 0 else 1, 2)
        val shown = buttons.take(if (compact) (if (maxWidth >= 480.dp) 4 else 2) else rows * perRow)
        Column(Modifier.fillMaxSize().padding(if (compact) 12.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showHeader) {
                Row(Modifier.clip(MaterialTheme.shapes.medium).carClickable(MaterialTheme.shapes.medium, onClick = onOpen).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Home, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.home_app_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (showTiles || (shown.isEmpty() && home.tiles.isNotEmpty())) {
                val n = if (shown.isEmpty()) (if (wide) 4 else 2) else (if (wide) 3 else 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    home.tiles.take(minOf(n, HomeConfig.CARD_TILES)).forEach { HomeTileView(it, Modifier.weight(1f)) }
                }
            }
            if (shown.isNotEmpty()) Spacer(Modifier.weight(1f))
            val chunk = if (compact) shown.size.coerceAtLeast(1) else perRow
            shown.chunked(chunk).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { b -> HomeActionButton(b, Modifier.weight(1f)) { tap(b, confirming, actions) } }
                    // An odd last row keeps the same button width.
                    repeat(chunk - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        ConfirmOverlay(confirming, actions)
    }
}

/** The Home app: every value, then every button. */
@Composable
internal fun HomeScreen(home: HomeUi, actions: CarActions, modifier: Modifier) {
    val confirming = remember { mutableStateOf<HomeButton?>(null) }
    Box(modifier.fillMaxSize()) {
        if (home.isEmpty) {
            Text(stringResource(R.string.home_car_empty), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val perRow = when {
                maxWidth >= 900.dp -> 4
                maxWidth >= 600.dp -> 3
                else -> 2
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(home.tiles.chunked(perRow)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { HomeTileView(it, Modifier.weight(1f)) }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (home.tiles.isNotEmpty() && home.buttons.isNotEmpty()) item { Spacer(Modifier.height(8.dp)) }
                items(home.buttons.chunked(perRow)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { b -> HomeActionButton(b, Modifier.weight(1f)) { tap(b, confirming, actions) } }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        ConfirmOverlay(confirming, actions)
    }
}

private fun tap(b: HomeButton, confirming: androidx.compose.runtime.MutableState<HomeButton?>, actions: CarActions) {
    if (b.running) return
    if (b.confirm) confirming.value = b else actions.runHomeAction(b.id)
}

@Composable
private fun HomeActionButton(b: HomeButton, modifier: Modifier, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Box(
        modifier.heightIn(min = LocalCarDimens.current.touchTarget).clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .carClickable(shape, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (b.running) CircularProgressIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, strokeWidth = 3.dp)
        else Text(b.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HomeTileView(t: HomeTileUi, modifier: Modifier) {
    Column(modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp)) {
        val value = t.value?.let { if (t.unit.isBlank()) it else "$it ${t.unit}" } ?: "—"
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (t.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(t.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Open the garage?" over the card or the screen, for actions marked to confirm. */
@Composable
private fun ConfirmOverlay(confirming: androidx.compose.runtime.MutableState<HomeButton?>, actions: CarActions) {
    val b by confirming
    val button = b ?: return
    var done by remember(button.id) { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(enabled = false) {}.padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_confirm_question, button.label), style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val target = LocalCarDimens.current.touchTarget
                val shape = MaterialTheme.shapes.large
                Box(
                    Modifier.heightIn(min = target).width(target * 2).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
                        .carClickable(shape) { confirming.value = null },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.common_cancel), style = MaterialTheme.typography.titleMedium) }
                Box(
                    Modifier.heightIn(min = target).width(target * 2).clip(shape).background(MaterialTheme.colorScheme.primary)
                        .carClickable(shape) {
                            // One send per confirmation, however fast the second tap.
                            if (!done) { done = true; actions.runHomeAction(button.id) }
                            confirming.value = null
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.home_confirm_run), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary) }
            }
        }
    }
}
