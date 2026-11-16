package net.prototux.aare.aap

import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.aap.sim.HeadUnitSimulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A car that stays silent on the focus request must still get video; one that answers decides. */
class VideoFocusFallbackTest {
    private lateinit var hu: HeadUnitSimulator
    private lateinit var phone: AapSession
    private val configChosen = CountDownLatch(1)
    private val videoStarted = CountDownLatch(1)
    private val focusGained = CountDownLatch(1)
    private val streaming = AtomicBoolean(true)
    private var video: VideoChannel? = null

    private fun connect(answerVideoFocus: Boolean) {
        val server = ServerSocket(0)
        val huSocket = Socket("127.0.0.1", server.localPort)
        val phoneSocket = server.accept()
        server.close()
        hu = HeadUnitSimulator(SocketTransport(huSocket), maxUnacked = 2, answerVideoFocus = answerVideoFocus)
        val id = SelfSignedCertificate.generate("phone")
        phone = AapSession(
            SocketTransport(phoneSocket),
            PhoneIdentity(id.certificate, id.keyPair.private),
            PhoneDescription(deviceName = "Test phone", label = "AARE", instanceId = "test-1"),
            callbacks = Callbacks(),
        )
        phone.start()
        hu.start()
        assertTrue("the car should send its video config", configChosen.await(10, TimeUnit.SECONDS))
        Thread.sleep(200) // the channel finishes handling the config after choosing
    }

    @After
    fun tearDown() {
        streaming.set(false)
        hu.stop()
    }

    private inner class Callbacks : AapSession.Callbacks {
        override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> =
            response.channelsList.mapNotNull { s ->
                if (s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0) VideoChannel(s, Listener()).also { video = it } else null
            }

        override fun onRunning(session: AapSession) {}
        override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) {}
        override fun onClosed(session: AapSession, reason: String, error: Throwable?) {}
    }

    /** Stands in for the encoder, which runs whether or not the frames can be sent. */
    private inner class Listener : VideoChannel.Listener {
        override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int {
            configChosen.countDown()
            return 0
        }
        override fun onVideoStart(index: Int, config: VideoConfiguration) {
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
    fun silentCarGetsVideoOnceFocusIsAssumed() {
        connect(answerVideoFocus = false)
        Thread.sleep(500)
        assertEquals("nothing is sent before the fallback", 0, hu.videoFrames.get())
        assertEquals("the stream waits for focus", null, hu.videoStart)

        assertTrue(video!!.assumeProjectedIfUnanswered())

        assertTrue(focusGained.await(3, TimeUnit.SECONDS))
        assertTrue(videoStarted.await(3, TimeUnit.SECONDS))
        assertTrue("frames should reach the head unit", hu.firstVideoFrame.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun carRefusingFocusIsNotOverruled() {
        connect(answerVideoFocus = false)
        hu.requestFocusNative()
        Thread.sleep(500)

        assertFalse(video!!.assumeProjectedIfUnanswered())

        Thread.sleep(300)
        assertFalse(video!!.focused)
        assertEquals(0, hu.videoFrames.get())
        assertEquals("no stream without focus", null, hu.videoStart)
    }

    @Test
    fun theStreamStartsOnceWhenFocusComesTwice() {
        connect(answerVideoFocus = true)
        assertTrue(videoStarted.await(5, TimeUnit.SECONDS))
        hu.requestFocusProjected()

        assertTrue("frames should reach the head unit", hu.firstVideoFrame.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(1, hu.videoStart!!.sessionId)
    }

    /** The VW Play's reverse camera: frames in flight are dropped unacked, then the car hands the screen back. */
    @Test
    fun videoComesBackAfterTheCarDroppedFramesUnacked() {
        connect(answerVideoFocus = true)
        assertTrue(hu.firstVideoFrame.await(5, TimeUnit.SECONDS))

        hu.dropVideoUnacked = true
        Thread.sleep(300) // the window fills with frames the car will never ack
        hu.requestFocusNative()
        Thread.sleep(300)
        hu.dropVideoUnacked = false
        val before = hu.videoFrames.get()
        hu.requestFocusProjected()

        Thread.sleep(1_000)
        assertTrue("frames should flow again after the camera", hu.videoFrames.get() > before + 10)
    }

    /** As Android Auto: the stream stops while the car shows its own screen and comes back as a new session. */
    @Test
    fun focusLossStopsTheStreamAndFocusGainStartsANewSession() {
        connect(answerVideoFocus = true)
        assertTrue(hu.firstVideoFrame.await(5, TimeUnit.SECONDS))
        val configsBefore = hu.codecConfigs.get()

        hu.requestFocusNative()
        Thread.sleep(300)
        assertEquals("the stream stops with the car's own screen", 1, hu.videoStops.get())
        val stopped = hu.videoFrames.get()
        Thread.sleep(200)
        assertEquals("nothing is sent while stopped", stopped, hu.videoFrames.get())

        hu.requestFocusProjected()
        Thread.sleep(1_000)
        assertEquals("a new session", 2, hu.videoStart!!.sessionId)
        assertEquals(1, video!!.restarts)
        assertTrue("the decoder setup is sent again", hu.codecConfigs.get() > configsBefore)
        assertTrue("frames flow in the new session", hu.videoFrames.get() > stopped + 10)
    }

    /** Same, but the car never says it took the screen: the stall check recovers. */
    @Test
    fun videoRecoversFromASilentStall() {
        connect(answerVideoFocus = true)
        assertTrue(hu.firstVideoFrame.await(5, TimeUnit.SECONDS))

        hu.dropVideoUnacked = true
        Thread.sleep(300)
        hu.dropVideoUnacked = false
        val before = hu.videoFrames.get()

        Thread.sleep(3_500)
        assertTrue("frames should flow again once the stall is noticed", hu.videoFrames.get() > before + 10)
        assertTrue(video!!.stalls >= 1)
    }

    @Test
    fun carAnsweringFocusNeedsNoFallback() {
        connect(answerVideoFocus = true)
        assertTrue(focusGained.await(5, TimeUnit.SECONDS))

        assertFalse(video!!.assumeProjectedIfUnanswered())
    }
}
