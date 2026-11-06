package net.prototux.aare.aap

import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import aap_protobuf.service.navigationstatus.message.NavigationDestinationOuterClass.NavigationDestination
import aap_protobuf.service.navigationstatus.message.NavigationStateOuterClass.NavigationState
import aap_protobuf.service.navigationstatus.message.NavigationStatusOuterClass.NavigationStatus
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.InputChannel
import net.prototux.aare.aap.channels.MediaPlaybackChannel
import net.prototux.aare.aap.channels.MicrophoneChannel
import net.prototux.aare.aap.channels.NavigationStatusChannel
import net.prototux.aare.aap.channels.SensorChannel
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.aap.sim.HeadUnitSimulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

open class SessionLoopbackTest {

    /** Whether the simulated head unit leaves out fields that are 0, like a proto3 encoder. */
    protected open val omitZeroFields = false

    private lateinit var hu: HeadUnitSimulator
    private lateinit var huSocket: Socket
    private lateinit var phone: AapSession
    private val running = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private val videoStarted = CountDownLatch(1)
    private val focusGained = CountDownLatch(1)
    private val inputs = LinkedBlockingQueue<InputReport>()
    private val sensors = LinkedBlockingQueue<SensorBatch>()
    private val micData = CountDownLatch(1)
    private val audioFocus = LinkedBlockingQueue<AudioFocusNotification>()
    private var video: VideoChannel? = null
    private var mic: MicrophoneChannel? = null
    private var nav: NavigationStatusChannel? = null
    private val audio = mutableListOf<AudioChannel>()
    private val encoding = AtomicBoolean(false)
    @Volatile private var closeError: Throwable? = null
    @Volatile private var closeReason: String? = null

    @Before
    fun setUp() {
        val server = ServerSocket(0)
        huSocket = Socket("127.0.0.1", server.localPort)
        val phoneSocket = server.accept()
        server.close()

        hu = HeadUnitSimulator(SocketTransport(huSocket), maxUnacked = 2, omitZeroFields = omitZeroFields)
        val id = SelfSignedCertificate.generate("phone")
        phone = AapSession(
            InterruptibleCloseTransport(SocketTransport(phoneSocket)),
            PhoneIdentity(id.certificate, id.keyPair.private),
            PhoneDescription(deviceName = "Test phone", label = "AARE", instanceId = "test-1"),
            callbacks = Callbacks(),
        )
        phone.start()
        hu.start()
    }

    @After
    fun tearDown() {
        encoding.set(false)
        hu.stop()
    }

