package net.prototux.aare.aap.sim

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestOuterClass.AudioFocusRequest
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.control.message.AuthResponseOuterClass.AuthResponse
import aap_protobuf.service.control.message.ByeByeReasonOuterClass.ByeByeReason
import aap_protobuf.service.control.message.ByeByeRequestOuterClass.ByeByeRequest
import aap_protobuf.service.control.message.ChannelOpenResponseOuterClass.ChannelOpenResponse
import aap_protobuf.service.control.message.ConnectionConfigurationOuterClass.ConnectionConfiguration
import aap_protobuf.service.control.message.HeadUnitInfoOuterClass.HeadUnitInfo
import aap_protobuf.service.control.message.PingConfigurationOuterClass.PingConfiguration
import aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest
import aap_protobuf.service.control.message.PingResponseOuterClass.PingResponse
import aap_protobuf.service.control.message.ServiceDiscoveryRequestOuterClass.ServiceDiscoveryRequest
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.inputsource.InputSourceServiceOuterClass.InputSourceService
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.RelativeEventOuterClass.RelativeEvent
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.sink.MediaSinkServiceOuterClass.MediaSinkService
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import aap_protobuf.service.media.source.MediaSourceServiceOuterClass.MediaSourceService
import aap_protobuf.service.media.source.message.AckOuterClass.Ack
import aap_protobuf.service.media.source.message.MicrophoneRequestOuterClass.MicrophoneRequest
import aap_protobuf.service.media.source.message.MicrophoneResponseOuterClass.MicrophoneResponse
import aap_protobuf.service.media.video.message.VideoFocusModeOuterClass.VideoFocusMode
import aap_protobuf.service.media.video.message.VideoFocusNotificationOuterClass.VideoFocusNotification
import aap_protobuf.service.media.video.message.VideoFocusRequestNotificationOuterClass.VideoFocusRequestNotification
import aap_protobuf.service.navigationstatus.NavigationStatusServiceOuterClass.NavigationStatusService
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent
import aap_protobuf.service.navigationstatus.message.NavigationStateOuterClass.NavigationState
import aap_protobuf.service.navigationstatus.message.NavigationStatusOuterClass.NavigationStatus
import aap_protobuf.service.radio.RadioServiceOuterClass.RadioService
import aap_protobuf.service.radio.message.RadioPropertiesOuterClass.RadioProperties
import aap_protobuf.service.radio.message.RadioTypeOuterClass.RadioType
import aap_protobuf.service.radio.message.RangeOuterClass.Range
import aap_protobuf.service.sensorsource.SensorSourceServiceOuterClass.SensorSourceService
import aap_protobuf.service.sensorsource.message.DrivingStatusDataOuterClass.DrivingStatusData
import aap_protobuf.service.sensorsource.message.NightModeDataOuterClass.NightModeData
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorOuterClass.Sensor
import aap_protobuf.service.sensorsource.message.SensorRequestOuterClass.SensorRequest
import aap_protobuf.service.sensorsource.message.SensorResponseOuterClass.SensorResponse
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import aap_protobuf.service.mediaplayback.MediaPlaybackStatusServiceOuterClass.MediaPlaybackStatusService
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import com.google.protobuf.MessageLite
import net.prototux.aare.aap.AapMessage
import net.prototux.aare.aap.ControlMsg
import net.prototux.aare.aap.Cryptor
import net.prototux.aare.aap.FrameReader
import net.prototux.aare.aap.FrameWriter
import net.prototux.aare.aap.InputMsg
import net.prototux.aare.aap.MediaMsg
import net.prototux.aare.aap.NavStatusMsg
import net.prototux.aare.aap.SensorMsg
import net.prototux.aare.aap.Transport
import net.prototux.aare.aap.cert.SelfSignedCertificate
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal head unit, for tests and for driving the phone app from a desktop.
 * It follows the same sequence as aasdk/openauto: version request, TLS as client, auth complete,
 * answer service discovery, accept channels, ack media.
 */
