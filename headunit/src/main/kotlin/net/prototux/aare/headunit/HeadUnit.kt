package net.prototux.aare.headunit

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.bluetooth.BluetoothServiceOuterClass.BluetoothService
import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestOuterClass.AudioFocusRequest
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.control.message.AuthResponseOuterClass.AuthResponse
import aap_protobuf.service.control.message.ByeByeReasonOuterClass.ByeByeReason
import aap_protobuf.service.control.message.ByeByeRequestOuterClass.ByeByeRequest
import aap_protobuf.service.control.message.ByeByeResponseOuterClass.ByeByeResponse
import aap_protobuf.service.control.message.ChannelOpenResponseOuterClass.ChannelOpenResponse
import aap_protobuf.service.control.message.ConnectionConfigurationOuterClass.ConnectionConfiguration
import aap_protobuf.service.control.message.DriverPositionOuterClass.DriverPosition
import aap_protobuf.service.control.message.HeadUnitInfoOuterClass.HeadUnitInfo
import aap_protobuf.service.control.message.NavFocusNotificationOuterClass.NavFocusNotification
import aap_protobuf.service.control.message.NavFocusTypeOuterClass.NavFocusType
import aap_protobuf.service.control.message.PingConfigurationOuterClass.PingConfiguration
import aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest
import aap_protobuf.service.control.message.PingResponseOuterClass.PingResponse
import aap_protobuf.service.control.message.ServiceDiscoveryRequestOuterClass.ServiceDiscoveryRequest
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.inputsource.InputSourceServiceOuterClass.InputSourceService
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.inputsource.message.KeyEventOuterClass.KeyEvent
import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.RelativeEventOuterClass.RelativeEvent
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.shared.message.ConfigOuterClass.Config
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.StartOuterClass.Start
import aap_protobuf.service.media.sink.MediaSinkServiceOuterClass.MediaSinkService
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.DisplayTypeOuterClass.DisplayType
import aap_protobuf.service.media.sink.message.KeyBindingResponseOuterClass.KeyBindingResponse
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
import aap_protobuf.service.mediaplayback.MediaPlaybackStatusServiceOuterClass.MediaPlaybackStatusService
import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import aap_protobuf.service.navigationstatus.NavigationStatusServiceOuterClass.NavigationStatusService
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnDistanceEventOuterClass.NavigationNextTurnDistanceEvent
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent
import aap_protobuf.service.navigationstatus.message.NavigationStatusOuterClass.NavigationStatus
import aap_protobuf.service.sensorsource.SensorSourceServiceOuterClass.SensorSourceService
import aap_protobuf.service.sensorsource.message.DrivingStatusDataOuterClass.DrivingStatusData
import aap_protobuf.service.sensorsource.message.GearDataOuterClass.GearData
import aap_protobuf.service.sensorsource.message.GearOuterClass.Gear
import aap_protobuf.service.sensorsource.message.LocationDataOuterClass.LocationData
import aap_protobuf.service.sensorsource.message.NightModeDataOuterClass.NightModeData
import aap_protobuf.service.sensorsource.message.ParkingBrakeDataOuterClass.ParkingBrakeData
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorOuterClass.Sensor
import aap_protobuf.service.sensorsource.message.SensorRequestOuterClass.SensorRequest
import aap_protobuf.service.sensorsource.message.SensorResponseOuterClass.SensorResponse
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import aap_protobuf.service.sensorsource.message.SpeedDataOuterClass.SpeedData
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import com.google.protobuf.MessageLite
import net.prototux.aare.aap.AapMessage
import net.prototux.aare.aap.ControlMsg
import net.prototux.aare.aap.Cryptor
import net.prototux.aare.aap.FrameReader
import net.prototux.aare.aap.FrameWriter
import net.prototux.aare.aap.InputMsg
import net.prototux.aare.aap.MediaMsg
import net.prototux.aare.aap.MediaPlaybackMsg
import net.prototux.aare.aap.NavStatusMsg
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.SensorMsg
import net.prototux.aare.aap.Transport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The head-unit side of AAP: what a car does. Speaks first (version request), is the TLS client,
 * describes its screen, speakers, microphone, controls and sensors, then plays what the phone
 * sends and feeds back input and sensor data.
 */
