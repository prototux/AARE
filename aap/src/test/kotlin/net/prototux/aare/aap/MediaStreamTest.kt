package net.prototux.aare.aap

import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaStatsOuterClass.MediaStats
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.aap.sim.HeadUnitSimulator
import org.junit.After
import org.junit.Assert.assertEquals
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
        }
    }

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
}