    private inner class Callbacks : AapSession.Callbacks {
        override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> =
            response.channelsList.mapNotNull { s ->
                when {
                    s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0 -> VideoChannel(s, VideoListener()).also { video = it }
                    s.hasMediaSinkService() -> AudioChannel(s).also { audio += it }
                    s.hasMediaSourceService() -> MicrophoneChannel(s, object : MicrophoneChannel.Listener {
                        override fun onMicData(data: ByteArray, offset: Int, length: Int, timestampUs: Long) = micData.countDown()
                    }).also { mic = it }
                    s.hasInputSourceService() -> InputChannel(s, object : InputChannel.Listener {
                        override fun onInput(report: InputReport) { inputs.add(report) }
                    })
                    s.hasSensorSourceService() -> SensorChannel(s, object : SensorChannel.Listener {
                        override fun onSensors(batch: SensorBatch) { sensors.add(batch) }
                    })
                    s.hasNavigationStatusService() -> NavigationStatusChannel(s).also { nav = it }
                    s.hasMediaPlaybackService() -> MediaPlaybackChannel(s)
                    else -> null
                }
            }

        override fun onRunning(session: AapSession) = running.countDown()
        override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) { audioFocus.add(notification) }
        override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
            closeError = error
            closeReason = reason
            closed.countDown()
        }
    }

    /**
     * Like the wireless transport, whose close() ends in an interruptible blocking call: it throws
     * if the closing thread has been interrupted.
     */
    private class InterruptibleCloseTransport(private val inner: Transport) : Transport by inner {
        override fun close() {
            LinkedBlockingQueue<Unit>().put(Unit)
            inner.close()
        }
    }

    /** Stands in for MediaCodec: a key frame then small P frames, 10 bytes of headroom each. */
    private inner class VideoListener : VideoChannel.Listener {
        override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>) = 0
        override fun onVideoStart(index: Int, config: VideoConfiguration) {
            videoStarted.countDown()
            video!!.sendCodecConfig(byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 3, 0, 0, 0, 1, 0x68, 4), 0, 14)
        }
        override fun onVideoFocus(projected: Boolean) {
            if (!projected) return
            focusGained.countDown()
            if (!encoding.compareAndSet(false, true)) return
            Thread {
                var n = 0
                while (encoding.get()) {
                    val key = n % 30 == 0
                    val size = if (key) 40_000 else 3_000
                    val buf = ByteArray(10 + size)
                    buf[10 + 4] = if (key) 0x65 else 0x41
                    video!!.sendFrame(buf, 10, size, n * 33_333L, key) {}
                    n++
                    Thread.sleep(5)
                }
            }.start()
        }
        override fun onKeyFrameNeeded() {}
    }

    @Test
    fun fullSession() {
        assertTrue("session should reach RUNNING", running.await(10, TimeUnit.SECONDS))
        assertEquals("Test phone", hu.phoneRequest!!.deviceName)
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 8, 9), hu.openedChannels.toSet())

        // video: config chosen, focus granted, frames flow within the ack window
        assertTrue(videoStarted.await(5, TimeUnit.SECONDS))
        assertTrue(focusGained.await(5, TimeUnit.SECONDS))
        assertTrue("frames should reach the head unit", hu.firstVideoFrame.await(5, TimeUnit.SECONDS))
        Thread.sleep(500)
        assertTrue("several frames delivered, got ${hu.videoFrames.get()}", hu.videoFrames.get() > 10)
        assertTrue(hu.codecConfigs.get() >= 1)
        assertNotNull(hu.videoStart)

        // input: a tap arrives as a touch down + up
        hu.tap(100, 200)
        val down = inputs.poll(3, TimeUnit.SECONDS)!!
        assertEquals(100, down.touchEvent.getPointerData(0).x)
        assertNotNull(inputs.poll(3, TimeUnit.SECONDS))

        // input: a D-pad key and a rotary detent reach the phone through the same channel
        // (rotary/D-pad-only units have no touchscreen; keys and detents must still route). #42
        hu.key(19) // KEYCODE_DPAD_UP
        val keyDown = inputs.poll(3, TimeUnit.SECONDS)!!
        assertTrue("expected a key event", keyDown.hasKeyEvent())
        assertEquals(19, keyDown.keyEvent.getKeys(0).keycode)
        assertTrue("expected the key down", keyDown.keyEvent.getKeys(0).down)
        assertFalse("expected the key up", inputs.poll(3, TimeUnit.SECONDS)!!.keyEvent.getKeys(0).down)
        hu.rotary(1)
        val rotate = inputs.poll(3, TimeUnit.SECONDS)!!
        assertTrue("expected a relative (rotary) event", rotate.hasRelativeEvent())
        assertEquals(1, rotate.relativeEvent.getData(0).delta)

        // sensors: driving status and night mode were requested and answered
        assertNotNull(sensors.poll(3, TimeUnit.SECONDS))
        assertTrue(SensorType.SENSOR_NIGHT_MODE in hu.sensorRequests)
        assertTrue(SensorType.SENSOR_DRIVING_STATUS_DATA in hu.sensorRequests)

        // microphone
        mic!!.openMic()
        assertTrue(micData.await(3, TimeUnit.SECONDS))

        // audio focus round trip, then PCM on the media channel
        phone.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
        assertEquals(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN, audioFocus.poll(3, TimeUnit.SECONDS)!!.focusState)
        val media = audio.first { it.streamType.name.endsWith("MEDIA") }
        media.startPlayback()
        repeat(5) { assertTrue(media.sendPcm(ByteArray(10 + 3840), 10, 3840, it * 20_000L)) }
        Thread.sleep(300)
        assertEquals(5, hu.audioChunks.get())

        // keep-alive
        Thread.sleep(1500)
        assertTrue("pings answered: ${hu.pingsAnswered.get()}", hu.pingsAnswered.get() >= 1)
        assertTrue(phone.lastRttMs >= 0)

        // focus away and back: frames stop, then resume starting with a key frame
        hu.requestFocusNative()
        Thread.sleep(300)
        val during = hu.videoFrames.get()
        Thread.sleep(300)
        assertTrue("no frames while native", hu.videoFrames.get() - during <= 1)
        hu.requestFocusProjected()
        Thread.sleep(500)
        assertTrue(hu.videoFrames.get() > during)

        // orderly shutdown from the phone
        encoding.set(false)
        phone.stop()
        assertTrue(hu.byeBye.await(3, TimeUnit.SECONDS))
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        assertEquals(null, closeError)
    }

    @Test
    fun headUnitDisappearingClosesSession() {
        assertTrue(running.await(10, TimeUnit.SECONDS))
        hu.stop()
        assertTrue("phone notices the dead link", closed.await(5, TimeUnit.SECONDS))
        assertNotNull(closeError)
    }

    @Test
    fun headUnitByeBye() {
        assertTrue(running.await(10, TimeUnit.SECONDS))
        hu.sayByeBye()
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        assertEquals(null, closeError)
    }

    @Test
    fun headUnitByeByeThenHangUp() {
        assertTrue(running.await(10, TimeUnit.SECONDS))
        // The car powering down: bye-bye, then the link drops before our response can go out.
        hu.sayByeBye()
        huSocket.shutdownOutput()
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        assertEquals(null, closeError)
        assertTrue("reason: $closeReason", closeReason!!.contains("ended"))
    }

    @Test
    fun phoneStopClosesWhenHeadUnitNeverAnswers() {
        assertTrue(running.await(10, TimeUnit.SECONDS))
        hu.answerByeBye = false
        phone.stop()
        assertTrue(hu.byeBye.await(3, TimeUnit.SECONDS))
        // The grace-period close runs on the session's timer thread, through a transport whose
        // close() is interruptible: it must still reach onClosed.
        assertTrue("onClosed after the grace period", closed.await(3, TimeUnit.SECONDS))
        assertEquals(null, closeError)
    }

    @Test
    fun headUnitFocusRequestWithoutInputStaysProjected() {
        assertTrue(running.await(10, TimeUnit.SECONDS))
        assertTrue(focusGained.await(5, TimeUnit.SECONDS))
        hu.requestFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS)
        assertEquals(VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS, hu.focusNotifications.poll(3, TimeUnit.SECONDS))
        assertTrue("still projecting", video!!.focused)
    }

    @Test
    fun navUpdateInsideRateLimitIsSentLate() {
        assertTrue(running.await(10, TimeUnit.SECONDS))
        fun step(address: String) = NavigationState.newBuilder()
            .addDestinations(NavigationDestination.newBuilder().setAddress(address)).build()
        // The simulator asks for 500 ms between updates: the second one must be delayed, not dropped.
        nav!!.update(step("first"), null, null, null)
        nav!!.update(step("second"), null, null, null)
        assertEquals("first", hu.navStates.poll(3, TimeUnit.SECONDS)!!.getDestinations(0).address)
        assertEquals("second", hu.navStates.poll(3, TimeUnit.SECONDS)!!.getDestinations(0).address)
    }

    @Test
    fun heldNavUpdateIsDroppedWhenRerouting() {
        assertTrue(running.await(10, TimeUnit.SECONDS))
        fun step(address: String) = NavigationState.newBuilder()
            .addDestinations(NavigationDestination.newBuilder().setAddress(address)).build()
        nav!!.update(step("old route"), null, null, null)
        nav!!.update(step("abandoned"), null, null, null)
        // The held step belongs to the route being left: it must not follow REROUTING.
        nav!!.status(NavigationStatus.NavigationStatusEnum.REROUTING)
        assertEquals("old route", hu.navStates.poll(3, TimeUnit.SECONDS)!!.getDestinations(0).address)
        assertEquals(null, hu.navStates.poll(1, TimeUnit.SECONDS))
    }
}

/**
 * The same sessions against a head unit that omits every field set to 0, as Ford SYNC 4 does
 * (#74: an empty AUTH_COMPLETE, then a service discovery response aasdk's definitions rejected).
 */
class ZeroOmittingSessionLoopbackTest : SessionLoopbackTest() {
    override val omitZeroFields = true
}
