package net.prototux.aare.media

import androidx.media3.common.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaitSettledTest {
    private val ready = MediaConnection.Status.Ready(MediaItem.EMPTY, StyleHints(), searchSupported = false)
    private val failed = MediaConnection.Status.Failed(MediaError("failed"))

    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000

    @Test
    fun readyAfterConnectingIsTrue() = runBlocking {
        val status = MutableStateFlow<MediaConnection.Status>(MediaConnection.Status.Connecting)
        launch { delay(50); status.value = ready }
        val start = System.nanoTime()
        assertTrue(awaitSettled(status, 10_000))
        assertTrue(elapsedMs(start) < 5_000)
    }

    @Test
    fun alreadyReadyIsTrue() = runBlocking {
        val status = MutableStateFlow<MediaConnection.Status>(ready)
        val start = System.nanoTime()
        assertTrue(awaitSettled(status, 10_000))
        assertTrue(elapsedMs(start) < 5_000)
    }

    @Test
    fun failedIsFalse() = runBlocking {
        val status = MutableStateFlow<MediaConnection.Status>(MediaConnection.Status.Connecting)
        launch { delay(50); status.value = failed }
        val start = System.nanoTime()
        assertFalse(awaitSettled(status, 10_000))
        assertTrue(elapsedMs(start) < 5_000)
    }

    @Test(timeout = 5_000)
    fun stillConnectingTimesOut() = runBlocking {
        val status = MutableStateFlow<MediaConnection.Status>(MediaConnection.Status.Connecting)
        val start = System.nanoTime()
        assertFalse(awaitSettled(status, 200))
        assertTrue(elapsedMs(start) >= 180)
    }
}
