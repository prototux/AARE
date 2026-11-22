package net.prototux.aare.headunit

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.control.message.NavFocusNotificationOuterClass.NavFocusNotification
import aap_protobuf.service.control.message.NavFocusTypeOuterClass.NavFocusType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import aap_protobuf.service.phonestatus.message.PhoneStatusInputOuterClass.PhoneStatusInput
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.wifiprojection.message.WifiCredentialsRequestOuterClass.WifiCredentialsRequest
import aap_protobuf.service.wifiprojection.message.WifiCredentialsResponseOuterClass.WifiCredentialsResponse
import aap_protobuf.shared.InstrumentClusterInputOuterClass.InstrumentClusterInput
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.AapMessage
import net.prototux.aare.aap.AapSession
import net.prototux.aare.aap.PhoneDescription
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.Priority
import net.prototux.aare.aap.ServiceChannel
import net.prototux.aare.aap.SocketTransport
import net.prototux.aare.aap.WifiProjectionMsg
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.BluetoothChannel
import net.prototux.aare.aap.channels.InputChannel
import net.prototux.aare.aap.channels.MediaPlaybackChannel
import net.prototux.aare.aap.channels.MicrophoneChannel
import net.prototux.aare.aap.channels.NavigationStatusChannel
import net.prototux.aare.aap.channels.PhoneStatusChannel
import net.prototux.aare.aap.channels.SensorChannel
import net.prototux.aare.aap.channels.VideoChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The desktop head unit against the phone side of the aap module (what AARE runs) over a loopback
 * socket: the plain head unit, and one case per kind of test option. Checks the phone's state and
 * callbacks, not its log wording, and never waits out a timeout: an answer that must not come is
 * checked once a later one has (the head unit answers in order, from one thread).
 */
class LoopbackTest {
    private val links = mutableListOf<Link>()

    @After
    fun tearDown() = links.forEach { it.stop() }

    private fun connect(sim: Sim = Sim()) = Link(sim).also { links += it }

    @Test
    fun plainHeadUnit() {
        val l = connect()
        l.awaitRunning()
        assertTrue(l.channels.values.all { it.opened })
        assertNull(l.phone.certificateRefusal)
        // The plain head unit never answered a pairing request.
        l.bluetooth.requestPairing(PHONE_BT_ADDRESS)
        l.roundTrip()
        assertNull(l.pairing.poll())
    }

