package net.prototux.aare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.Settings

/** On the audio page: the visualizer's state, opening its own page. */
@Composable
internal fun VisualsEntry(settings: Settings, open: () -> Unit) {
    Section(stringResource(R.string.visuals_title)) {
        val on = settings.visualizerAnywhere
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = open).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.visualizer_toggle_title), style = MaterialTheme.typography.bodyLarge)
                Text(if (on) stringResource(R.string.visuals_entry_on, stringResource(R.string.visualizer_toggle_title)) else stringResource(R.string.visuals_entry_off),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The visualizer: where it shows (now playing, full screen, the dashboard), how it looks, its delay against the car's sound. */
@Composable
internal fun VisualizerSection(settings: Settings) {
    var enabled by remember { mutableStateOf(settings.nowPlayingVisual != Settings.VISUAL_OFF) }
    var full by remember { mutableStateOf(settings.visualizerFullScreen) }
    var colors by remember { mutableStateOf(settings.visualizerColors) }
    var delayMs by remember { mutableIntStateOf(settings.visualizerDelayMs) }
    var bars by remember { mutableStateOf(settings.visualizerBars) }
    var opacity by remember { mutableIntStateOf(settings.visualizerOpacityPct) }
    var peaks by remember { mutableStateOf(settings.visualizerPeaks) }
    var onCard by remember { mutableStateOf(settings.visualizerOnCard) }
    var fall by remember { mutableStateOf(settings.visualizerFall) }
    var art by remember { mutableStateOf(settings.visualizerArtBackground) }
    Section(stringResource(R.string.visualizer_section)) {
        Toggle(stringResource(R.string.visualizer_toggle_title), stringResource(R.string.visualizer_toggle_subtitle), enabled) {
            enabled = it; settings.nowPlayingVisual = if (it) Settings.VISUAL_SPECTRUM else Settings.VISUAL_OFF
        }
        // Each on its own: off on now playing can still be on full screen or the dashboard.
        Toggle(stringResource(R.string.visualizer_full_screen), stringResource(R.string.visualizer_full_screen_subtitle), full) {
            full = it; settings.visualizerFullScreen = it
        }
        Toggle(stringResource(R.string.visualizer_on_card), stringResource(R.string.visualizer_on_card_subtitle), onCard) {
            onCard = it; settings.visualizerOnCard = it
        }
        if (!enabled && !full && !onCard) return@Section
        Toggle(stringResource(R.string.visualizer_art_background), stringResource(R.string.visualizer_art_background_subtitle), art) {
            art = it; settings.visualizerArtBackground = it
        }
        Choices(stringResource(R.string.visualizer_bars_title), listOf(
            Settings.VISUAL_BARS_ROUNDED to stringResource(R.string.visualizer_bars_rounded),
            Settings.VISUAL_BARS_SQUARE to stringResource(R.string.visualizer_bars_square),
        ), bars) { bars = it; settings.visualizerBars = it }
        Choices(stringResource(R.string.visualizer_colors_title), listOf(
            Settings.VISUAL_COLORS_SPECTRAL to stringResource(R.string.visualizer_colors_spectral),
            Settings.VISUAL_COLORS_SYSTEM to stringResource(R.string.visualizer_colors_system),
        ), colors) { colors = it; settings.visualizerColors = it }
        Toggle(stringResource(R.string.visualizer_peaks), stringResource(R.string.visualizer_peaks_subtitle), peaks) {
            peaks = it; settings.visualizerPeaks = it
        }
        Choices(stringResource(R.string.visualizer_fall_title), listOf(
            Settings.VISUAL_FALL_SLOW to stringResource(R.string.visualizer_fall_slow),
            Settings.VISUAL_FALL_NORMAL to stringResource(R.string.visualizer_fall_normal),
            Settings.VISUAL_FALL_FAST to stringResource(R.string.visualizer_fall_fast),
        ), fall) { fall = it; settings.visualizerFall = it }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.visualizer_opacity_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.visualizer_opacity_value, opacity), style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = opacity.toFloat(),
                onValueChange = { opacity = Math.round(it / 10f) * 10; settings.visualizerOpacityPct = opacity },
                valueRange = Settings.VISUAL_OPACITY_MIN.toFloat()..100f,
                steps = (100 - Settings.VISUAL_OPACITY_MIN) / 10 - 1,
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.visualizer_delay_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.visualizer_delay_value, delayMs), style = MaterialTheme.typography.bodyMedium)
            }
            // Applied as it moves: a passenger can tune it against the car's sound.
            Slider(
                value = delayMs.toFloat(),
                onValueChange = { delayMs = Math.round(it / Settings.VISUAL_DELAY_STEP_MS) * Settings.VISUAL_DELAY_STEP_MS; settings.visualizerDelayMs = delayMs },
                valueRange = Settings.VISUAL_DELAY_MIN_MS.toFloat()..Settings.VISUAL_DELAY_MAX_MS.toFloat(),
                steps = (Settings.VISUAL_DELAY_MAX_MS - Settings.VISUAL_DELAY_MIN_MS) / Settings.VISUAL_DELAY_STEP_MS - 1,
            )
            Text(stringResource(R.string.visualizer_delay_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
