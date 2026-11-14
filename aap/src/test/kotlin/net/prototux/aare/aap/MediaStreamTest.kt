package net.prototux.aare.aap

import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.control.message.UpdateUiConfigRequestOuterClass.UpdateUiConfigRequest
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets
import aap_protobuf.service.media.shared.message.MediaStatsOuterClass.MediaStats
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.shared.message.UiThemeOuterClass.UiTheme
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.MediaSinkChannel
import net.prototux.aare.aap.channels.StartMediaOptions
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.aap.sim.HeadUnitSimulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The media streams against head units of different protocol versions and behaviours: what the
 * phone sends follows the same pattern for the same head unit.
 */
class MediaStreamTest {
    private val encoding = AtomicBoolean(true)
    private val links = CopyOnWriteArrayList<HeadUnitSimulator>()

    @After
    fun tearDown() {
        encoding.set(false)
        links.forEach { it.stop() }
    }

    /** A phone session with only the media channels, against a simulated head unit. */
    private inner class Link(
        major: Int = 1,
        minor: Int = 6,
        videoConfigStatus: Config.Status = Config.Status.STATUS_READY,
        uiConfig: UiConfig? = null,
    ) {
        val hu: HeadUnitSimulator
        @Volatile var video: VideoChannel? = null
        val audio = CopyOnWriteArrayList<AudioChannel>()
        val running = CountDownLatch(1)
        val videoStarted = CountDownLatch(1)
        val focused = CountDownLatch(1)
        /** UI configs handed to the phone's layout, in order. */
        val laidOut = LinkedBlockingQueue<UiConfig>()
        /** Video frames the head unit had when each layout was done. */
        val framesWhenLaidOut = LinkedBlockingQueue<Int>()

        init {
            val server = ServerSocket(0)
            val huSocket = Socket("127.0.0.1", server.localPort)
            val phoneSocket = server.accept()
            server.close()
            hu = HeadUnitSimulator(SocketTransport(huSocket), maxUnacked = 2, protocolMinor = minor, protocolMajor = major,
                videoConfigStatus = videoConfigStatus, videoUiConfig = uiConfig)
            links += hu
            val id = SelfSignedCertificate.generate("phone")
            AapSession(
                SocketTransport(phoneSocket),
                PhoneIdentity(id.certificate, id.keyPair.private),
                PhoneDescription(deviceName = "Test phone", label = "AARE", instanceId = "test-1"),
                callbacks = object : AapSession.Callbacks {
                    override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> =
                        response.channelsList.mapNotNull { s ->
                            when {
                                s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0 -> VideoChannel(s, Listener()).also { video = it }
                                s.hasMediaSinkService() -> AudioChannel(s).also { audio += it }
                                else -> null
                            }
                        }
                    override fun onRunning(session: AapSession) = running.countDown()
                    override fun onClosed(session: AapSession, reason: String, error: Throwable?) {}
                },
            ).start()
            hu.start()
            assertTrue("session should reach RUNNING", running.await(10, TimeUnit.SECONDS))
        }

        fun audio(type: String) = audio.first { it.streamType.name.endsWith(type) }

        /** Starts playback on an audio stream once the head unit has said it is ready. */
        fun startAudio(type: String): AudioChannel {
            val channel = audio(type)
            val until = System.currentTimeMillis() + 5000
            while (!channel.ready && System.currentTimeMillis() < until) Thread.sleep(20)
            assertTrue("$type should become ready", channel.ready)
            channel.startPlayback()
            return channel
        }

        /** The latest START the head unit got on [channel]. */
        fun start(channel: Int): Start {
            val until = System.currentTimeMillis() + 3000
            while (hu.starts[channel].isNullOrEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
            return hu.starts[channel]!!.last()
        }

        /** Stands in for the encoder: a key frame, then small P frames while projected. */
        private inner class Listener : VideoChannel.Listener {
            override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>) = 0
            override fun onVideoStart(index: Int, config: VideoConfiguration) {
                videoStarted.countDown()
                video!!.sendCodecConfig(byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 3, 0, 0, 0, 1, 0x68, 4), 0, 14)
            }
            override fun onVideoFocus(projected: Boolean) {
                if (!projected || focused.count == 0L) return
                focused.countDown()
                Thread {
                    var n = 0
                    while (encoding.get()) {
                        val key = n % 30 == 0
                        val size = if (key) 4_000 else 300
                        video!!.sendFrame(ByteArray(10 + size), 10, size, n * 33_333L, key) {}
                        n++
                        Thread.sleep(10)
                    }
                }.start()
            }
            override fun onKeyFrameNeeded() {}
            override fun onUiConfig(config: UiConfig, applied: () -> Unit) {
                laidOut.add(config)
                // Laid out a little later, on another thread, as on the phone's main thread.
                Thread {
                    Thread.sleep(100)
                    framesWhenLaidOut.add(hu.videoFrames.get())
                    applied()
                }.start()
            }
        }
    }

    private fun insets(top: Int) = Insets.newBuilder().setTop(top).setBottom(0).setLeft(0).setRight(0).build()
    private fun uiRequest(ui: UiConfig) = UpdateUiConfigRequest.newBuilder().setUiConfig(ui).build()

    @Test
    fun videoStartsWhateverTheConfigStatus() {
        // Video starts on any Config; only audio waits for READY.
        val link = Link(videoConfigStatus = Config.Status.STATUS_WAIT)
        assertTrue("video starts on a Config that says WAIT", link.videoStarted.await(5, TimeUnit.SECONDS))
        assertTrue(link.hu.firstVideoFrame.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun mediaStatsAreReadNotFatal() {
        val link = Link(major = 6, minor = 0)
        assertTrue(link.videoStarted.await(5, TimeUnit.SECONDS))
        // A body we can't parse (a varint cut short) is dropped, the session carries on.
        link.hu.sendBytes(link.hu.video, MediaMsg.MEDIA_STATS, byteArrayOf(0x10, 0xff.toByte()))
        link.hu.sendMediaStats(link.hu.video, MediaStats.newBuilder().setFrameCount(42).build())
        val until = System.currentTimeMillis() + 3000
        while (link.video!!.mediaStats == null && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(42L, link.video!!.mediaStats?.frameCount)
        val frames = link.hu.videoFrames.get()
        Thread.sleep(300)
        assertTrue("video keeps flowing", link.hu.videoFrames.get() > frames)
    }

    @Test
    fun olderHeadUnitsGetPlainStarts() {
        val link = Link(major = 1, minor = 6)
        assertTrue(link.videoStarted.await(5, TimeUnit.SECONDS))
        val video = link.start(link.hu.video)
        assertFalse(video.hasAckMode())
        assertFalse(video.hasMediaOptions())
        val media = link.startAudio("MEDIA")
        val audio = link.start(link.hu.mediaAudio)
        assertFalse(audio.hasAckMode())
        assertFalse(audio.hasMediaOptions())
        assertFalse("acks pace a 1.6 head unit", media.ackless)
    }

    @Test
    fun audioDropsOverflowFromProtocolFive() {
        // 5.x: audio in drop overflow mode with media options (no statistics yet); video unchanged.
        val link = Link(major = 5, minor = 0)
        assertTrue(link.videoStarted.await(5, TimeUnit.SECONDS))
        assertFalse(link.start(link.hu.video).hasAckMode())
        val media = link.startAudio("MEDIA")
        val start = link.start(link.hu.mediaAudio)
        assertEquals(MediaSinkChannel.ACK_MODE_DROP_OVERFLOW, start.ackMode)
        assertEquals(StartMediaOptions.audio(AudioStreamType.AUDIO_STREAM_MEDIA, 5), start.mediaOptions)
        assertEquals(300_000_000, start.mediaOptions.audioBufferLimit.nanos)
        assertFalse(start.mediaOptions.hasMediaStatsReportInterval())
        assertTrue("acks don't pace a drop overflow stream", media.ackless)
        assertTrue("the window is at least 24", media.maxUnacked >= 24)

        // The head unit acks nothing: chunks keep going out, none dropped.
        link.hu.ackAudio = false
        repeat(40) { assertTrue(media.sendPcm(ByteArray(10 + 3840), 10, 3840, it * 20_000L)) }
        Thread.sleep(300)
        assertEquals(40, link.hu.audioChunks.get())
    }

    @Test
    fun videoDropsOverflowFromProtocolSix() {
        val link = Link(major = 6, minor = 0)
        link.hu.ackVideo = false
        assertTrue(link.videoStarted.await(5, TimeUnit.SECONDS))
        val start = link.start(link.hu.video)
        assertEquals(MediaSinkChannel.ACK_MODE_DROP_OVERFLOW, start.ackMode)
        assertEquals(StartMediaOptions.video(), start.mediaOptions)
        assertTrue(start.mediaOptions.enableMediaStats)
        assertEquals(5L, start.mediaOptions.mediaStatsReportInterval.seconds)
        assertEquals(9_000_000, start.mediaOptions.videoTimingMinDelay.nanos)
        // Frames flow without a single ack, far past the head unit's window of 2.
        assertTrue(link.focused.await(5, TimeUnit.SECONDS))
        Thread.sleep(800)
        assertTrue("frames sent without acks: ${link.hu.videoFrames.get()}", link.hu.videoFrames.get() > 20)
        assertTrue(link.video!!.ackless)

        // Navigation voice reports statistics more often.
        link.startAudio("GUIDANCE")
        val guidance = link.start(link.hu.guidance).mediaOptions
        assertTrue(guidance.enableMediaStats)
        assertEquals(500_000_000, guidance.mediaStatsReportInterval.nanos)
        assertEquals(400_000_000, guidance.audioBufferLimit.nanos)
    }

    @Test
    fun uiConfigUpdateIsMergedAndConfirmedAfterAFrame() {
        // A 1.6 head unit sends only what changes: the margins it started with stay.
        val link = Link(uiConfig = UiConfig.newBuilder().setMargins(insets(10)).build())
        assertTrue(link.focused.await(5, TimeUnit.SECONDS))
        assertTrue(link.hu.firstVideoFrame.await(5, TimeUnit.SECONDS))
        link.hu.updateUiConfig(uiRequest(UiConfig.newBuilder().setContentInsets(insets(30)).build()))

        val reply = link.hu.uiConfigReplies.poll(3, TimeUnit.SECONDS)!!
        assertEquals("merged into the config in use", 10, reply.margins.top)
        assertEquals(30, reply.contentInsets.top)
        assertEquals(reply, link.laidOut.poll())
        assertEquals(reply, link.video!!.chosen!!.uiConfig)
        val laidOutAt = link.framesWhenLaidOut.poll()!!
        assertTrue("the reply follows a frame sent after the layout", link.hu.framesAtUiConfigReply.poll()!! > laidOutAt)

        // A 1.6 head unit may not set the theme this way: refused, no reply, layout unchanged.
        link.hu.updateUiConfig(uiRequest(UiConfig.newBuilder().setUiTheme(UiTheme.UI_THEME_DARK).build()))
        assertEquals(null, link.hu.uiConfigReplies.poll(500, TimeUnit.MILLISECONDS))
        assertEquals(null, link.laidOut.poll())
    }

    @Test
    fun uiConfigUpdateReplacesFromProtocolFourThree() {
        val link = Link(major = 4, minor = 3, uiConfig = UiConfig.newBuilder().setMargins(insets(10)).build())
        assertTrue(link.focused.await(5, TimeUnit.SECONDS))
        link.hu.updateUiConfig(uiRequest(UiConfig.newBuilder().setContentInsets(insets(30)).build()))
        val reply = link.hu.uiConfigReplies.poll(3, TimeUnit.SECONDS)!!
        assertFalse("a 4.3 head unit sends the whole config", reply.hasMargins())
        assertEquals(30, reply.contentInsets.top)
    }
}
