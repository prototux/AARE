package net.prototux.aare.car

import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.control.message.NavFocusTypeOuterClass.NavFocusType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.control.message.DriverPositionOuterClass.DriverPosition
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.location.Location
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.prototux.aare.Settings
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.AapSession
import net.prototux.aare.aap.PhoneDescription
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.PinRefusal
import net.prototux.aare.aap.ServiceChannel
import net.prototux.aare.aap.SessionOptions
import net.prototux.aare.aap.Transport
import net.prototux.aare.aap.channels.AudioChannel
import net.prototux.aare.aap.channels.BluetoothChannel
import net.prototux.aare.aap.channels.InputChannel
import net.prototux.aare.aap.channels.MediaPlaybackChannel
import net.prototux.aare.aap.channels.MicrophoneChannel
import net.prototux.aare.aap.channels.NavigationStatusChannel
import net.prototux.aare.aap.channels.SensorChannel
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.DiagLog
import net.prototux.aare.PinnedHeadUnits
import net.prototux.aare.audio.AudioPolicySource
import net.prototux.aare.audio.CaptureMethod
import net.prototux.aare.audio.CaptureMethods
import net.prototux.aare.audio.CarAudioOut
import net.prototux.aare.audio.PcmSource
import net.prototux.aare.audio.PlaybackCaptureSource
import net.prototux.aare.audio.ShizukuSource
import net.prototux.aare.audio.MicRouter
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.MediaHub
import net.prototux.aare.video.BitrateController
import net.prototux.aare.video.ProjectionDisplay
import net.prototux.aare.video.VideoEncoder
import java.io.ByteArrayOutputStream

/**
 * One connection to a head unit, on the phone: AAP on one side, the car runtime (UI, hosted
 * apps, media) on the other.
 */