    @Test
    fun identityAndPairing() {
        val l = connect(Sim(make = "Mazda", model = "3", year = "2019", huMake = "Harman", huModel = "CMU", huSoftwareVersion = "74",
            vehicleId = "VIN123", carBtAddress = "00:11:22:33:44:55"))
        l.awaitRunning()
        with(l.discovery!!.headunitInfo) {
            assertEquals(listOf("Mazda", "3", "2019", "Harman", "CMU", "74", "VIN123"),
                listOf(make, model, year, headUnitMake, headUnitModel, headUnitSoftwareVersion, vehicleId))
        }
        assertEquals("00:11:22:33:44:55", l.bluetooth.carAddress)
        l.bluetooth.requestPairing(PHONE_BT_ADDRESS)
        assertEquals(true to true, l.pairing.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun authFail() {
        val l = connect(Sim(faults = setOf(Sim.Fault.AUTH_FAIL)))
        l.awaitClosed()
        assertEquals(MessageStatus.STATUS_AUTHENTICATION_FAILURE_VALUE, l.phone.certificateRefusal?.authStatus)
    }

    @Test
    fun refusedChannel() {
        val l = connect(Sim(refuseChannels = setOf(7)))
        // Running: every open request was answered, the refused one too.
        l.awaitRunning()
        assertFalse(l.channels.getValue(7).opened)
        assertTrue(l.channels.filterKeys { it != 7 }.values.all { it.opened })
    }

    @Test
    fun ignoredChannel() {
        val l = connect(Sim(ignoreChannels = setOf(7)))
        l.awaitTrue("service discovery") { l.channels.isNotEmpty() }
        // The phone asks in order and the head unit answers in order: once the last channel is open,
        // channel 7 got no answer, and the phone is still waiting for it.
        val last = l.channels.values.last()
        l.awaitTrue("last channel open") { last.opened }
        assertFalse(l.channels.getValue(7).opened)
        assertEquals(AapSession.State.OPENING, l.phone.state)
    }

    @Test
    fun wifiProjection() {
        val l = connect(Sim(aacSink = true, unknownService = true, wifiProjection = Sim.WifiProjection("CarNet 5G", "02:00:5E:10:00:01")))
        l.awaitRunning()
        assertEquals(mapOf(12 to "audio:AUDIO_STREAM_MEDIA", 13 to "notifications", 14 to "wifi"),
            l.discovery!!.channelsList.filter { it.id in 12..14 }.associate { it.id to AapSession.serviceKind(it) })
        l.wifi!!.ask()
        val r = WifiCredentialsResponse.parseFrom(l.wifiAnswers.poll(5, TimeUnit.SECONDS)!!.bodyBytes())
        assertEquals("CarNet 5G", r.carWifiSsid)
        assertEquals(HeadUnit.FAKE_WIFI_PASSWORD, r.carWifiPassword)
    }

    @Test
    fun scriptEvents() {
        val l = connect()
        l.awaitRunning()
        val hu = l.hu

        hu.navFocus()
        assertEquals(NavFocusType.NAV_FOCUS_PROJECTED, l.navFocus.poll(5, TimeUnit.SECONDS)?.focusType)
        hu.audioFocus(Sim.AUDIO_FOCUS_STATES.getValue("loss-transient"))
        val focus = l.audioFocus.poll(5, TimeUnit.SECONDS)!!
        assertEquals(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT, focus.focusState)
        assertTrue(focus.unsolicited)

        hu.phoneInput()
        assertEquals(InstrumentClusterInput.InstrumentClusterAction.CALL, l.phoneInputs.poll(5, TimeUnit.SECONDS)?.input?.action)
        hu.uiConfig()
        val insets = l.uiConfigs.poll(5, TimeUnit.SECONDS)!!.contentInsets
        assertEquals(listOf(96, 32, 96, 32), listOf(insets.left, insets.top, insets.right, insets.bottom))

        l.awaitTrue("video focus") { l.video!!.focused }
        // The phone also asked for focus after setup, at video priority: let that answer come first.
        l.roundTrip(Priority.VIDEO)
        hu.videoFocus(VideoFocusMode.VIDEO_FOCUS_NATIVE)
        l.awaitTrue("native video focus") { !l.video!!.focused }

        hu.key(3, true, longPress = true)
        hu.key(3, false, longPress = true)
        val keys = List(2) { l.inputs.poll(5, TimeUnit.SECONDS)!! }.flatMap { it.keyEvent.keysList }
        assertEquals(listOf(Triple(3, true, true), Triple(3, false, true)), keys.map { Triple(it.keycode, it.down, it.longpress) })

        // A body the phone can't parse: it reports an error and carries on.
        hu.garbage(hu.channelId("sensors")!!)
        hu.navFocus()
        l.navFocus.poll(5, TimeUnit.SECONDS)!!
        assertTrue(l.errors.isNotEmpty())
        assertFalse(l.phone.isClosed)

        hu.byeBye()
        l.awaitClosed()
        assertNull("an orderly end", l.closeError)
    }

    // ------------------------------------------------------------------ harness

    /** One phone session and one desktop head unit, joined by a loopback socket. */
    private class Link(sim: Sim) {
        val errors = CopyOnWriteArrayList<String>()
        val audioFocus = LinkedBlockingQueue<AudioFocusNotification>()
        val navFocus = LinkedBlockingQueue<NavFocusNotification>()
        val pairing = LinkedBlockingQueue<Pair<Boolean, Boolean>>()
        val phoneInputs = LinkedBlockingQueue<PhoneStatusInput>()
        val uiConfigs = LinkedBlockingQueue<UiConfig>()
        val inputs = LinkedBlockingQueue<InputReport>()
        val wifiAnswers = LinkedBlockingQueue<AapMessage>()
        private val running = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        @Volatile var closeError: Throwable? = null
        @Volatile var discovery: ServiceDiscoveryResponse? = null
        /** The channels the phone asked to open, in the order it asked. */
        @Volatile var channels: Map<Int, ServiceChannel> = emptyMap()
        val video get() = channels[1] as VideoChannel?
        val bluetooth get() = channels.getValue(10) as BluetoothChannel
        val wifi get() = channels[14] as WifiProbe?
        val phone: AapSession
        val hu: HeadUnit

        init {
            val loopback = InetAddress.getLoopbackAddress()
            val server = ServerSocket(0, 1, loopback)
            val huSocket = Socket(loopback, server.localPort)
            val phoneSocket = server.accept()
            server.close()
            val log = object : AapLog {
                override fun i(msg: String) {}
                override fun w(msg: String, t: Throwable?) {}
                override fun e(msg: String, t: Throwable?) { errors += msg }
            }
            phone = AapSession(SocketTransport(phoneSocket), PHONE, PhoneDescription("Test phone", "AARE"), Callbacks(), log)
            hu = HeadUnit(SocketTransport(huSocket), HU, HeadUnit.Options(sim = sim), object : HeadUnit.Listener {
                override fun status(text: String) {}
                override fun videoStarted(width: Int, height: Int) {}
                override fun video(data: ByteArray, offset: Int, length: Int) {}
                override fun videoCodecConfig(data: ByteArray, offset: Int, length: Int) {}
                override fun audioStart(stream: AudioStreamType, config: AudioConfiguration) {}
                override fun audio(stream: AudioStreamType, data: ByteArray, offset: Int, length: Int) {}
                override fun audioStop(stream: AudioStreamType) {}
                override fun micOpened() {}
                override fun micClosed() {}
                override fun navigation(text: String) {}
                override fun nowPlaying(text: String) {}
                override fun phone(text: String) {}
                override fun closed(reason: String) {}
            })
            phone.start()
            hu.start()
        }

        fun stop() {
            hu.close("test over")
            phone.stop()
        }

        fun awaitRunning() = assertTrue("session running", running.await(5, TimeUnit.SECONDS))

        fun awaitClosed() = assertTrue("session closed", closed.await(5, TimeUnit.SECONDS))

        fun awaitTrue(what: String, check: () -> Boolean) {
            val end = System.nanoTime() + 5_000_000_000L
            while (!check()) {
                if (System.nanoTime() > end) throw AssertionError("no $what within 5 s")
                Thread.sleep(10)
            }
        }

        /**
         * Asks for audio focus and waits for the answer: whatever the head unit sent before it, in
         * answer to what the phone queued before it at [priority], has arrived.
         */
        fun roundTrip(priority: Int = Priority.CONTROL) {
            audioFocus.clear()
            phone.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN, priority)
            assertTrue("audio focus answer", audioFocus.poll(5, TimeUnit.SECONDS) != null)
        }

        /**
         * Opens what the head unit lists with the aap channel classes AARE uses. Like AARE's
         * CarSession it leaves non-PCM audio sinks and services it has no class for closed; the
         * Bluetooth channel asks for pairing only when a test calls requestPairing.
         */
        private inner class Callbacks : AapSession.Callbacks {
            override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> {
                discovery = response
                val list = response.channelsList.mapNotNull(::channel)
                channels = list.associateBy { it.id }
                return list
            }

            private fun channel(s: Service): ServiceChannel? = when {
                s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0 -> VideoChannel(s, object : VideoChannel.Listener {
                    override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>) = 0
                    override fun onVideoStart(index: Int, config: VideoConfiguration) {}
                    override fun onVideoFocus(projected: Boolean) {}
                    override fun onKeyFrameNeeded() {}
                    override fun onUiConfig(config: UiConfig) { uiConfigs += config }
                })
                s.hasMediaSinkService() && s.mediaSinkService.availableType != MediaCodecType.MEDIA_CODEC_AUDIO_PCM -> null
                s.hasMediaSinkService() -> AudioChannel(s)
                s.hasMediaSourceService() -> MicrophoneChannel(s, object : MicrophoneChannel.Listener {
                    override fun onMicData(data: ByteArray, offset: Int, length: Int, timestampUs: Long) {}
                })
                s.hasInputSourceService() -> InputChannel(s, object : InputChannel.Listener {
                    override fun onInput(report: InputReport) { if (report.hasKeyEvent()) inputs += report }
                })
                s.hasSensorSourceService() -> SensorChannel(s, object : SensorChannel.Listener {
                    override fun onSensors(batch: SensorBatch) {}
                })
                s.hasNavigationStatusService() -> NavigationStatusChannel(s)
                s.hasMediaPlaybackService() -> MediaPlaybackChannel(s)
                s.hasPhoneStatusService() -> PhoneStatusChannel(s) { phoneInputs += it }
                s.hasWifiProjectionService() -> WifiProbe(s)
                s.hasBluetoothService() -> BluetoothChannel(s, null, object : BluetoothChannel.Listener {
                    override fun onPairingResponse(alreadyPaired: Boolean, ok: Boolean) { pairing += alreadyPaired to ok }
                })
                else -> null
            }

            override fun onRunning(session: AapSession) = running.countDown()
            override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) { audioFocus += notification }
            override fun onNavFocus(session: AapSession, notification: NavFocusNotification) { navFocus += notification }
            override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
                closeError = error
                closed.countDown()
            }
        }

        /** AARE's WifiProjectionChannel never asks for credentials: this one does, and keeps the answer. */
        inner class WifiProbe(service: Service) : ServiceChannel(service, "wifi") {
            fun ask() = send(WifiProjectionMsg.CREDENTIALS_REQUEST, WifiCredentialsRequest.getDefaultInstance())
            override fun onMessage(msg: AapMessage) { if (msg.id == WifiProjectionMsg.CREDENTIALS_RESPONSE) wifiAnswers += msg }
        }
    }

    private companion object {
        const val PHONE_BT_ADDRESS = "02:11:22:33:44:55"
        val PHONE = SelfSignedCertificate.generate("phone").let { PhoneIdentity(it.certificate, it.keyPair.private) }
        val HU = SelfSignedCertificate.generate("head unit").let { PhoneIdentity(it.certificate, it.keyPair.private) }
    }
}
