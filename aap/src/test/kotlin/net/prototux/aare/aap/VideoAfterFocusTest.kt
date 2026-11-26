package net.prototux.aare.aap

import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.aap.sim.HeadUnitSimulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * vehicles.json `videoAfterFocus` (VideoChannel's startOnFocus): the stream starts once the car
 * grants focus, and anyway if it never answers. Off, the stream starts right after the Config, as
 * it always has.
 */
class VideoAfterFocusTest {
    private lateinit var hu: HeadUnitSimulator
    private val configChosen = CountDownLatch(1)
    private val videoStarted = CountDownLatch(1)
    private val encoderStarts = AtomicInteger()
    private val focusGained = CountDownLatch(1)
    private val streaming = AtomicBoolean(true)
    @Volatile private var video: VideoChannel? = null

    private fun connect(startOnFocus: Boolean, answerVideoFocus: Boolean) {
        val server = ServerSocket(0)
        val huSocket = Socket("127.0.0.1", server.localPort)
        val phoneSocket = server.accept()
        server.close()
        hu = HeadUnitSimulator(SocketTransport(huSocket), maxUnacked = 2, answerVideoFocus = answerVideoFocus)
        val id = SelfSignedCertificate.generate("phone")
        AapSession(
            SocketTransport(phoneSocket),
            PhoneIdentity(id.certificate, id.keyPair.private),
            PhoneDescription(deviceName = "Test phone", label = "AARE", instanceId = "test-1"),
            callbacks = object : AapSession.Callbacks {
                override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> =
                    response.channelsList.mapNotNull { s ->
                        if (s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0)
                            VideoChannel(s, Listener(), startOnFocus = startOnFocus).also { video = it } else null
                    }
                override fun onRunning(session: AapSession) {}
                override fun onClosed(session: AapSession, reason: String, error: Throwable?) {}
            },
        ).start()
        hu.start()
        assertTrue("the car should send its video config", configChosen.await(10, TimeUnit.SECONDS))
        Thread.sleep(300) // the channel finishes handling the config after choosing
    }

    @After
    fun tearDown() {
        streaming.set(false)
        hu.stop()
    }

    /** Stands in for the encoder, which runs whether or not the frames can be sent. */
    private inner class Listener : VideoChannel.Listener {
        override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int {
            configChosen.countDown()
            return 0
        }
        override fun onVideoStart(index: Int, config: VideoConfiguration) {
            encoderStarts.incrementAndGet()
            video!!.sendCodecConfig(byteArrayOf(0, 0, 0, 1, 0x67), 0, 5)
            Thread {
                var n = 0
                while (streaming.get()) {
                    video!!.sendFrame(ByteArray(10 + 1_000), 10, 1_000, n * 33_333L, n % 10 == 0) {}
                    n++
                    Thread.sleep(5)
                }
            }.start()
            videoStarted.countDown()
        }
        override fun onVideoFocus(projected: Boolean) { if (projected) focusGained.countDown() }
        override fun onKeyFrameNeeded() {}
    }

    @Test
    fun byDefaultTheStreamStartsBeforeFocusAsBefore() {
        connect(startOnFocus = false, answerVideoFocus = false)
        assertNotNull("START right after the config", hu.videoStart)
        assertEquals(1, encoderStarts.get())
        // No fallback either: that's only for the cars that need it.
        assertFalse(video!!.assumeProjectedIfUnanswered())
    }

    @Test
    fun theStreamWaitsForFocusAndStartsOnIt() {
        connect(startOnFocus = true, answerVideoFocus = true)
        assertTrue(focusGained.await(5, TimeUnit.SECONDS))
        assertTrue(videoStarted.await(5, TimeUnit.SECONDS))
        assertTrue("frames should reach the head unit", hu.firstVideoFrame.await(5, TimeUnit.SECONDS))
        assertFalse("the car answered: no fallback", video!!.assumeProjectedIfUnanswered())
    }

    @Test
    fun aSilentCarGetsVideoOnceFocusIsAssumed() {
        connect(startOnFocus = true, answerVideoFocus = false)
        Thread.sleep(300)
        assertNull("the stream waits for focus", hu.videoStart)
        assertEquals("nothing is sent before the fallback", 0, hu.videoFrames.get())

        assertTrue(video!!.assumeProjectedIfUnanswered())

        assertTrue(focusGained.await(3, TimeUnit.SECONDS))
        assertTrue(videoStarted.await(3, TimeUnit.SECONDS))
        assertTrue("frames should reach the head unit", hu.firstVideoFrame.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun aCarRefusingFocusIsNotOverruled() {
        connect(startOnFocus = true, answerVideoFocus = false)
        hu.requestFocusNative()
        Thread.sleep(300)

        assertFalse(video!!.assumeProjectedIfUnanswered())

        Thread.sleep(300)
        assertFalse(video!!.focused)
        assertNull("no stream without focus", hu.videoStart)
        assertEquals(0, encoderStarts.get())
    }

    @Test
    fun theStreamStartsOnceWhenFocusComesTwice() {
        connect(startOnFocus = true, answerVideoFocus = true)
        assertTrue(videoStarted.await(5, TimeUnit.SECONDS))
        hu.requestFocusProjected()

        assertTrue(hu.firstVideoFrame.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(1, hu.starts[hu.video]!!.size)
        assertEquals(1, encoderStarts.get())
    }

    /** The car's own screen (reverse camera) and back: a new session, the encoder isn't started again. */
    @Test
    fun focusLossStopsTheStreamAndFocusGainStartsANewSession() {
        connect(startOnFocus = true, answerVideoFocus = true)
        assertTrue(hu.firstVideoFrame.await(5, TimeUnit.SECONDS))
        val configsBefore = hu.codecConfigs.get()

        hu.requestFocusNative()
        Thread.sleep(300)
        assertEquals("the stream stops with the car's own screen", 1, hu.videoStops.get())
        val stopped = hu.videoFrames.get()

        hu.requestFocusProjected()
        Thread.sleep(1_000)
        assertEquals("a new session", 2, hu.starts[hu.video]!!.size)
        assertEquals(1, encoderStarts.get())
        assertTrue("the decoder setup is sent again", hu.codecConfigs.get() > configsBefore)
        assertTrue("frames flow in the new session", hu.videoFrames.get() > stopped + 10)
    }

    /** As the VW Play behind its reverse camera: frames in flight are never acked, then the car hands the screen back. */
    @Test
    fun videoComesBackAfterTheCarDroppedFramesUnacked() {
        connect(startOnFocus = true, answerVideoFocus = true)
        assertTrue(hu.firstVideoFrame.await(5, TimeUnit.SECONDS))

        hu.ackVideo = false
        Thread.sleep(300) // the window fills with frames the car will never ack
        hu.requestFocusNative()
        Thread.sleep(300)
        hu.ackVideo = true
        val before = hu.videoFrames.get()
        hu.requestFocusProjected()

        Thread.sleep(1_000)
        assertTrue("frames should flow again after the camera", hu.videoFrames.get() > before + 10)
    }
}
