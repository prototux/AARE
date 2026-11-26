package net.prototux.aare.media

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import net.prototux.aare.audio.Mix
import net.prototux.aare.audio.PcmTap
import net.prototux.aare.audio.Spectrum
import net.prototux.aare.audio.SpectrumAnalyzer
import net.prototux.aare.audio.SpectrumBallistics

/**
 * Something the now-playing screen draws from the sound going to the car. In-tree only: the
 * registry is a list, no loading. [SpectrumVisual] is the one there is.
 */
interface NowPlayingVisual {
    /** Stored in [Settings.nowPlayingVisual]. */
    val id: String
    /**
     * Draws [frames]. [stereo]: left channel on the left half and right on the right, low
     * frequencies at the outside edges; otherwise one (mono) spectrum, low on the left. Read
     * [SpectrumFrames.tick] in the draw phase, so a new frame redraws without recomposing.
     */
    @Composable
    fun Content(frames: SpectrumFrames, stereo: Boolean, style: VisualStyle, modifier: Modifier)
}

object NowPlayingVisuals {
    val all: List<NowPlayingVisual> = listOf(SpectrumVisual)
    fun byId(id: String): NowPlayingVisual? = all.firstOrNull { it.id == id }
    /** The one full screen and the dashboard card draw: now playing's, or the first when that's off. */
    fun style(settings: Settings): NowPlayingVisual = byId(settings.nowPlayingVisual) ?: all.first()
}

/**
 * How the user set the visual up. [stops]: colors by height (0 the bottom, 1 the top), or null for
 * [accent] bars with [dim] peaks. [alpha]: the whole visual's opacity.
 */
data class VisualStyle(
    val stops: List<Pair<Float, Color>>?,
    val accent: Color,
    val dim: Color,
    val rounded: Boolean,
    val peaks: Boolean,
    val alpha: Float,
)

@Composable
internal fun visualStyle(settings: Settings): VisualStyle {
    val accent = MaterialTheme.colorScheme.primary
    val stops = if (settings.visualizerColors == Settings.VISUAL_COLORS_SYSTEM) null else SPECTRAL
    return VisualStyle(
        stops, accent, accent.copy(alpha = 0.6f),
        rounded = settings.visualizerBars == Settings.VISUAL_BARS_ROUNDED,
        peaks = settings.visualizerPeaks,
        alpha = settings.visualizerOpacityPct / 100f,
    )
}

/** How fast the bars fall, in dB a second (the floor is 60 dB down). */
internal fun fallDbPerSec(settings: Settings): Float = when (settings.visualizerFall) {
    Settings.VISUAL_FALL_SLOW -> 20f
    Settings.VISUAL_FALL_FAST -> 90f
    else -> 45f
}

// Pure colors held over most of the height: blends between far-apart colors look washed out,
// worse over the car's compressed video.
private val SPECTRAL = listOf(
    0f to Color(0xFF00FF00), 0.5f to Color(0xFF00FF00), // green
    0.7f to Color(0xFFFFFF00), // yellow
    0.85f to Color(0xFFFF8C00), // orange
    1f to Color(0xFFFF0000), // red
)

/**
 * The sound as the visuals see it, refreshed with the screen (up to 60 times a second) by [rememberSpectrumFrames].
 * Levels in dBFS, from [Spectrum.FLOOR_DB] to 0, low band first. Channel 0 is mono (or left in
 * stereo), channel 1 right. Written on the main thread, read in draw.
 */
@Stable
class SpectrumFrames(val bandCount: Int, val channels: Int) {
    val bars = Array(channels) { FloatArray(bandCount) { Spectrum.FLOOR_DB } }
    val caps = Array(channels) { FloatArray(bandCount) { Spectrum.FLOOR_DB } }
    /** The newest [WAVE_SIZE] samples shown (channel 0), -1..1. */
    val wave = FloatArray(WAVE_SIZE)
    /** Bumped on every new frame: read it in the draw phase. */
    var tick by mutableIntStateOf(0)
        internal set
    /** Sound came through lately: show the visual (and its full-screen button). */
    var hasSound by mutableStateOf(false)
        internal set

    companion object {
        const val WAVE_SIZE = 256
    }
}