class CarSession(
    private val context: Context,
    private val transport: Transport,
    private val identity: PhoneIdentity,
    private val settings: Settings,
    private val media: MediaHub,
    /** Screen-capture consent, if the user gave it: phone audio to the car, and the phone screen. */
    private val projection: () -> MediaProjection?,
    private val wireless: Boolean,
    /** Identifies the head unit among the trusted certificates: Bluetooth address, USB accessory, or "tcp". */
    peerKey: String,
    /** Trust the head unit's first certificate without asking (Bluetooth bonding already was the user's consent). */
    trustOnFirstUse: Boolean,
    private val listener: Listener,
    /** Offer the phone screen to the car; false for the developer TCP server unless opted in, as its peer is unauthenticated. */
    mirror: Boolean = true,
) : AapSession.Callbacks {

    interface Listener {
        fun onStatus(session: CarSession, status: String)
        /** [pinRefusal] is set when the phone refused the head unit's certificate. */
        fun onEnded(session: CarSession, reason: String, certificateRejected: Boolean, pinRefusal: PinRefusal?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val runtime = CarRuntime(context, scope, settings, media, ownsMedia = false, phoneScreenAllowed = mirror)
    private val aap = AapSession(transport, identity, PhoneDescription(
        deviceName = deviceName(), label = "Android", instanceId = settings.instanceId,
        smallIcon = icon(32), mediumIcon = icon(64), largeIcon = icon(128),
    ), this, net.prototux.aare.AndroidAapLog, SessionOptions(sendPings = wireless, pins = PinnedHeadUnits(context), peerKey = peerKey, trustOnFirstUse = trustOnFirstUse))

    private var video: VideoChannel? = null
    private var mic: MicrophoneChannel? = null
    private var navStatus: NavigationStatusChannel? = null
    /** The instrument cluster's video, when the car has a cluster display. */
    private var cluster: ClusterDisplay? = null
    private var playback: MediaPlaybackChannel? = null
    private var phoneStatus: net.prototux.aare.aap.channels.PhoneStatusChannel? = null
    /** Calls, signal, battery and call availability to the car (from onRunning). */
    @Volatile private var phoneBridge: PhoneStatusBridge? = null
    private val audio = ArrayList<AudioChannel>()

    private var encoder: VideoEncoder? = null
    private var display: ProjectionDisplay? = null
    private var touch: TouchMapper? = null
    private val audioOuts = java.util.concurrent.ConcurrentHashMap<AudioStreamType, CarAudioOut>()
    private val micRouter = MicRouter({ mic }, { active -> if (aap.state == AapSession.State.RUNNING) aap.voiceSession(active) })
    @Volatile private var mediaFocus = false
    /** The car lets navigation voice play (but not music). */
    @Volatile private var guidanceFocusState = false
    @Volatile var headUnitName: String = ""
        private set
    @Volatile var videoDescription: String = ""
        private set

    fun start() {
        // The head unit is waiting for our version answer: start the protocol before the UI work.
        aap.start()
        runtime.microphone = { sink, stopped -> micRouter.open(sink, stopped) }
        runtime.projection = projection
        runtime.onTrip = { _, trip -> navBridge?.update(trip) }
        runtime.onPhoneGuidance = { g ->
            if (g != null && !navFocusRequested && aap.state == AapSession.State.RUNNING) { navFocusRequested = true; aap.requestNavFocus(NavFocusType.NAV_FOCUS_PROJECTED) }
            navBridge?.updatePhone(g)
        }
        runtime.start()
        listener.onStatus(this, "Connecting to ${transport.name}")
    }

    fun stop() = aap.stop()

    val rttMs: Long get() = aap.lastRttMs

    // ------------------------------------------------------------------ AAP callbacks (reader thread)

    override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> {
        headUnitName = AapSession.describe(response).substringBefore(" [").ifBlank { response.displayName }
        CarData.discovered(response, owner = this)
        val rhd = response.driverPosition == DriverPosition.DRIVER_POSITION_RIGHT
        main.post {
            runtime.ui.headUnitName = response.displayName.ifBlank { headUnitName }
            listener.onStatus(this, "Connected to $headUnitName")
            settings.rememberCar(runtime.ui.headUnitName)
        }
        val channels = ArrayList<ServiceChannel>()
        for (s in response.channelsList) {
            when {
                s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0 -> {
                    // The main display, and the instrument cluster's if the car has one (display_type CLUSTER).
                    when (s.mediaSinkService.displayType.number) {
                        0 -> if (video == null) video = VideoChannel(s, videoListener(rhd)).also { channels += it }
                        1 -> if (cluster == null) cluster = ClusterDisplay(context, runtime).also { channels += it.open(s) }
                    }
                }
                s.hasMediaSinkService() -> {
                    // The official phone never uses telephony audio (calls stay on Bluetooth HFP).
                    if (s.mediaSinkService.audioType != AudioStreamType.AUDIO_STREAM_TELEPHONY) {
                        AudioChannel(s, audioListener).also { audio += it; channels += it }
                    }
                }
                s.hasMediaSourceService() -> mic = MicrophoneChannel(s, micListener).also { channels += it }
                s.hasInputSourceService() -> InputChannel(s, inputListener).also { ch ->
                    ch.touchScreen?.let { touchSize = it.width to it.height }
                    channels += ch
                }
                s.hasSensorSourceService() -> channels += SensorChannel(s, sensorListener)
                s.hasNavigationStatusService() -> navStatus = NavigationStatusChannel(s).also { channels += it }
                s.hasMediaPlaybackService() -> playback = MediaPlaybackChannel(s).also { channels += it }
                s.hasPhoneStatusService() -> phoneStatus = net.prototux.aare.aap.channels.PhoneStatusChannel(s) { phoneBridge?.onInput(it) }.also { channels += it }
                s.hasBluetoothService() -> channels += BluetoothChannel(s, object : BluetoothChannel.Listener {})
            }
        }
        return channels
    }

    override fun onRunning(session: AapSession) {
        main.post {
            listener.onStatus(this, "Projecting to $headUnitName")
            navStatus?.let { navBridge = NavStatusBridge(it, { m, w, h -> turnImage(m, w, h) }, ::png) }
            navBridge?.updatePhone(runtime.ui.phoneGuidance)
            drivingMode.start()
            net.prototux.aare.notify.CarNotificationListener.sink = { m ->
                drivingMode.onMessage(m.key, m.group)
                if (!m.group || settings.groupMessages) {
                    runtime.remember(m.title, m.text, m.icon, m.actions)
                    if (settings.messageBanners) runtime.notice(m.title, m.text, m.icon, 10_000, m.actions, message = true)
                    if (settings.readMessagesAloud) m.readAloud()
                }
            }
            if (settings.resumeMedia) runtime.resumeMedia()
            startPlaybackStatus()
            phoneBridge = PhoneStatusBridge(context, aap, phoneStatus).also { it.start() }
        }
    }

    override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) {
        val state = notification.focusState
        // Ducking keeps the music going: the head unit lowers it itself.
        mediaFocus = state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN || state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY ||
            state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT || state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK
        guidanceFocusState = state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY ||
            state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK
        when {
            // The car took the audio (radio, a call...): pause the phone's media like Android Auto
            // does, and play again when a transient loss ends.
            notification.unsolicited && (state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS || state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT) -> main.post {
                pausedByCar = state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT && media.playback.value?.playing == true
                media.pause()
            }
            // Only the car handing focus back resumes: a gain answering our own request means
            // something else is playing now, and the old music must stay paused.
            state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN || state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY -> main.post {
                if (pausedByCar) {
                    pausedByCar = false
                    if (notification.unsolicited && media.playback.value?.playing != true) media.playPause()
                }
            }
        }
    }

    /** Media paused by an unsolicited transient focus loss, to resume on the next gain (main thread). */
    private var pausedByCar = false

    override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
        val rejected = session.certificateRejected
        val refusal = session.pinRefusal
        main.post {
            teardown()
            listener.onEnded(this, reason, rejected, refusal)
        }
    }

    // ------------------------------------------------------------------ video

    private fun videoListener(rightHandDrive: Boolean) = object : VideoChannel.Listener {
        override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int =
            chooseVideo(configs, preferred, settings.maxVideoHeight, settings.prefer60fps)

        override fun onVideoStart(index: Int, config: VideoConfiguration) {
            main.post {
                try {
                    startProjection(config, rightHandDrive)
                } catch (e: Exception) {
                    // Never crash the app over one car: end this session and say why.
                    Log.e(TAG, "can't start the projection", e)
                    listener.onStatus(this@CarSession, "Can't start video: ${e.message}")
                    aap.stop()
                }
            }
        }

        override fun onVideoFocus(projected: Boolean) {
            Log.i(TAG, "video focus: ${if (projected) "projected" else "native"} " +
                "ch=${video?.id} started=${video?.started}")
            main.post {
                encoder?.setSuspended(!projected)
                if (projected) encoder?.requestKeyFrame()
            }
        }

        override fun onKeyFrameNeeded() {
            encoder?.requestKeyFrame()
        }

        override fun onUiConfig(config: UiConfig) {
            // New insets or margins: lay the projection out again with them.
            main.post {
                val last = videoConfig ?: return@post
                Log.i(TAG, "UI config update from the head unit")
                try {
                    startProjection(last.toBuilder().setUiConfig(config).build(), rightHandDrive)
                } catch (e: Exception) {
                    Log.e(TAG, "can't apply the new UI config, keeping the old one", e)
                    try {
                        startProjection(last, rightHandDrive)
                    } catch (e: Exception) {
                        Log.e(TAG, "can't restart the projection", e)
                        aap.stop()
                    }
                }
            }
        }
    }

    /** The configuration the projection runs with (main thread), for UI config updates. */
    private var videoConfig: VideoConfiguration? = null

    private fun startProjection(config: VideoConfiguration, rightHandDrive: Boolean) {
        teardownVideo()
        videoConfig = config
        val (w, h) = resolution(config.codecResolution)
        val fps = if (config.frameRate == VideoFrameRateType.VIDEO_FPS_60) 60 else 30
        val dpi = if (config.density > 0) config.density else defaultDensity(minOf(w, h))
        val marginW = config.widthMargin
        val marginH = config.heightMargin
        val insets = config.uiConfig.takeIf { config.hasUiConfig() }?.contentInsets
        videoDescription = "${w}x$h@$fps ${dpi}dpi" + if (marginW + marginH > 0) " margins ${marginW}x$marginH" else ""
        Log.i(TAG, "projection $videoDescription")

        val ch = video ?: return
        val enc = VideoEncoder(w, h, fps, sink = object : VideoEncoder.Sink {
            private var frames = 0
            // Per stream: a restart (new config, encoder error) starts again from the full bitrate.
            private val abr = BitrateController(VideoEncoder.defaultBitrate(w, h, fps)) { encoder?.setBitrate(it) }
            override fun onCodecConfig(data: ByteArray, offset: Int, length: Int) {
                Log.i(TAG, "codec config: $length bytes")
                ch.sendCodecConfig(data, offset, length)
            }
            override fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean) {
                frames++
                if (frames == 1 || keyFrame && frames < 100 || frames % 600 == 0) {
                    Log.i(TAG, "video frame $frames (${if (keyFrame) "key, " else ""}$length bytes), " +
                        "ch=${ch.id} focused=${ch.focused} dropped=${ch.droppedFrames} " +
                        "queued=${ch.diagnosticDataQueued} written=${ch.diagnosticDataWritten} " +
                        "ackMsgs=${ch.diagnosticAckMessages} ackUnits=${ch.diagnosticAckUnits} " +
                        "inFlight=${ch.diagnosticInFlight}/${ch.maxUnacked} " +
                        "waiting=${ch.pendingFrames} ackless=${ch.ackless}")
                }
                ch.sendFrame(data, offset, length, ptsUs, keyFrame) { encoder?.recycle(it) }
                abr.onFrame(ch)
            }
            override fun onError(e: Exception) {
                Log.e(TAG, "encoder failed, restarting", e)
                main.post { startProjection(videoConfig ?: config, rightHandDrive) }
            }
        })
        enc.start()
        encoder = enc

        // The visible area is the video minus its margins (split evenly), minus the UI insets.
        val visibleW = w - marginW - (insets?.let { it.left + it.right } ?: 0)
        val visibleH = h - marginH - (insets?.let { it.top + it.bottom } ?: 0)
        runtime.densityDpi = dpi
        runtime.screenWidthDp = visibleW * 160 / dpi
        runtime.screenHeightDp = visibleH * 160 / dpi
        runtime.rightHandDrive = rightHandDrive
        val padLeft = marginW / 2 + (insets?.left ?: 0)
        val padTop = marginH / 2 + (insets?.top ?: 0)
        val padRight = marginW - marginW / 2 + (insets?.right ?: 0)
        val padBottom = marginH - marginH / 2 + (insets?.bottom ?: 0)
        display = ProjectionDisplay(context, w, h, dpi, enc.inputSurface) {
            val d = LocalDensity.current
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                with(d) {
                    runtime.Root(Modifier.padding(start = padLeft.toDp(), top = padTop.toDp(), end = padRight.toDp(), bottom = padBottom.toDp()))
                }
            }
        }.also { it.show() }
        val inputCh = touchSize
        touch = TouchMapper(inputCh?.first ?: (w - marginW), inputCh?.second ?: (h - marginH), w, h, marginW, marginH)
    }

    private fun teardownVideo() {
        display?.release(); display = null
        encoder?.stop(); encoder = null
    }

    // ------------------------------------------------------------------ input

    @Volatile private var touchSize: Pair<Int, Int>? = null

    private val inputListener = object : InputChannel.Listener {
        override fun onInput(report: InputReport) {
            if (report.hasTouchEvent()) {
                val ev = touch?.map(report.touchEvent) ?: return
                display?.dispatchTouch(ev)
            }
            if (report.hasKeyEvent()) {
                for (k in report.keyEvent.keysList) main.post { onKey(k.keycode, k.down, k.longpress) }
            }
            if (report.hasRelativeEvent()) {
                // Rotary controller: each detent moves the focus.
                for (r in report.relativeEvent.dataList) {
                    val code = if (r.delta > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                    repeat(kotlin.math.abs(r.delta)) {
                        display?.dispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        display?.dispatchKey(KeyEvent(KeyEvent.ACTION_UP, code))
                    }
                }
            }
        }
    }

    private fun onKey(code: Int, down: Boolean, longPress: Boolean) {
        when (code) {
            KeyEvent.KEYCODE_HOME -> if (!down) runtime.actions.goHome()
            KeyEvent.KEYCODE_BACK -> if (!down) runtime.back()
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST -> if (!down) runtime.assistant()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (!down) media.playPause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (!down && runtime.ui.nowPlaying?.playing != true) media.playPause()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> if (!down) media.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (!down) media.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (!down) media.previous()
            KEYCODE_MEDIA -> if (!down) runtime.ui.app(runtime.ui.activeMediaAppId)?.let(runtime.actions::openApp)
            KEYCODE_NAVIGATION -> if (!down) runtime.ui.app(runtime.ui.activeNavAppId)?.let(runtime.actions::openApp)
            else -> display?.dispatchKey(KeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code))
        }
    }

    // ------------------------------------------------------------------ sensors

    /** Do Not Disturb and auto-reply while connected. */
    private val drivingMode = DrivingMode(context, settings)

    /** The car's GPS as the phone's location, so apps don't mix two positions. */
    private val locationFeed = PhoneLocationFeed(context)

    private val sensorListener = object : SensorChannel.Listener {
        override fun onSensors(batch: SensorBatch) {
            CarData.update(batch) // every sensor, for car apps and the assistant
            var carSensors = false
            batch.locationDataList.lastOrNull()?.let { l ->
                runtime.carLocation = Location("car").apply {
                    latitude = l.latitudeE7 / 1e7
                    longitude = l.longitudeE7 / 1e7
                    if (l.hasAccuracyE3()) accuracy = l.accuracyE3 / 1e3f
                    if (l.hasAltitudeE2()) altitude = l.altitudeE2 / 1e2
                    if (l.hasSpeedE3()) speed = l.speedE3 / 1e3f
                    if (l.hasBearingE6()) bearing = l.bearingE6 / 1e6f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }.also { if (settings.carGpsForPhone) locationFeed.push(it) }
                carSensors = true
            }
            batch.compassDataList.lastOrNull()?.let { runtime.carHeading = it.bearingE6 / 1e6f; carSensors = true }
            if (carSensors) runtime.carSensorsChanged()
            batch.nightModeDataList.lastOrNull()?.let { n ->
                main.post { runtime.carNightMode(n.nightMode) }
            }
            batch.drivingStatusDataList.lastOrNull()?.let { d ->
                main.post { runtime.carDriving(d.status != 0) }
            }
        }
    }

    // ------------------------------------------------------------------ audio

    private val audioListener = object : AudioChannel.Listener {
        override fun onAudioReady(channel: AudioChannel, config: aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration) {
            if (!settings.carAudio) return
            if (channel.streamType != AudioStreamType.AUDIO_STREAM_MEDIA && channel.streamType != AudioStreamType.AUDIO_STREAM_GUIDANCE) return
            main.post { startAudio(channel) }
        }
        override fun onUnderflow(channel: AudioChannel) { audioOuts[channel.streamType]?.onUnderflow() }
    }

    /** Capture methods that failed in this session: the next best one is used instead. */
    private val failedCapture = HashSet<CaptureMethod>()
    @Volatile private var mediaPlaying = false
    @Volatile private var guidancePlaying = false

    /**
     * Phone audio to one car channel, with the best capture method available. Android Auto's own
     * method (audio policy) feeds music and navigation voice to their own channels; the others
     * only have one mixed stream, which goes to the media channel.
     */
    private fun startAudio(channel: AudioChannel) {
        val cfg = channel.config ?: return
        val guidance = channel.streamType == AudioStreamType.AUDIO_STREAM_GUIDANCE
        val p = projection()
        val method = CaptureMethods.best(context, p, failedCapture)
        val rate = cfg.samplingRate
        val ch = cfg.numberOfChannels
        val source: PcmSource = when (method) {
            CaptureMethod.AUDIO_POLICY -> {
                // Without a guidance channel in the car, navigation voice goes with the music.
                val hasGuidance = audio.any { it.streamType == AudioStreamType.AUDIO_STREAM_GUIDANCE }
                val usages = when {
                    guidance -> CaptureMethods.GUIDANCE_USAGES
                    hasGuidance -> CaptureMethods.MEDIA_USAGES
                    else -> CaptureMethods.MEDIA_USAGES + CaptureMethods.GUIDANCE_USAGES
                }
                AudioPolicySource(context, usages, rate, ch)
            }
            CaptureMethod.SHIZUKU -> if (guidance) return else ShizukuSource(context, rate, ch)
            CaptureMethod.PLAYBACK_CAPTURE -> if (guidance || p == null) return else PlaybackCaptureSource(p, rate, ch)
            null -> {
                if (!guidance) runtime.notice("Car audio", "Open AARE on the phone and set up car audio to hear the phone in the car")
                return
            }
        }
        if (!guidance) DiagLog.add("car audio: ${method.label}")
        audioOuts.remove(channel.streamType)?.stop()
        audioOuts[channel.streamType] = CarAudioOut(source, channel, if (guidance) guidanceFocus else mediaFocusRequests, onFailed = {
            main.post {
                DiagLog.add("car audio: ${method.label} failed, trying the next method")
                failedCapture += method
                startAudio(channel)
            }
        }).also { it.start() }
    }

    /** Music: full focus while it plays. */
    private val mediaFocusRequests = object : CarAudioOut.Focus {
        override fun request(playing: Boolean) {
            mediaPlaying = playing
            if (playing) aap.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
            else if (!guidancePlaying) aap.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE)
        }
        override val granted: Boolean get() = mediaFocus
    }

    /** Navigation voice: transient focus that ducks the car's other sound, then back to the music if it plays. */
    private val guidanceFocus = object : CarAudioOut.Focus {
        override fun request(playing: Boolean) {
            guidancePlaying = playing
            aap.requestAudioFocus(when {
                playing -> AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK
                mediaPlaying -> AudioFocusRequestType.AUDIO_FOCUS_GAIN
                else -> AudioFocusRequestType.AUDIO_FOCUS_RELEASE
            })
        }
        override val granted: Boolean get() = mediaFocus || guidanceFocusState
    }

    // ------------------------------------------------------------------ microphone

    private val micListener = object : MicrophoneChannel.Listener {
        override fun onMicData(data: ByteArray, offset: Int, length: Int, timestampUs: Long) = micRouter.onCarMic(data, offset, length)
        override fun onMicOpened(ok: Boolean) = micRouter.onCarMicOpened(ok)
    }

    // ------------------------------------------------------------------ status to the head unit

    private var navBridge: NavStatusBridge? = null
    private var navFocusRequested = false

    private fun turnImage(m: androidx.car.app.navigation.model.Maneuver, w: Int, h: Int): ByteArray? {
        val icon = m.icon ?: return null
        val img = runtime.icons.load(icon, maxOf(w, h), runtime.ui.tripPackage) ?: return null
        val bmp = Bitmap.createScaledBitmap(img.asAndroidBitmapCompat(), w, h, true)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun png(bmp: Bitmap, w: Int, h: Int): ByteArray? = runCatching {
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    private fun startPlaybackStatus() {
        val ch = playback ?: return
        scope.launch {
            var lastTitle: String? = null
            media.playback.collectLatest { p ->
                if (p == null) return@collectLatest
                if (p.title != lastTitle) {
                    lastTitle = p.title
                    val meta = MediaPlaybackMetadata.newBuilder().setSong(p.title).setArtist(p.artist).setAlbum(p.album)
                        .setDurationSeconds((p.durationMs / 1000).toInt().coerceAtLeast(0))
                    p.art?.let { art ->
                        val small = Bitmap.createScaledBitmap(art, 256, 256 * art.height / maxOf(1, art.width), true)
                        meta.setAlbumArt(ByteString.copyFrom(ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()))
                    }
                    ch.metadata(meta.build())
                }
                ch.status(MediaPlaybackStatus.newBuilder()
                    .setState(if (p.playing) MediaPlaybackStatus.State.PLAYING else MediaPlaybackStatus.State.PAUSED)
                    .setMediaSource(runtime.ui.app(runtime.ui.activeMediaAppId)?.label ?: p.packageName)
                    .setPlaybackSeconds((p.positionMs / 1000).toInt().coerceAtLeast(0))
                    .build())
            }
        }
        // Ask for navigation focus once a nav app starts guiding.
        runtime.onTrip = { _, trip ->
            if (trip != null && !navFocusRequested) { navFocusRequested = true; aap.requestNavFocus(NavFocusType.NAV_FOCUS_PROJECTED) }
            navBridge?.update(trip)
        }
    }

    // ------------------------------------------------------------------ teardown

    private fun teardown() {
        net.prototux.aare.notify.CarNotificationListener.sink = null
        audioOuts.values.forEach { it.stop() }; audioOuts.clear()
        phoneBridge?.stop(); phoneBridge = null
        locationFeed.stop()
        drivingMode.stop()
        cluster?.stop()
        micRouter.shutdown()
        teardownVideo()
        runtime.stop()
        CarData.reset(owner = this)
        scope.cancel()
    }

    private fun deviceName(): String {
        val m = Build.MODEL
        return if (m.startsWith(Build.MANUFACTURER, ignoreCase = true)) m else "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} $m"
    }

    private fun icon(size: Int): ByteArray? = runCatching {
        val d = context.packageManager.getApplicationIcon(context.packageName)
        val bmp = androidx.core.graphics.drawable.DrawableCompat.wrap(d).let {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            it.setBounds(0, 0, size, size); it.draw(c); b
        }
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    companion object {
        private const val TAG = "AareSession"
        const val KEYCODE_MEDIA = 65537
        const val KEYCODE_NAVIGATION = 65538

        fun resolution(r: VideoCodecResolutionType): Pair<Int, Int> = when (r) {
            VideoCodecResolutionType.VIDEO_800x480 -> 800 to 480
            VideoCodecResolutionType.VIDEO_1280x720 -> 1280 to 720
            VideoCodecResolutionType.VIDEO_1920x1080 -> 1920 to 1080
            VideoCodecResolutionType.VIDEO_2560x1440 -> 2560 to 1440
            VideoCodecResolutionType.VIDEO_3840x2160 -> 3840 to 2160
            VideoCodecResolutionType.VIDEO_720x1280 -> 720 to 1280
            VideoCodecResolutionType.VIDEO_1080x1920 -> 1080 to 1920
            VideoCodecResolutionType.VIDEO_1440x2560 -> 1440 to 2560
            VideoCodecResolutionType.VIDEO_2160x3840 -> 2160 to 3840
        }

        /**
         * Head units should always send a density; when one doesn't, keep the UI readable. By the
         * short side, so a 1080x1920 portrait screen gets the same density as a 1920x1080 one.
         */
        fun defaultDensity(shortSide: Int) = when {
            shortSide >= 1440 -> 320
            shortSide >= 1080 -> 240
            shortSide >= 720 -> 160
            else -> 140
        }

        /**
         * Picks the best configuration the phone can encode comfortably: the largest one within
         * [maxHeight] (short side), preferring 60 fps when asked, among the head unit's preferred
         * indices when it gave any.
         */
        fun chooseVideo(configs: List<VideoConfiguration>, preferred: List<Int>, maxHeight: Int, prefer60: Boolean): Int {
            fun portrait(i: Int) = resolution(configs[i].codecResolution).let { it.second > it.first }
            // A screen has one shape: when a head unit offers both, the shape of its first preferred
            // configuration is the screen's (the other one would be letterboxed or squeezed).
            val candidates = videoCandidates(configs, preferred, TAG).let { all ->
                val shape = portrait(preferred.firstOrNull { it in all } ?: all.firstOrNull() ?: return 0)
                all.filter { portrait(it) == shape }
            }
            fun shortSide(i: Int) = resolution(configs[i].codecResolution).let { minOf(it.first, it.second) }
            fun score(i: Int): Int {
                val c = configs[i]
                val s = shortSide(i)
                val fits = s <= maxHeight
                val fps60 = c.frameRate == VideoFrameRateType.VIDEO_FPS_60
                return (if (fits) 1_000_000 else 0) + (if (fits) s else -s) * 10 + (if (fps60 == prefer60) 1 else 0)
            }
            return candidates.maxByOrNull(::score) ?: 0
        }

        /**
         * The configurations worth choosing from: the head unit's preferred ones, and only those we
         * can encode (H.264 Baseline; no codec given means H.264). With no H.264 one at all, the
         * old choice anyway: better a try than no video.
         */
        fun videoCandidates(configs: List<VideoConfiguration>, preferred: List<Int>, tag: String): List<Int> {
            fun h264(i: Int) = !configs[i].hasVideoCodecType() || configs[i].videoCodecType == MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
            val all = configs.indices.filter { preferred.isEmpty() || it in preferred }.ifEmpty { configs.indices.toList() }
            all.filter(::h264).ifEmpty { configs.indices.filter(::h264) }.let { if (it.isNotEmpty()) return it }
            Log.w(tag, "no H.264 video configuration offered (${configs.map { it.videoCodecType }}), trying one anyway")
            return all
        }
    }
}

private fun androidx.compose.ui.graphics.ImageBitmap.asAndroidBitmapCompat(): Bitmap = asAndroidBitmap()