class HeadUnitSimulator(
    private val transport: Transport,
    val width: Int = 1280,
    val height: Int = 720,
    val dpi: Int = 160,
    private val maxUnacked: Int = 4,
    /** Raw H.264 (Annex-B) of the projection is written here if set. */
    private val videoOut: OutputStream? = null,
    /**
     * Encode like Ford SYNC 4 (#74): fields whose value is 0 are left out, as a proto3 encoder
     * does, even the ones aasdk's definitions call required.
     */
    omitZeroFields: Boolean = false,
    /** The protocol minor version the head unit announces (1.x). */
    private val protocolMinor: Int = 6,
) {
    /** False when encoding like a proto3 head unit: fields set to 0 are not written. */
    private val writeZeros = !omitZeroFields
    val huCert = SelfSignedCertificate.generate("AARE simulated head unit")
    private val cryptor = Cryptor(huCert.certificate, huCert.keyPair.private, client = true)
    @Volatile private var tlsUp = false
    private val reader = FrameReader(transport.input) { if (tlsUp) cryptor else null }
    private val writer = FrameWriter(transport.output) { if (tlsUp) cryptor else null }
    private val writeLock = Any()

    // channel ids, as a typical head unit numbers them
    val video = 1; val mediaAudio = 2; val guidance = 3; val system = 4; val mic = 5
    val input = 6; val sensors = 7; val navStatus = 8; val playback = 9; val radio = 10

    val discoveryRequest = CountDownLatch(1)
    var phoneRequest: ServiceDiscoveryRequest? = null
    val openedChannels = ConcurrentHashMap.newKeySet<Int>()
    val videoFrames = AtomicInteger()
    val videoBytes = AtomicInteger()
    val codecConfigs = AtomicInteger()
    val audioChunks = AtomicInteger()
    /** Set false to simulate a head unit that stops acking audio mid-stream (#82). */
    @Volatile var ackAudio = true
    val pingsAnswered = AtomicInteger()
    val focusRequests = AtomicInteger()
    val sensorRequests = ConcurrentHashMap.newKeySet<SensorType>()
    val firstVideoFrame = CountDownLatch(1)
    val byeBye = CountDownLatch(1)
    val closed = CountDownLatch(1)
    @Volatile var videoStart: Start? = null
    /** Each media channel's session, from its latest START: its acks carry it, as a head unit's do. */
    val sessionIds = ConcurrentHashMap<Int, Int>()
    @Volatile var error: Throwable? = null
    /** Video focus the phone reported, in order. */
    val focusNotifications = LinkedBlockingQueue<VideoFocusMode>()
    /** Navigation states the phone sent, in order. */
    val navStates = LinkedBlockingQueue<NavigationState>()
    /** The older turn events the phone sent, in order. */
    val navTurns = LinkedBlockingQueue<NavigationNextTurnEvent>()
    /** Navigation statuses the phone sent, in order. */
    val navStatuses = LinkedBlockingQueue<NavigationStatus.NavigationStatusEnum>()
    /** False plays a head unit that never answers the phone's bye-bye. */
    @Volatile var answerByeBye = true
    /** AUTH_COMPLETE's status: non-zero plays a head unit that refuses the phone certificate. */
    @Volatile var authStatus = 0
    /** True plays a link that goes away in the middle of the TLS handshake (a cable pulled then). */
    @Volatile var hangUpInHandshake = false

    fun start() {
        Thread({
            try {
                sendPlain(0, ControlMsg.VERSION_REQUEST, byteArrayOf(0, 1, 0, protocolMinor.toByte()))
                while (true) handle(reader.read())
            } catch (e: Throwable) {
                if (byeBye.count > 0L) error = e
            } finally {
                closed.countDown()
            }
        }, "hu-sim").apply { isDaemon = true }.start()
    }

    fun stop() = transport.close()

    private fun handle(m: AapMessage) {
        if (m.channel == 0 && !m.control) return control(m)
        if (m.control && m.id == ControlMsg.CHANNEL_OPEN_REQUEST) {
            openedChannels.add(m.channel)
            send(m.channel, ControlMsg.CHANNEL_OPEN_RESPONSE, ChannelOpenResponse.newBuilder()
                .apply { if (writeZeros) setStatus(MessageStatus.STATUS_SUCCESS) }.build(), control = true)
            return
        }
        when (m.channel) {
            video, mediaAudio, guidance, system -> mediaSink(m)
            mic -> micSource(m)
            input -> if (m.id == InputMsg.KEY_BINDING_REQUEST) send(input, InputMsg.KEY_BINDING_RESPONSE,
                aap_protobuf.service.media.sink.message.KeyBindingResponseOuterClass.KeyBindingResponse.newBuilder().setStatus(0).build())
            sensors -> if (m.id == SensorMsg.REQUEST) {
                val req = SensorRequest.parseFrom(m.bodyBytes())
                sensorRequests.add(req.type)
                send(sensors, SensorMsg.RESPONSE, SensorResponse.newBuilder().apply { if (writeZeros) setStatus(MessageStatus.STATUS_SUCCESS) }.build())
                val batch = SensorBatch.newBuilder()
                when (req.type) {
                    SensorType.SENSOR_NIGHT_MODE -> batch.addNightModeData(NightModeData.newBuilder().setNightMode(true))
                    SensorType.SENSOR_DRIVING_STATUS_DATA -> batch.addDrivingStatusData(DrivingStatusData.newBuilder().apply { if (writeZeros) setStatus(0) })
                    else -> Unit
                }
                send(sensors, SensorMsg.BATCH, batch.build())
            }
            navStatus -> when (m.id) {
                NavStatusMsg.STATE -> navStates.add(NavigationState.parseFrom(m.bodyBytes()))
                NavStatusMsg.TURN_EVENT -> navTurns.add(NavigationNextTurnEvent.parseFrom(m.bodyBytes()))
                NavStatusMsg.STATUS -> navStatuses.add(NavigationStatus.parseFrom(m.bodyBytes()).status)
            }
        }
    }

    private fun control(m: AapMessage) {
        when (m.id) {
            ControlMsg.VERSION_RESPONSE -> {
                cryptor.beginHandshake()
                sendPlain(0, ControlMsg.ENCAPSULATED_SSL, cryptor.handshake(ByteArray(0)))
            }
            ControlMsg.ENCAPSULATED_SSL -> {
                if (hangUpInHandshake) { transport.close(); return }
                val out = cryptor.handshake(m.bodyBytes())
                if (out.isNotEmpty()) sendPlain(0, ControlMsg.ENCAPSULATED_SSL, out)
                if (cryptor.handshakeDone && !tlsUp) {
                    sendPlain(0, ControlMsg.AUTH_COMPLETE, AuthResponse.newBuilder().apply { if (writeZeros || authStatus != 0) setStatus(authStatus) }.build().toByteArray())
                    tlsUp = true
                }
            }
            ControlMsg.SERVICE_DISCOVERY_REQUEST -> {
                phoneRequest = ServiceDiscoveryRequest.parseFrom(m.bodyBytes())
                send(0, ControlMsg.SERVICE_DISCOVERY_RESPONSE, discoveryResponse())
                discoveryRequest.countDown()
            }
            ControlMsg.PING_REQUEST -> {
                val req = PingRequest.parseFrom(m.bodyBytes())
                send(0, ControlMsg.PING_RESPONSE, PingResponse.newBuilder().setTimestamp(req.timestamp).build())
                pingsAnswered.incrementAndGet()
            }
            ControlMsg.AUDIO_FOCUS_REQUEST -> {
                val req = AudioFocusRequest.parseFrom(m.bodyBytes())
                val state = when (req.audioFocusType) {
                    AudioFocusRequestType.AUDIO_FOCUS_RELEASE -> AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS
                    AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT, AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK ->
                        AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT
                    else -> AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN
                }
                send(0, ControlMsg.AUDIO_FOCUS_NOTIFICATION, AudioFocusNotification.newBuilder().setFocusState(state).setUnsolicited(false).build())
            }
            ControlMsg.BYEBYE_REQUEST -> {
                byeBye.countDown()
                if (answerByeBye) send(0, ControlMsg.BYEBYE_RESPONSE, aap_protobuf.service.control.message.ByeByeResponseOuterClass.ByeByeResponse.getDefaultInstance())
            }
        }
    }

    private fun mediaSink(m: AapMessage) {
        when (m.id) {
            MediaMsg.SETUP -> send(m.channel, MediaMsg.CONFIG, Config.newBuilder().setStatus(Config.Status.STATUS_READY)
                .setMaxUnacked(maxUnacked).addConfigurationIndices(0).build())
            MediaMsg.START -> {
                val start = Start.parseFrom(m.bodyBytes())
                sessionIds[m.channel] = start.sessionId
                if (m.channel == video) videoStart = start
            }
            MediaMsg.VIDEO_FOCUS_REQUEST -> {
                focusRequests.incrementAndGet()
                val r = VideoFocusRequestNotification.parseFrom(m.bodyBytes())
                send(video, MediaMsg.VIDEO_FOCUS_NOTIFICATION, VideoFocusNotification.newBuilder()
                    .setFocus(if (r.mode == VideoFocusMode.VIDEO_FOCUS_NATIVE) VideoFocusMode.VIDEO_FOCUS_NATIVE else VideoFocusMode.VIDEO_FOCUS_PROJECTED)
                    .setUnsolicited(false).build())
            }
            MediaMsg.VIDEO_FOCUS_NOTIFICATION -> focusNotifications.add(VideoFocusNotification.parseFrom(m.bodyBytes()).focus)
            MediaMsg.CODEC_CONFIG -> {
                codecConfigs.incrementAndGet()
                videoOut?.write(m.body, m.bodyOffset, m.bodyLength)
            }
            MediaMsg.DATA -> {
                if (m.channel == video) {
                    videoOut?.write(m.body, m.bodyOffset + 8, m.bodyLength - 8)
                    videoBytes.addAndGet(m.bodyLength - 8)
                    videoFrames.incrementAndGet()
                    firstVideoFrame.countDown()
                } else {
                    audioChunks.incrementAndGet()
                }
                val session = sessionIds[m.channel] ?: 0
                if (m.channel == video || ackAudio)
                    send(m.channel, MediaMsg.ACK, Ack.newBuilder().apply { if (session != 0 || writeZeros) setSessionId(session) }.setAck(1).build())
            }
        }
    }

    private fun micSource(m: AapMessage) {
        when (m.id) {
            MediaMsg.SETUP -> send(mic, MediaMsg.CONFIG, Config.newBuilder().setStatus(Config.Status.STATUS_READY).setMaxUnacked(1).build())
            MediaMsg.MICROPHONE_REQUEST -> {
                val r = MicrophoneRequest.parseFrom(m.bodyBytes())
                send(mic, MediaMsg.MICROPHONE_RESPONSE, MicrophoneResponse.newBuilder().apply { if (writeZeros) setStatus(0) }.setSessionId(1).build())
                if (r.open) sendMicChunk()
            }
        }
    }

    /** Sends one 20 ms chunk of silence from the "car microphone". */
    fun sendMicChunk() {
        val pcm = ByteArray(2 + 8 + 640)
        pcm[1] = MediaMsg.DATA.toByte()
        sendRaw(mic, pcm)
    }

    /** Taps the screen at (x, y). */
    fun tap(x: Int, y: Int) {
        for (action in listOf(PointerAction.ACTION_DOWN, PointerAction.ACTION_UP)) {
            send(input, InputMsg.INPUT_REPORT, InputReport.newBuilder().setTimestamp(System.nanoTime())
                .setTouchEvent(TouchEvent.newBuilder().setAction(action).setActionIndex(0)
                    .addPointerData(TouchEvent.Pointer.newBuilder().setX(x).setY(y).setPointerId(0))).build())
        }
    }

    /** Presses and releases a car key (Android keycode numbering). */
    fun key(code: Int) {
        for (down in listOf(true, false)) {
            send(input, InputMsg.INPUT_REPORT, InputReport.newBuilder().setTimestamp(System.nanoTime())
                .setKeyEvent(aap_protobuf.service.inputsource.message.KeyEventOuterClass.KeyEvent.newBuilder()
                    .addKeys(aap_protobuf.service.inputsource.message.KeyEventOuterClass.KeyEvent.Key.newBuilder()
                        .setKeycode(code).setDown(down).setMetastate(0))).build())
        }
    }

    /** The car's dedicated function buttons (AA keycodes), as a head unit's hardware keys send them. */
    fun phoneButton() = key(65540) // KEYCODE_TEL
    fun navigationButton() = key(65538) // KEYCODE_NAVIGATION
    fun mediaButton() = key(65537) // KEYCODE_MEDIA
    fun radioButton() = key(65539) // KEYCODE_RADIO
    fun callButton() = key(5) // KEYCODE_CALL

    /** Turns the rotary controller [detents] detents (positive = clockwise), like a knob. */
    fun rotary(detents: Int) {
        send(input, InputMsg.INPUT_REPORT, InputReport.newBuilder().setTimestamp(System.nanoTime())
            .setRelativeEvent(RelativeEvent.newBuilder()
                .addData(RelativeEvent.Rel.newBuilder().setKeycode(65536 /* rotary */).setDelta(detents))).build())
    }

    fun requestFocusNative() = send(video, MediaMsg.VIDEO_FOCUS_NOTIFICATION,
        VideoFocusNotification.newBuilder().setFocus(VideoFocusMode.VIDEO_FOCUS_NATIVE).setUnsolicited(true).build())

    fun requestFocusProjected() = send(video, MediaMsg.VIDEO_FOCUS_NOTIFICATION,
        VideoFocusNotification.newBuilder().setFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED).setUnsolicited(true).build())

    /** Asks the phone for a video focus mode, as some head units do. */
    fun requestFocus(mode: VideoFocusMode) = send(video, MediaMsg.VIDEO_FOCUS_REQUEST,
        VideoFocusRequestNotification.newBuilder().setMode(mode).build())

    /** Sends an ack of the head unit's own making on a media channel. */
    fun sendAck(channel: Int, sessionId: Int, count: Int? = null, receiveTimesNs: List<Long> = emptyList()) =
        send(channel, MediaMsg.ACK, Ack.newBuilder().setSessionId(sessionId).apply { count?.let { setAck(it) } }
            .addAllReceiveTimestampNs(receiveTimesNs).build())

    fun sayByeBye() = send(0, ControlMsg.BYEBYE_REQUEST, ByeByeRequest.newBuilder().setReason(ByeByeReason.USER_SELECTION).build())

    fun discoveryResponse(): ServiceDiscoveryResponse {
        val res = when {
            width >= 1920 -> VideoCodecResolutionType.VIDEO_1920x1080
            width >= 1280 -> VideoCodecResolutionType.VIDEO_1280x720
            else -> VideoCodecResolutionType.VIDEO_800x480
        }
        fun audio(id: Int, type: AudioStreamType, rate: Int, ch: Int) = Service.newBuilder().setId(id).setMediaSinkService(
            MediaSinkService.newBuilder().setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM).setAudioType(type)
                .addAudioConfigs(AudioConfiguration.newBuilder().setSamplingRate(rate).setNumberOfBits(16).setNumberOfChannels(ch)))
        return ServiceDiscoveryResponse.newBuilder()
            .setHeadunitInfo(HeadUnitInfo.newBuilder().setMake("AARE").setModel("Simulator").setYear("2026")
                .setHeadUnitMake("AARE").setHeadUnitModel("hu-sim").setHeadUnitSoftwareVersion("1"))
            .setDisplayName("Simulated car")
            .setConnectionConfiguration(ConnectionConfiguration.newBuilder().setPingConfiguration(
                PingConfiguration.newBuilder().setIntervalMs(1000).setTimeoutMs(3000).setTrackedPingCount(5)))
            .addChannels(Service.newBuilder().setId(video).setMediaSinkService(MediaSinkService.newBuilder()
                .setAvailableType(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                .addVideoConfigs(VideoConfiguration.newBuilder().setCodecResolution(res)
                    .setFrameRate(VideoFrameRateType.VIDEO_FPS_30).setDensity(dpi).apply { if (writeZeros) { setWidthMargin(0); setHeightMargin(0) } })))
            .addChannels(audio(mediaAudio, AudioStreamType.AUDIO_STREAM_MEDIA, 48000, 2))
            .addChannels(audio(guidance, AudioStreamType.AUDIO_STREAM_GUIDANCE, 16000, 1))
            .addChannels(audio(system, AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO, 16000, 1))
            .addChannels(Service.newBuilder().setId(mic).setMediaSourceService(MediaSourceService.newBuilder()
                .setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM)
                .setAudioConfig(AudioConfiguration.newBuilder().setSamplingRate(16000).setNumberOfBits(16).setNumberOfChannels(1))))
            .addChannels(Service.newBuilder().setId(input).setInputSourceService(InputSourceService.newBuilder()
                // HOME, BACK, CALL, ENDCALL, DPAD, SEARCH, media, VOICE_ASSIST, rotary, and the AA
                // function buttons: MEDIA, NAVIGATION, RADIO, TEL.
                .addAllKeycodesSupported(listOf(3, 4, 5, 6, 19, 20, 21, 22, 23, 84, 85, 87, 88, 231, 65536, 65537, 65538, 65539, 65540))
                .addTouchscreen(InputSourceService.TouchScreen.newBuilder().setWidth(width).setHeight(height))))
            .addChannels(Service.newBuilder().setId(sensors).setSensorSourceService(SensorSourceService.newBuilder()
                .addSensors(Sensor.newBuilder().setSensorType(SensorType.SENSOR_DRIVING_STATUS_DATA))
                .addSensors(Sensor.newBuilder().setSensorType(SensorType.SENSOR_NIGHT_MODE))
                .addSensors(Sensor.newBuilder().setSensorType(SensorType.SENSOR_LOCATION))))
            .addChannels(Service.newBuilder().setId(navStatus).setNavigationStatusService(NavigationStatusService.newBuilder()
                .setMinimumIntervalMs(500).setType(NavigationStatusService.InstrumentClusterType.ENUM)))
            .addChannels(Service.newBuilder().setId(playback).setMediaPlaybackService(MediaPlaybackStatusService.getDefaultInstance()))
            // An AM tuner numbered 0: a proto3 head unit sends neither its id nor its type (both 0).
            .addChannels(Service.newBuilder().setId(radio).setRadioService(RadioService.newBuilder().addRadioProperties(
                RadioProperties.newBuilder().apply { if (writeZeros) { setRadioId(0); setType(RadioType.AM_RADIO) } }
                    .addChannelRange(Range.newBuilder().setMin(530).setMax(1710)).setChannelSpacing(10))))
            .build()
    }

    // ---- sending

    private fun sendPlain(channel: Int, id: Int, body: ByteArray) = synchronized(writeLock) {
        writer.write(channel, false, false, AapMessage.plaintext(id, body))
    }

    fun send(channel: Int, id: Int, body: MessageLite, control: Boolean = false) = synchronized(writeLock) {
        writer.write(channel, control, tlsUp, AapMessage.plaintext(id, body.toByteArray()))
    }

    private fun sendRaw(channel: Int, plain: ByteArray) = synchronized(writeLock) {
        writer.write(channel, false, tlsUp, plain)
    }
}