/** Bands for a spectrum [width] wide (per channel). */
internal fun bandsFor(width: Dp): Int = (width / 18.dp).toInt().coerceIn(16, 32)

/**
 * Runs the analysis while [running] (the visual is on screen and the music plays): reads the
 * sound the car is playing now, [delayMs] late, and fills the frames. When [running] goes false,
 * the bars fall to the floor and then nothing runs: no frames, no redraws.
 */
@Composable
internal fun rememberSpectrumFrames(bandCount: Int, stereo: Boolean, running: Boolean, delayMs: () -> Int, verboseLog: Boolean, fallDbPerSec: Float = 45f): SpectrumFrames {
    val channels = if (stereo) 2 else 1
    val frames = remember(bandCount, channels) { SpectrumFrames(bandCount, channels) }
    val delayNow by rememberUpdatedState(delayMs)
    // In step with the screen's refresh (a timer drifts against it, and stretches when the phone is
    // locked), at most 60 times a second: the car's video is 30 or 60 fps.
    val view = LocalView.current
    val frameNs = remember(view) { (1_000_000_000f / minOf(view.display?.refreshRate ?: 60f, 60f).coerceAtLeast(1f)).toLong() }
    LaunchedEffect(frames, running, fallDbPerSec) {
        val balls = Array(channels) { SpectrumBallistics(bandCount, decayDbPerSec = fallDbPerSec) }
        // Pick up where the frames are, so a pause doesn't snap the bars down.
        for (c in 0 until channels) { frames.bars[c].copyInto(balls[c].bars); frames.caps[c].copyInto(balls[c].caps) }
        if (running) PcmTap.listen()
        try {
            var analyzer: SpectrumAnalyzer? = null
            val pcm = FloatArray(Spectrum.FFT_SIZE)
            val levels = FloatArray(bandCount)
            var last = SystemClock.elapsedRealtime()
            var soundAt = 0L
            var loggedAt = 0L
            var frameAt = 0L
            while (true) {
                val t = withFrameNanos { it }
                if (frameAt != 0L && t - frameAt < frameNs - FRAME_SLACK_NS) continue
                frameAt = t
                val now = SystemClock.elapsedRealtime()
                val ring = if (running) PcmTap.ring else null
                var any = false
                for (c in 0 until channels) {
                    val mix = when { !stereo -> Mix.MONO; c == 0 -> Mix.LEFT; else -> Mix.RIGHT }
                    if (ring != null && ring.read(now, delayNow(), mix, pcm)) {
                        val a = analyzer?.takeIf { it.sampleRate == ring.sampleRate } ?: SpectrumAnalyzer(ring.sampleRate, bandCount).also { analyzer = it }
                        a.analyze(pcm, levels)
                        if (c == 0) pcm.copyInto(frames.wave, 0, pcm.size - SpectrumFrames.WAVE_SIZE)
                        any = true
                    } else {
                        levels.fill(Spectrum.FLOOR_DB)
                    }
                    balls[c].update(levels, now - last)
                    balls[c].bars.copyInto(frames.bars[c])
                    balls[c].caps.copyInto(frames.caps[c])
                }
                last = now
                if (any) soundAt = now
                // Paused: keep showing while the bars fall, then hide.
                if (running) frames.hasSound = soundAt != 0L && now - soundAt < NO_SOUND_HIDE_MS
                frames.tick++
                if (verboseLog && ring != null && now - loggedAt > 30_000) {
                    loggedAt = now
                    DiagLog.add("visualizer: the car has ${ring.leadMs(now)} ms sent but not played (by the model), delay ${delayNow()} ms")
                }
                if (!running && balls.all { b -> b.bars.all { it <= Spectrum.FLOOR_DB } && b.caps.all { it <= Spectrum.FLOOR_DB } }) {
                    frames.hasSound = false
                    break
                }
            }
        } finally {
            if (running) PcmTap.release()
        }
    }
    return frames
}

/** A vsync this early still counts as the next frame (they jitter). */
private const val FRAME_SLACK_NS = 3_000_000L
/** No sound for this long (a paused app, one that refuses capture): hide the visual. */
private const val NO_SOUND_HIDE_MS = 1_500L