class HeadUnit(
    private val transport: Transport,
    private val identity: PhoneIdentity,
    val config: Options,
    private val ui: Listener,
) {
    data class Options(
        val width: Int = 1280,
        val height: Int = 720,
        val dpi: Int = 160,
        val fps60: Boolean = false,
        val name: String = "AARE desktop head unit",
        val rightHandDrive: Boolean = false,
        /** Also offer an 800x480 instrument-cluster display (a second video sink, display_type CLUSTER). */
        val cluster: Boolean = false,
        /**
         * Present as a head unit with no touchscreen (rotary knob / D-pad only, like the Mazda
         * commander in #42). The input service still advertises its keycodes, but no touch panel,
         * so the phone drives the UI entirely from keys and rotary detents.
         */
        val noTouch: Boolean = false,
    )

    interface Listener {
        fun status(text: String)
        fun videoStarted(width: Int, height: Int)
        fun video(data: ByteArray, offset: Int, length: Int)
        /** The stream's SPS/PPS; a decoder started later needs them again. */
        fun videoCodecConfig(data: ByteArray, offset: Int, length: Int)
        /** The instrument cluster's video (only with [Options.cluster]). */
        fun clusterStarted(width: Int, height: Int) {}
        fun clusterVideo(data: ByteArray, offset: Int, length: Int, config: Boolean) {}
        fun audioStart(stream: AudioStreamType, config: AudioConfiguration)
        fun audio(stream: AudioStreamType, data: ByteArray, offset: Int, length: Int)
        fun audioStop(stream: AudioStreamType)
        /** The phone wants the microphone; call [HeadUnit.micData] until [micClosed]. */
        fun micOpened()
        fun micClosed()
        fun navigation(text: String)
        fun nowPlaying(text: String)
        /** Calls, signal, battery and call availability from the phone. */
        fun phone(text: String) = println("phone: $text")
        fun closed(reason: String)
    }

    // Channel numbers, like a typical head unit lays them out.
    private val chVideo = 1
    private val chMedia = 2
    private val chGuidance = 3
    private val chSystem = 4
    private val chMic = 5
    private val chInput = 6
    private val chSensors = 7
    private val chNav = 8
    private val chPlayback = 9
    private val chBluetooth = 10
    private val chCluster = 11
    private val chPhone = 15
    private val phoneText = PhoneStatusText()

    private val cryptor = Cryptor(identity.certificate, identity.privateKey, identity.chain, client = true)
    @Volatile private var tlsUp = false
    /** The TLS handshake began (a failure from here on is usually a refused certificate). */
    @Volatile private var tlsStarted = false
    private val reader = FrameReader(transport.input) { if (tlsUp) cryptor else null }
    private val writer = FrameWriter(transport.output) { if (tlsUp) cryptor else null }
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "hu-timer").apply { isDaemon = true } }

    private val sessions = ConcurrentHashMap<Int, Int>()
    private val sensorsWanted = ConcurrentHashMap.newKeySet<SensorType>()
    /** Fake RPM, odometer, fuel, doors, tyres... for the phone's car data (FakeCar.SENSORS). */
    private val fakeCar = FakeCar()
    private val micSession = AtomicInteger(0)
    @Volatile private var micOpen = false
    @Volatile var night = false
        private set
    @Volatile var driving = true
        private set
    @Volatile private var lastFix: Fix? = null

    var phoneName: String = ""
        private set

    fun start() {
        Thread({
            try {
                sendPlain(ControlMsg.VERSION_REQUEST, byteArrayOf(0, 1, 0, 7))
                ui.status("Connected, negotiating…")
                while (!closed.get()) handle(reader.read())
            } catch (e: Exception) {
                close(when {
                    closed.get() -> "closed"
                    // The phone hung up in the middle of the TLS handshake: it didn't trust our certificate.
                    tlsStarted && !tlsUp -> "the phone refused the head unit's certificate. Google's Android Auto only accepts " +
                        "head units with a certificate issued by Google (give one with --hu-cert/--hu-key); AARE asks first: tap Trust on its \"New head unit\" notification or home screen, then connect again"
                    else -> "connection lost: ${e.message}"
                })
            }
        }, "hu-reader").apply { isDaemon = true }.start()
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        timer.shutdownNow()
        transport.close()
        ui.closed(reason)
    }

    /** Polite disconnect, like turning the car off. */
    fun byeBye() {
        if (!tlsUp) return close("disconnected")
        send(0, ControlMsg.BYEBYE_REQUEST, ByeByeRequest.newBuilder().setReason(ByeByeReason.USER_SELECTION).build())
        timer.schedule({ close("disconnected") }, 1, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ input

    /** A touch in video coordinates; [action] is a PointerAction. */
    fun touch(action: PointerAction, x: Int, y: Int) {
        if (!tlsUp) return
        send(chInput, InputMsg.INPUT_REPORT, InputReport.newBuilder().setTimestamp(System.nanoTime() / 1000)
            .setTouchEvent(TouchEvent.newBuilder().setAction(action).setActionIndex(0)
                .addPointerData(TouchEvent.Pointer.newBuilder().setX(x.coerceAtLeast(0)).setY(y.coerceAtLeast(0)).setPointerId(0)))
            .build())
    }

    fun key(code: Int, down: Boolean, longPress: Boolean = false) {
        if (!tlsUp) return
        send(chInput, InputMsg.INPUT_REPORT, InputReport.newBuilder().setTimestamp(System.nanoTime() / 1000)
            .setKeyEvent(KeyEvent.newBuilder().addKeys(KeyEvent.Key.newBuilder().setKeycode(code).setDown(down).setMetastate(0).setLongpress(longPress)))
            .build())
    }

    fun pressKey(code: Int) { key(code, true); key(code, false) }

    /** Rotary controller detents (positive = clockwise). */
    fun rotate(detents: Int) {
        if (!tlsUp) return
        send(chInput, InputMsg.INPUT_REPORT, InputReport.newBuilder().setTimestamp(System.nanoTime() / 1000)
            .setRelativeEvent(RelativeEvent.newBuilder().addData(RelativeEvent.Rel.newBuilder().setKeycode(KEYCODE_ROTARY).setDelta(detents)))
            .build())
    }

    // ------------------------------------------------------------------ sensors

    fun setNight(on: Boolean) {
        night = on
        if (SensorType.SENSOR_NIGHT_MODE in sensorsWanted) sendBatch(SensorBatch.newBuilder().addNightModeData(NightModeData.newBuilder().setNightMode(on)))
    }

    fun setDriving(on: Boolean) {
        driving = on
        sendDrivingStatus()
    }

    fun location(fix: Fix) {
        lastFix = fix
        if (!tlsUp) return
        val b = SensorBatch.newBuilder()
        if (SensorType.SENSOR_LOCATION in sensorsWanted) {
            b.addLocationData(LocationData.newBuilder()
                .setLatitudeE7((fix.lat * 1e7).toInt()).setLongitudeE7((fix.lon * 1e7).toInt())
                .setAccuracyE3(4_000).setAltitudeE2(10_000)
                .setSpeedE3((fix.speed * 1e3).toInt()).setBearingE6((fix.bearing * 1e6).toInt()))
        }
        if (SensorType.SENSOR_SPEED in sensorsWanted) b.addSpeedData(SpeedData.newBuilder().setSpeedE3((fix.speed * 1e3).toInt()))
        if (b.locationDataCount + b.speedDataCount > 0) sendBatch(b)
    }

    private fun sendDrivingStatus() {
        if (SensorType.SENSOR_DRIVING_STATUS_DATA !in sensorsWanted) return
        // 31 = no video, keyboard, voice-free text, config, long messages: what a moving car reports.
        sendBatch(SensorBatch.newBuilder().addDrivingStatusData(DrivingStatusData.newBuilder().setStatus(if (driving) 31 else 0)))
        if (SensorType.SENSOR_PARKING_BRAKE in sensorsWanted) sendBatch(SensorBatch.newBuilder().addParkingBrakeData(ParkingBrakeData.newBuilder().setParkingBrake(!driving)))
        if (SensorType.SENSOR_GEAR in sensorsWanted) sendBatch(SensorBatch.newBuilder().addGearData(GearData.newBuilder().setGear(if (driving) Gear.GEAR_DRIVE else Gear.GEAR_PARK)))
    }

    private fun sendBatch(b: SensorBatch.Builder) {
        if (tlsUp) send(chSensors, SensorMsg.BATCH, b.build())
    }

    // ------------------------------------------------------------------ microphone

    /** Desktop microphone PCM (16 kHz mono s16le) while the phone listens. */
    fun micData(pcm: ByteArray, length: Int) {
        if (!micOpen) return
        val plain = ByteArray(2 + 8 + length)
        val ts = System.nanoTime() / 1000
        for (i in 0 until 8) plain[2 + i] = (ts ushr (56 - 8 * i)).toByte()
        System.arraycopy(pcm, 0, plain, 10, length)
        sendRaw(chMic, plain)
    }

    // ------------------------------------------------------------------ incoming

    private fun handle(m: AapMessage) {
        if (m.channel == 0 && !m.control) return control(m)
        if (m.control && m.id == ControlMsg.CHANNEL_OPEN_REQUEST) {
            send(m.channel, ControlMsg.CHANNEL_OPEN_RESPONSE, ChannelOpenResponse.newBuilder().setStatus(MessageStatus.STATUS_SUCCESS).build(), control = true)
            if (m.channel == chNav) send(chNav, NavStatusMsg.START, aap_protobuf.service.navigationstatus.message.NavigationStatusStartOuterClass.NavigationStatusStart.getDefaultInstance())
            return
        }
        when (m.channel) {
            chVideo, chMedia, chGuidance, chSystem, chCluster -> mediaSink(m)
            chMic -> micSource(m)
            chInput -> if (m.id == InputMsg.KEY_BINDING_REQUEST) send(chInput, InputMsg.KEY_BINDING_RESPONSE, KeyBindingResponse.newBuilder().setStatus(0).build())
            chSensors -> if (m.id == SensorMsg.REQUEST) sensorRequest(SensorRequest.parseFrom(m.bodyBytes()))
            chNav -> navigation(m)
            chPlayback -> playback(m)
            chPhone -> if (m.id == net.prototux.aare.aap.PhoneStatusMsg.STATUS) ui.phone(phoneText.status(m.bodyBytes()))
        }
    }

    private fun control(m: AapMessage) {
        when (m.id) {
            ControlMsg.VERSION_RESPONSE -> {
                val b = m.bodyBytes()
                val ver = if (b.size >= 4) "${(b[0].toInt() shl 8) or (b[1].toInt() and 0xff)}.${(b[2].toInt() shl 8) or (b[3].toInt() and 0xff)}" else "?"
                ui.status("Phone speaks protocol $ver; TLS…")
                tlsStarted = true
                cryptor.beginHandshake()
                sendPlain(ControlMsg.ENCAPSULATED_SSL, cryptor.handshake(ByteArray(0)))
            }
            ControlMsg.ENCAPSULATED_SSL -> {
                val out = cryptor.handshake(m.bodyBytes())
                if (out.isNotEmpty()) sendPlain(ControlMsg.ENCAPSULATED_SSL, out)
                if (cryptor.handshakeDone && !tlsUp) {
                    // Like openauto, we accept any phone certificate.
                    sendPlain(ControlMsg.AUTH_COMPLETE, AuthResponse.newBuilder().setStatus(0).build().toByteArray())
                    tlsUp = true
                    ui.status("Authenticated (${cryptor.cipherSuite}); waiting for the phone…")
                }
            }
            ControlMsg.SERVICE_DISCOVERY_REQUEST -> {
                val req = ServiceDiscoveryRequest.parseFrom(m.bodyBytes())
                phoneName = req.deviceName.ifEmpty { req.labelText }
                send(0, ControlMsg.SERVICE_DISCOVERY_RESPONSE, discovery())
                // Like a car with its hands-free link up: calls can go through it.
                send(0, ControlMsg.CALL_AVAILABILITY_STATUS, aap_protobuf.service.control.message.CallAvailabilityStatusOuterClass
                    .CallAvailabilityStatus.newBuilder().setCallAvailable(true).build())
                ui.status("Connected to $phoneName")
                timer.scheduleWithFixedDelay({
                    if (tlsUp && !closed.get()) send(0, ControlMsg.PING_REQUEST, PingRequest.newBuilder().setTimestamp(System.nanoTime() / 1000).build())
                }, 1, 1, TimeUnit.SECONDS)
            }
            ControlMsg.PING_REQUEST -> send(0, ControlMsg.PING_RESPONSE,
                PingResponse.newBuilder().setTimestamp(PingRequest.parseFrom(m.bodyBytes()).timestamp).build())
            ControlMsg.AUDIO_FOCUS_REQUEST -> {
                val state = when (AudioFocusRequest.parseFrom(m.bodyBytes()).audioFocusType) {
                    AudioFocusRequestType.AUDIO_FOCUS_RELEASE -> AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS
                    AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT, AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK ->
                        AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT
                    else -> AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN
                }
                send(0, ControlMsg.AUDIO_FOCUS_NOTIFICATION, AudioFocusNotification.newBuilder().setFocusState(state).setUnsolicited(false).build())
            }
            ControlMsg.NAV_FOCUS_REQUEST -> send(0, ControlMsg.NAV_FOCUS_NOTIFICATION,
                NavFocusNotification.newBuilder().setFocusType(NavFocusType.NAV_FOCUS_PROJECTED).build())
            ControlMsg.BYEBYE_REQUEST -> {
                send(0, ControlMsg.BYEBYE_RESPONSE, ByeByeResponse.getDefaultInstance())
                close("the phone ended the session")
            }
            ControlMsg.BYEBYE_RESPONSE -> close("disconnected")
            ControlMsg.BATTERY_STATUS_NOTIFICATION -> ui.phone(phoneText.battery(m.bodyBytes()))
        }
    }

    private fun mediaSink(m: AapMessage) {
        val stream = streamOf(m.channel)
        val video = m.channel == chVideo || m.channel == chCluster
        when (m.id) {
            MediaMsg.SETUP -> {
                send(m.channel, MediaMsg.CONFIG, Config.newBuilder().setStatus(Config.Status.STATUS_READY)
                    .setMaxUnacked(if (video) 4 else 8).addConfigurationIndices(0).build())
                if (video) {
                    // Show the projection as soon as the phone is ready, like openauto.
                    send(m.channel, MediaMsg.VIDEO_FOCUS_NOTIFICATION, VideoFocusNotification.newBuilder()
                        .setFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED).setUnsolicited(false).build())
                }
            }
            MediaMsg.START -> {
                sessions[m.channel] = Start.parseFrom(m.bodyBytes()).sessionId
                when (m.channel) {
                    chVideo -> ui.videoStarted(codecSize.second, codecSize.third)
                    chCluster -> ui.clusterStarted(800, 480)
                    else -> audioConfig(m.channel)?.let { ui.audioStart(stream!!, it) }
                }
            }
            MediaMsg.STOP -> if (!video) ui.audioStop(stream!!)
            MediaMsg.VIDEO_FOCUS_REQUEST -> {
                val r = VideoFocusRequestNotification.parseFrom(m.bodyBytes())
                val mode = if (r.mode == VideoFocusMode.VIDEO_FOCUS_NATIVE) VideoFocusMode.VIDEO_FOCUS_NATIVE else VideoFocusMode.VIDEO_FOCUS_PROJECTED
                send(m.channel, MediaMsg.VIDEO_FOCUS_NOTIFICATION, VideoFocusNotification.newBuilder().setFocus(mode).setUnsolicited(false).build())
                if (mode == VideoFocusMode.VIDEO_FOCUS_NATIVE && m.channel == chVideo) ui.status("Phone asked to show the car's own screen")
            }
            MediaMsg.CODEC_CONFIG -> when (m.channel) {
                chVideo -> ui.videoCodecConfig(m.body, m.bodyOffset, m.bodyLength)
                chCluster -> ui.clusterVideo(m.body, m.bodyOffset, m.bodyLength, config = true)
            }
            MediaMsg.DATA -> {
                when (m.channel) {
                    chVideo -> ui.video(m.body, m.bodyOffset + 8, m.bodyLength - 8)
                    chCluster -> ui.clusterVideo(m.body, m.bodyOffset + 8, m.bodyLength - 8, config = false)
                    else -> ui.audio(stream!!, m.body, m.bodyOffset + 8, m.bodyLength - 8)
                }
                send(m.channel, MediaMsg.ACK, Ack.newBuilder().setSessionId(sessions[m.channel] ?: 0).setAck(1).build())
            }
        }
    }

    private fun micSource(m: AapMessage) {
        when (m.id) {
            MediaMsg.SETUP -> send(chMic, MediaMsg.CONFIG, Config.newBuilder().setStatus(Config.Status.STATUS_READY).setMaxUnacked(2).build())
            MediaMsg.MICROPHONE_REQUEST -> {
                val open = MicrophoneRequest.parseFrom(m.bodyBytes()).open
                val session = micSession.incrementAndGet()
                send(chMic, MediaMsg.MICROPHONE_RESPONSE, MicrophoneResponse.newBuilder().setStatus(0).setSessionId(session).build())
                micOpen = open
                if (open) ui.micOpened() else ui.micClosed()
            }
            MediaMsg.ACK -> Unit
        }
    }

    private fun sensorRequest(r: SensorRequest) {
        sensorsWanted += r.type
        send(chSensors, SensorMsg.RESPONSE, SensorResponse.newBuilder().setStatus(MessageStatus.STATUS_SUCCESS).build())
        when (r.type) {
            SensorType.SENSOR_NIGHT_MODE -> setNight(night)
            SensorType.SENSOR_DRIVING_STATUS_DATA, SensorType.SENSOR_GEAR, SensorType.SENSOR_PARKING_BRAKE -> sendDrivingStatus()
            SensorType.SENSOR_LOCATION -> lastFix?.let { location(it) }
            in FakeCar.SENSORS -> fakeCar.start(timer) { if (tlsUp) fakeCar.batch(sensorsWanted, lastFix, driving, night)?.let(::sendBatch) }
            else -> Unit
        }
    }

    private fun navigation(m: AapMessage) {
        when (m.id) {
            NavStatusMsg.STATUS -> ui.navigation("Navigation: ${NavigationStatus.parseFrom(m.bodyBytes()).status.name.lowercase()}")
            NavStatusMsg.TURN_EVENT -> {
                val t = NavigationNextTurnEvent.parseFrom(m.bodyBytes())
                lastTurn = "${t.event.name.lowercase().replace('_', ' ')} ${if (t.turnSide != NavigationNextTurnEvent.TurnSide.UNSPECIFIED) t.turnSide.name.lowercase() else ""} onto ${t.road}".trim()
                ui.navigation(listOf(lastDistance, lastTurn).filter { it.isNotEmpty() }.joinToString(": "))
            }
            NavStatusMsg.DISTANCE_EVENT -> {
                val d = NavigationNextTurnDistanceEvent.parseFrom(m.bodyBytes())
                lastDistance = if (d.distanceMeters >= 1000) "%.1f km".format(d.distanceMeters / 1000.0) else "${d.distanceMeters} m"
                ui.navigation(listOf(lastDistance, lastTurn).filter { it.isNotEmpty() }.joinToString(": "))
            }
        }
    }

    private var lastTurn = ""
    private var lastDistance = ""
    private var nowTitle = ""

    private fun playback(m: AapMessage) {
        when (m.id) {
            MediaPlaybackMsg.METADATA -> {
                val md = MediaPlaybackMetadata.parseFrom(m.bodyBytes())
                nowTitle = listOf(md.song, md.artist).filter { it.isNotEmpty() }.joinToString(" — ")
                ui.nowPlaying(nowTitle)
            }
            MediaPlaybackMsg.STATUS -> {
                val s = MediaPlaybackStatus.parseFrom(m.bodyBytes())
                ui.nowPlaying("${if (s.state == MediaPlaybackStatus.State.PLAYING) "▶" else "⏸"} $nowTitle  (${s.mediaSource})")
            }
        }
    }

    private fun streamOf(ch: Int) = when (ch) {
        chMedia -> AudioStreamType.AUDIO_STREAM_MEDIA
        chGuidance -> AudioStreamType.AUDIO_STREAM_GUIDANCE
        chSystem -> AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO
        else -> null
    }

    private fun audioConfig(ch: Int): AudioConfiguration? = when (ch) {
        chMedia -> pcm(48_000, 2)
        chGuidance, chSystem -> pcm(16_000, 1)
        else -> null
    }

    private fun pcm(rate: Int, channels: Int) = AudioConfiguration.newBuilder().setSamplingRate(rate).setNumberOfBits(16).setNumberOfChannels(channels).build()

    /**
     * The standard video size the phone encodes at: the largest that fits the screen's height, or
     * on a portrait screen (taller than wide, --portrait) the largest portrait one that fits its width.
     */
    private val codecSize: Triple<VideoCodecResolutionType, Int, Int> = when {
        config.height > config.width -> when {
            config.width >= 1440 -> Triple(VideoCodecResolutionType.VIDEO_1440x2560, 1440, 2560)
            config.width >= 1080 -> Triple(VideoCodecResolutionType.VIDEO_1080x1920, 1080, 1920)
            else -> Triple(VideoCodecResolutionType.VIDEO_720x1280, 720, 1280)
        }
        config.height >= 1440 -> Triple(VideoCodecResolutionType.VIDEO_2560x1440, 2560, 1440)
        config.height >= 1080 -> Triple(VideoCodecResolutionType.VIDEO_1920x1080, 1920, 1080)
        config.height >= 720 -> Triple(VideoCodecResolutionType.VIDEO_1280x720, 1280, 720)
        else -> Triple(VideoCodecResolutionType.VIDEO_800x480, 800, 480)
    }

    private fun discovery(): ServiceDiscoveryResponse {
        val res = codecSize.first
        fun audio(id: Int, type: AudioStreamType, rate: Int, ch: Int) = Service.newBuilder().setId(id).setMediaSinkService(
            MediaSinkService.newBuilder().setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM).setAudioType(type).addAudioConfigs(pcm(rate, ch)))
        return ServiceDiscoveryResponse.newBuilder()
            .setHeadunitInfo(HeadUnitInfo.newBuilder().setMake("AARE").setModel("Desktop").setYear("2026")
                .setHeadUnitMake("AARE").setHeadUnitModel("desktop head unit").setHeadUnitSoftwareVersion("1.0"))
            .setDisplayName(config.name)
            .setDriverPosition(if (config.rightHandDrive) DriverPosition.DRIVER_POSITION_RIGHT else DriverPosition.DRIVER_POSITION_LEFT)
            .setConnectionConfiguration(ConnectionConfiguration.newBuilder().setPingConfiguration(
                PingConfiguration.newBuilder().setIntervalMs(1000).setTimeoutMs(3000).setTrackedPingCount(5).setHighLatencyThresholdMs(200)))
            .addChannels(Service.newBuilder().setId(chVideo).setMediaSinkService(MediaSinkService.newBuilder()
                .setAvailableType(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                .addVideoConfigs(VideoConfiguration.newBuilder().setCodecResolution(res)
                    .setFrameRate(if (config.fps60) VideoFrameRateType.VIDEO_FPS_60 else VideoFrameRateType.VIDEO_FPS_30)
                    .setDensity(config.dpi).setWidthMargin(0).setHeightMargin(0))))
            .apply {
                if (config.cluster) addChannels(Service.newBuilder().setId(chCluster).setMediaSinkService(MediaSinkService.newBuilder()
                    .setAvailableType(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                    .setDisplayType(DisplayType.DISPLAY_TYPE_CLUSTER).setDisplayId(1)
                    .addVideoConfigs(VideoConfiguration.newBuilder().setCodecResolution(VideoCodecResolutionType.VIDEO_800x480)
                        .setFrameRate(VideoFrameRateType.VIDEO_FPS_30).setDensity(160).setWidthMargin(0).setHeightMargin(0))))
            }
            .addChannels(audio(chMedia, AudioStreamType.AUDIO_STREAM_MEDIA, 48_000, 2))
            .addChannels(audio(chGuidance, AudioStreamType.AUDIO_STREAM_GUIDANCE, 16_000, 1))
            .addChannels(audio(chSystem, AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO, 16_000, 1))
            .addChannels(Service.newBuilder().setId(chMic).setMediaSourceService(MediaSourceService.newBuilder()
                .setAvailableType(MediaCodecType.MEDIA_CODEC_AUDIO_PCM).setAudioConfig(pcm(16_000, 1))))
            .addChannels(Service.newBuilder().setId(chInput).setInputSourceService(InputSourceService.newBuilder()
                .addAllKeycodesSupported(SUPPORTED_KEYS)
                .apply {
                    // A real rotary/D-pad-only unit declares no touch panel (--no-touch).
                    if (!config.noTouch) addTouchscreen(
                        InputSourceService.TouchScreen.newBuilder().setWidth(config.width).setHeight(config.height))
                }))
            .addChannels(Service.newBuilder().setId(chSensors).setSensorSourceService(SensorSourceService.newBuilder().apply {
                for (t in listOf(SensorType.SENSOR_DRIVING_STATUS_DATA, SensorType.SENSOR_NIGHT_MODE, SensorType.SENSOR_LOCATION,
                    SensorType.SENSOR_SPEED, SensorType.SENSOR_GEAR, SensorType.SENSOR_PARKING_BRAKE) + FakeCar.SENSORS) addSensors(Sensor.newBuilder().setSensorType(t))
                addAllSupportedFuelTypes(FakeCar.FUEL_TYPES)
            }))
            .addChannels(Service.newBuilder().setId(chNav).setNavigationStatusService(NavigationStatusService.newBuilder()
                .setMinimumIntervalMs(500).setType(NavigationStatusService.InstrumentClusterType.ENUM)))
            .addChannels(Service.newBuilder().setId(chPlayback).setMediaPlaybackService(MediaPlaybackStatusService.getDefaultInstance()))
            .addChannels(Service.newBuilder().setId(chPhone).setPhoneStatusService(
                aap_protobuf.service.phonestatus.PhoneStatusServiceOuterClass.PhoneStatusService.getDefaultInstance()))
            .addChannels(Service.newBuilder().setId(chBluetooth).setBluetoothService(BluetoothService.newBuilder().setCarAddress("SKIP_THIS_BLUETOOTH")))
            .build()
    }

    // ------------------------------------------------------------------ sending

    private fun sendPlain(id: Int, body: ByteArray) = synchronized(writeLock) {
        writer.write(0, false, false, AapMessage.plaintext(id, body))
    }

    private fun send(channel: Int, id: Int, body: MessageLite, control: Boolean = false) {
        if (closed.get()) return
        try {
            synchronized(writeLock) { writer.write(channel, control, tlsUp, AapMessage.plaintext(id, body.toByteArray())) }
        } catch (e: Exception) {
            close("send failed: ${e.message}")
        }
    }

    private fun sendRaw(channel: Int, plain: ByteArray) {
        if (closed.get()) return
        try {
            synchronized(writeLock) { writer.write(channel, false, tlsUp, plain) }
        } catch (e: Exception) {
            close("send failed: ${e.message}")
        }
    }

    companion object {
        const val KEYCODE_ROTARY = 65536
        val SUPPORTED_KEYS = listOf(3, 4, 5, 6, 19, 20, 21, 22, 23, 84, 85, 87, 88, 126, 127, 65536, 65537, 65538)
    }
}
