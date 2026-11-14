package net.prototux.aare.car

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.prototux.aare.media.MediaApp
import net.prototux.aare.media.MediaHub
import net.prototux.aare.media.PlaybackInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionEndMediaTest {
    private class FakeMediaHub(playback: PlaybackInfo?) : MediaHub {
        var pauses = 0
        override val apps = MutableStateFlow<List<MediaApp>>(emptyList())
        override val playback: StateFlow<PlaybackInfo?> = MutableStateFlow(playback)
        override fun select(app: MediaApp) {}
        override fun playPause() {}
        override fun next() {}
        override fun previous() {}
        override fun pause() { pauses++ }
        @Composable
        override fun Screen(app: MediaApp, driving: Boolean, modifier: Modifier, onShowVideo: (() -> Unit)?) {}
        override fun release() {}
    }

    private fun info(playing: Boolean) = PlaybackInfo(
        packageName = "com.example.player", title = "Title", artist = "Artist", album = "Album",
        art = null, playing = playing, positionMs = 0, durationMs = 1000, sampledAtMs = 0,
    )

    @Test
    fun pausesWhenPlayingThroughTheCar() {
        val media = FakeMediaHub(info(playing = true))
        pauseIfFeedingCar(media, feedingCar = true)
        assertEquals(1, media.pauses)
    }

    @Test
    fun doesNothingWhenNotPlaying() {
        val media = FakeMediaHub(info(playing = false))
        pauseIfFeedingCar(media, feedingCar = true)
        assertEquals(0, media.pauses)
    }

    @Test
    fun doesNothingWithoutPlayback() {
        val media = FakeMediaHub(null)
        pauseIfFeedingCar(media, feedingCar = true)
        assertEquals(0, media.pauses)
    }

    @Test
    fun doesNothingWhenAudioWasNotOnTheCar() {
        val media = FakeMediaHub(info(playing = true))
        pauseIfFeedingCar(media, feedingCar = false)
        assertEquals(0, media.pauses)
    }
}
