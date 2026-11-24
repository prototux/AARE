package net.prototux.aare.car

import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.control.message.NavFocusTypeOuterClass.NavFocusType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.control.message.DriverPositionOuterClass.DriverPosition
import aap_protobuf.service.inputsource.InputSourceServiceOuterClass.InputSourceService.TouchPad
import aap_protobuf.service.inputsource.message.FeedbackEventOuterClass.FeedbackEvent
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.media.sink.message.VideoCodecResolutionTypeOuterClass.VideoCodecResolutionType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.VideoFrameRateTypeOuterClass.VideoFrameRateType
import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.ServiceOuterClass.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.location.Location
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
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
import net.prototux.aare.aap.CertificateRefusal
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
import net.prototux.aare.aap.channels.PassiveChannel
import net.prototux.aare.aap.channels.SensorChannel
import net.prototux.aare.aap.channels.VideoChannel
import net.prototux.aare.aap.channels.WifiProjectionChannel
import net.prototux.aare.BuildConfig
import net.prototux.aare.DiagLog
import net.prototux.aare.KnownHeadUnits
import net.prototux.aare.car.calibration.CalibrationController
import net.prototux.aare.car.calibration.TouchCalibration
import net.prototux.aare.ui.car.CalibrationOverlay
import net.prototux.aare.PinnedHeadUnits
import net.prototux.aare.audio.AudioChannelSink
import net.prototux.aare.audio.AudioPolicySource
import net.prototux.aare.audio.CaptureMethod
import net.prototux.aare.audio.CaptureMethods
import net.prototux.aare.audio.CaptureOrder
import net.prototux.aare.audio.CaptureStatus
import net.prototux.aare.audio.CarAudioOut
import net.prototux.aare.audio.CarMicForApps
import net.prototux.aare.audio.OutputSource
import net.prototux.aare.audio.PcmSource
import net.prototux.aare.audio.PolicySource
import net.prototux.aare.audio.RootAudio
import net.prototux.aare.audio.ShellAudio
import net.prototux.aare.audio.PlaybackCaptureSource
import net.prototux.aare.audio.StartSilence
import net.prototux.aare.audio.MicRouter
import net.prototux.aare.audio.MicTakeover
import net.prototux.aare.audio.PhoneAppsMic
import net.prototux.aare.audio.PhoneRecording
import net.prototux.aare.R
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.MediaHub
import net.prototux.aare.video.BitrateController
import net.prototux.aare.video.ProjectionDisplay
import net.prototux.aare.video.VideoCodecKind
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
    private val peerKey: String,
    /** Trust the head unit's first certificate without asking (Bluetooth bonding already was the user's consent). */
    trustOnFirstUse: Boolean,
    private val listener: Listener,
    /** Offer the phone screen to the car; false for the developer TCP server unless opted in, as its peer is unauthenticated. */
    mirror: Boolean = true,
    /** Answer the version request with the head unit's own version (SessionOptions.echoHeadUnitVersion), for wireless adapters. */
    echoHeadUnitVersion: Boolean = false,
) : AapSession.Callbacks {

    interface Listener {
        fun onStatus(session: CarSession, status: String)
        /**
         * [certificateRefusal] is set when the head unit refused the phone's certificate,
         * [pinRefusal] when the phone refused the head unit's.
         */
        fun onEnded(session: CarSession, reason: String, certificateRefusal: CertificateRefusal?, pinRefusal: PinRefusal?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val runtime = CarRuntime(context, scope, settings, media, ownsMedia = false, phoneScreenAllowed = mirror)
    private val aap = AapSession(transport, identity, PhoneDescription(
        deviceName = deviceName(), label = "Android", instanceId = settings.instanceId,
        smallIcon = icon(32), mediumIcon = icon(64), largeIcon = icon(128),
    ), this, net.prototux.aare.SessionAapLog.also { it.verbose = settings.verboseProtocolLog }, SessionOptions(sendPings = wireless, pins = PinnedHeadUnits(context), peerKey = peerKey, trustOnFirstUse = trustOnFirstUse,
        clockNanos = SystemClock::elapsedRealtimeNanos, echoHeadUnitVersion = echoHeadUnitVersion))

    private var video: VideoChannel? = null
    private var mic: MicrophoneChannel? = null
    private var navStatus: NavigationStatusChannel? = null
    /** The instrument cluster's video, when the car has a cluster display. */
    private var cluster: ClusterDisplay? = null
    private var playback: MediaPlaybackChannel? = null
    private var phoneStatus: net.prototux.aare.aap.channels.PhoneStatusChannel? = null
    /** Calls, signal, battery and call availability to the car (from onRunning). */
    @Volatile private var phoneBridge: PhoneStatusBridge? = null
    /** Answers the car's Bluetooth passkey or PIN while it pairs; null without a Bluetooth service. */
    @Volatile private var btPairing: BluetoothPairing? = null
    /** Moves calls to the phone's speaker while connected (Settings.callsOnPhoneSpeaker); null when off. */
    private var callSpeaker: CallSpeaker? = null
    private val audio = ArrayList<AudioChannel>()

    /**
     * The car channel a kind of audio goes to: the first one the car lists. Some cars offer two of
     * a kind (the Ford Mustang Mach-E has two media and two guidance channels, #74). Feeding both
     * made their captures stop each other (Shizuku records once for the whole phone, and an output
     * replaced by the second channel releases that recording), so the car only got silence.
     */
    private fun isPrimary(channel: AudioChannel) = audio.firstOrNull { it.streamType == channel.streamType } === channel

    // @Volatile: written on main (startProjection), read on the AAP reader thread (input) and the
    // encoder callback thread; without it a restart can leave them seeing a stale display/mapper.
    @Volatile private var encoder: VideoEncoder? = null
    @Volatile private var display: ProjectionDisplay? = null
    @Volatile private var touch: TouchMapper? = null
    /** Overrides for this specific head unit from vehicles.json (density, margins…), matched at discovery. */
    @Volatile private var vehicleOverride: VehicleOverride? = null
    /** Who the head unit said it is, at discovery. */
    @Volatile private var headUnit: HeadUnitIdentity? = null
    /** The video codec matched to this head unit (phone-hardware ∩ car-advertised), chosen at discovery. */
    @Volatile private var videoCodec: VideoCodecKind = VideoCodecKind.H264
    private val audioOuts = java.util.concurrent.ConcurrentHashMap<AudioStreamType, CarAudioOut>()
    private val micRouter = MicRouter({ mic }, { active -> if (aap.state == AapSession.State.RUNNING) aap.voiceSession(active) })
    /** The car's mic for the phone's own apps (Settings.carMicForPhoneApps); null when off or the car has none. */
    private var phoneAppsMic: CarMicForApps? = null
    /** The car's audio focus for the music and navigation voice streams. */
    private val audioFocus = CarAudioFocus({ type, priority -> aap.requestAudioFocus(type, priority) },
        object : CarAudioFocus.Scheduler {
            override fun at(timeMs: Long, task: Runnable) { main.postAtTime(task, timeMs) }
            override fun cancel(task: Runnable) = main.removeCallbacks(task)
        }, SystemClock::uptimeMillis)
    @Volatile var headUnitName: String = ""
        private set
    /** The head unit has answered at all (its version response): this link reaches it. */
    val reachedHeadUnit: Boolean get() = aap.headUnitVersion != null
    @Volatile var videoDescription: String = ""
        private set

    fun start() {
        // The head unit is waiting for our version answer: start the protocol before the UI work.
        aap.start()
        runtime.microphone = { sink, stopped -> micRouter.open(sink, stopped) }
        runtime.projection = projection
        runtime.exitToCar = ::exitToCar
        runtime.onDrivingRestricted = { calibration.onDrivingChanged() }
        runtime.onTrip = { _, trip ->
            guidanceChanged(hosted = true, guiding = trip != null)
            navBridge?.update(trip)
        }
        runtime.onPhoneGuidance = { g ->
            guidanceChanged(hosted = false, guiding = g != null)
            navBridge?.updatePhone(g)
        }
        runtime.start()
        settings.registerListener(carKeyPrefsListener)
        listener.onStatus(this, context.getString(R.string.notif_status_connecting_to, transport.name))
    }

    fun stop() = aap.stop()

    /** Ends this wireless session because the user turned wireless off. */
    fun stopWirelessDisabled() = aap.stopWirelessDisabled()

    /**
     * The session closed before the head unit's version request ever came in: this attempt never
     * reached the version exchange, let alone the handshake, so nothing about it (certificate,
     * trust) was actually tried. Typically the head unit's first try, dropped immediately, before
     * its own link was ready.
     */
    val closedBeforeVersionExchange: Boolean get() = aap.closedDuring == AapSession.State.VERSION

    /** The protocol state the session was in when it closed. */
    val closedDuring: AapSession.State get() = aap.closedDuring

    /** Why the session failed; null when it ended with a bye-bye from either side (or is still running). */
    @Volatile var closeError: Throwable? = null
        private set

    /** The head unit listed its services: the connection itself worked, whatever happens next. */
    val reachedServiceDiscovery: Boolean get() = aap.discovery != null

    /**
     * Asks the head unit to show its own UI (radio, vehicle settings), like Android Auto's "Exit".
     * Focus isn't dropped here: the head unit answers with a native VIDEO_FOCUS_NOTIFICATION (or
     * refuses, and projection carries on), and its projection icon gives the screen back later.
     * Any thread: sends only queue.
     */
    fun exitToCar() {
        val ch = video ?: return
        if (aap.state != AapSession.State.RUNNING || !ch.opened) return
        DiagLog.add("asking the car for its own screen")
        ch.requestNativeFocus()
    }

    val rttMs: Long get() = aap.lastRttMs

    // ------------------------------------------------------------------ AAP callbacks (reader thread)

    /**
     * Who this head unit is, kept by the Bluetooth address it announces, for quirks needed before
     * the next session says it again. Not by the address a wireless session came in on: that can
     * be an adapter that passes the head unit's identity through, and a rule for the head unit
     * must not keep the adapter from connecting. That address is only marked as one wireless works
     * with, which no rule overrides. Head units are looked up this way before wireless starts.
     */
    private fun rememberHeadUnit(response: ServiceDiscoveryResponse, id: HeadUnitIdentity) {
        // The car list's name for it (Settings.rememberCar), so that forgetting the car forgets this.
        val carName = response.displayName.ifBlank { headUnitName }
        val announced = announcedAddress(response)
        val wirelessPeer = peerKey.trim().uppercase().takeIf { wireless && BluetoothAdapter.checkBluetoothAddress(it) }
        runCatching {
            val store = KnownHeadUnits(context)
            if (announced != null) store.put(announced, id, carName) else store.name(peerKey, carName)
            wirelessPeer?.let { store.markWirelessWorked(it, carName) }
            settings.lastHeadUnit = carKey(response)
        }.onFailure { Log.w(TAG, "can't remember the head unit", it) }
    }

    /** The Bluetooth address the head unit announces, if any. */
    private fun announcedAddress(response: ServiceDiscoveryResponse): String? =
        response.channelsList.firstOrNull { it.hasBluetoothService() }?.bluetoothService?.carAddress
            ?.trim()?.uppercase()?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }

    /**
     * The head unit's key in KnownHeadUnits, which keeps its "Car quirks" choices: the Bluetooth
     * address it announces (the same over USB and wireless), else the session's peer key.
     */
    private fun carKey(response: ServiceDiscoveryResponse): String = announcedAddress(response) ?: peerKey.trim()

    override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> {
        headUnitName = AapSession.describe(response).substringBefore(" [").ifBlank { response.displayName }
        val id = HeadUnitIdentity.of(response, headUnitName, session.headUnitVersion)
        headUnit = id
        DiagLog.add("vehicles.json match keys: make \"${id.make}\", model \"${id.model}\", year \"${id.year}\", " +
            "headUnitMake \"${id.headUnitMake}\", headUnitModel \"${id.headUnitModel}\", headUnitBuild \"${id.headUnitBuild}\", " +
            "protocol ${id.protocol?.let { (major, minor) -> "\"$major.$minor\"" } ?: "unknown"}")
        // The per-car vehicles.json match, with this car's "Car quirks" page choices applied on top
        // (VehicleOverrides.effective): the one place this session's quirks are decided.
        vehicleOverride = VehicleOverrides.effective(VehicleOverrides.forHeadUnit(context, id),
            runCatching { KnownHeadUnits(context).quirks(carKey(response)) }.getOrDefault(QuirkSettings()))
        vehicleOverride?.let { DiagLog.add("vehicles.json override for \"$headUnitName\": $it") }
        rememberHeadUnit(response, id)
        calibrationKey = carKey(response)
        calibrationCarName = response.displayName.ifBlank { headUnitName }
        touchCalibration = runCatching { KnownHeadUnits(context).calibration(calibrationKey) }
            .onFailure { Log.w(TAG, "can't read the touch calibration", it) }.getOrNull()
        CarData.discovered(response, owner = this, forceElectric = vehicleOverride?.forceElectric == true)
        val rhd = response.driverPosition == DriverPosition.DRIVER_POSITION_RIGHT
        val quirks = vehicleOverride
        main.post {
            runtime.ui.headUnitName = response.displayName.ifBlank { headUnitName }
            listener.onStatus(this, context.getString(R.string.notif_status_connected_to, headUnitName))
            settings.rememberCar(runtime.ui.headUnitName)
            // "Exit to car" named after the car, or left out (vehicles.json, the built-in car fixes).
            if (quirks?.exitToCar == false) runtime.exitToCar = null
            runtime.exitLabel = ExitLabel.of(quirks?.exitLabel, response.displayName, id.make, carFixes = quirks?.builtInFixes == true)
            runtime.refreshApps()
        }
        val channels = ArrayList<ServiceChannel>()
        // Input services are resolved after the loop, once the main display id is known: a car with
        // a cluster/second screen lists one input service per display, several of them empty.
        val inputServices = ArrayList<Service>()
        var mainDisplayId = 0
        session.sensorChannelFirst = quirks?.sensorChannelFirst == true
        for (s in response.channelsList) {
            val kind = AapSession.serviceKind(s)
            if (quirks?.skips(kind) == true) {
                DiagLog.add("vehicles.json: not opening $kind#${s.id}")
                continue
            }
            when {
                s.hasMediaSinkService() && s.mediaSinkService.videoConfigsCount > 0 -> {
                    // The main display, and the instrument cluster's if the car has one (display_type CLUSTER).
                    when (s.mediaSinkService.displayType.number) {
                        0 -> if (video == null) {
                            mainDisplayId = s.mediaSinkService.displayId
                            videoCodec = pickVideoCodec(s.mediaSinkService.videoConfigsList)
                            video = VideoChannel(s, videoListener(rhd), videoType(videoCodec), allowDropOverflow = quirks?.dropOverflow == true).also { channels += it }
                        }
                        1 -> if (cluster == null) cluster = ClusterDisplay(context, runtime).also { channels += it.open(s, allowDropOverflow = quirks?.dropOverflow == true) }
                    }
                }
                s.hasMediaSinkService() -> {
                    val sink = s.mediaSinkService
                    when {
                        // The official phone never uses telephony audio (calls stay on Bluetooth HFP).
                        sink.audioType == AudioStreamType.AUDIO_STREAM_TELEPHONY -> Unit
                        // An AAC sink is left closed only when a PCM one plays the stream (AudioSinks).
                        AudioSinks.skip(s, response.channelsList) ->
                            DiagLog.add("not using ${sink.audioType.name.removePrefix("AUDIO_STREAM_")} sink #${s.id}: " +
                                "it asks for ${sink.availableType.name.removePrefix("MEDIA_CODEC_AUDIO_")}, the PCM one plays it")
                        else -> {
                            if (sink.availableType != MediaCodecType.MEDIA_CODEC_AUDIO_PCM) {
                                DiagLog.add("${sink.audioType.name.removePrefix("AUDIO_STREAM_")} sink #${s.id} asks for " +
                                    "${sink.availableType.name.removePrefix("MEDIA_CODEC_AUDIO_")} and is the only one: sending it PCM")
                            }
                            AudioChannel(s, audioListener, allowDropOverflow = quirks?.dropOverflow == true).also { audio += it; channels += it }
                        }
                    }
                }
                s.hasMediaSourceService() -> mic = MicrophoneChannel(s, micListener, sendSetup = quirks?.micSetup ?: true).also { channels += it }
                s.hasInputSourceService() -> inputServices += s
                s.hasSensorSourceService() -> channels += SensorChannel(s, sensorListener)
                s.hasNavigationStatusService() -> navStatus = NavigationStatusChannel(s).also { channels += it }
                s.hasMediaPlaybackService() -> playback = MediaPlaybackChannel(s).also { channels += it }
                s.hasPhoneStatusService() -> phoneStatus = net.prototux.aare.aap.channels.PhoneStatusChannel(s) { phoneBridge?.onInput(it) }.also { channels += it }
                s.hasWifiProjectionService() -> channels += WifiProjectionChannel(s)
                s.hasBluetoothService() -> {
                    val carAddress = s.bluetoothService.carAddress.trim().uppercase()
                    channels += BluetoothChannel(s, phoneBtAddress(), object : BluetoothChannel.Listener {
                        override fun onPairingResponse(alreadyPaired: Boolean, ok: Boolean) {
                            DiagLog.add("car bluetooth: pairing ${if (ok) "accepted" else "refused"}${if (alreadyPaired) " (already paired)" else ""}")
                            main.post { maybeAutoPair(carAddress, alreadyPaired) }
                        }
                        override fun onAuthenticationData(authData: String) { btPairing?.onAuthenticationData(authData) }
                    }).also { bluetooth = it; btPairing = BluetoothPairing(context, it, main).apply { start() } }
                }
            }
        }
        // The input service for the projected (main) display: match its display id, require a real
        // non-secondary touch panel, and skip the empty input services a multi-screen unit lists.
        fun realTouch(s: Service) = s.inputSourceService.touchscreenList.any { (it.width > 0 || it.height > 0) && !it.isSecondary }
        val mainInput = inputServices.firstOrNull { it.inputSourceService.displayId == mainDisplayId && realTouch(it) }
            ?: inputServices.firstOrNull(::realTouch)
            ?: inputServices.firstOrNull()
        val hasTouch = mainInput?.let(::realTouch) == true
        main.post { runtime.touchscreen = hasTouch }
        mainInput?.let { s ->
            InputChannel(s, inputListener).also { ch ->
                ch.touchScreen?.let { touchSize = it.width to it.height }
                setUpInput(ch)
                channels += ch
                DiagLog.add("input: display ${s.inputSourceService.displayId} panel ${ch.touchScreen?.let { "${it.width}x${it.height}" } ?: "none"} of ${inputServices.size} service(s)")
            }
        }
        // Services the head unit lists that AARE doesn't speak, opened because a rule says this car wants them open.
        if (quirks?.openServices != null) for (s in response.channelsList) {
            val kind = AapSession.serviceKind(s)
            if (quirks.opens(kind) && !quirks.skips(kind) && channels.none { it.id == s.id }) {
                DiagLog.add("vehicles.json: opening $kind#${s.id}")
                channels += PassiveChannel(s, kind)
            }
        }
        Log.i(TAG, "audio: " + audio.joinToString { ch ->
            "${ch.streamType.name.removePrefix("AUDIO_STREAM_")}#${ch.id} " +
                ch.service.mediaSinkService.audioConfigsList.joinToString("|") { "${it.samplingRate}/${it.numberOfBits}/${it.numberOfChannels}" } +
                if (isPrimary(ch)) "" else " (not used: a second channel of this kind)"
        })
        // Some head units hold back the streams until every channel they listed is open: say which
        // ones AARE leaves closed, so a car that shows nothing can be read from the connection log.
        val unopened = response.channelsList.filter { s -> channels.none { it.id == s.id } }
        if (unopened.isNotEmpty()) DiagLog.add("not opening: " + unopened.joinToString { AapSession.serviceKind(it) + "#" + it.id })
        return channels
    }

    override fun onRunning(session: AapSession) {
        if (vehicleOverride?.pairAgainWhenRunning == true && bluetooth?.announceAgain() == true)
            DiagLog.add("car bluetooth: announcing the phone again, session running (vehicles.json)")
        main.post {
            if (closed) return@post
            listener.onStatus(this, context.getString(R.string.notif_status_projecting_to, headUnitName))
            navStatus?.let { navBridge = NavStatusBridge(it, { m, w, h -> turnImage(m, w, h) }, ::png, turnIcons::png) }
            navBridge?.updatePhone(runtime.ui.phoneGuidance)
            // A route that started before the session was running still gets the focus.
            requestNavFocus()
            drivingMode.start()
            locationSwitch.start(settings.locationOnInCar)
            // Opt-in: engine data from an OBD2 adapter for the Car data viewers (held only while connected).
            if (settings.obdAdapter) net.prototux.aare.obdapp.ObdApp.attach(context)
            if (settings.obdAdapter) net.prototux.aare.obd.ObdReader.start(context, simulated = settings.obdSimulated && BuildConfig.DEBUG, owner = this)
            net.prototux.aare.notify.CarNotificationListener.sink = { m ->
                // An app muted for the car doesn't answer in the driver's name either.
                if (autoReplies(m.packageName, settings.mutedMessageApps)) drivingMode.onMessage(m.key, m.group)
                if (showsMessage(m.packageName, m.group, settings.groupMessages, settings.mutedMessageApps)) {
                    val open = runtime.opener(m.packageName)
                    runtime.remember(m.title, m.text, m.icon, m.actions, open)
                    if (settings.messageBanners) runtime.notice(m.title, m.text, m.icon, 10_000, m.actions, message = true, open = open)
                    if (settings.readMessagesAloud) m.readAloud()
                }
            }
            if (settings.resumeMedia) runtime.resumeMedia()
            startPlaybackStatus()
            phoneBridge = PhoneStatusBridge(context, aap, phoneStatus).also { it.start() }
            if (settings.pauseWhileRecording) context.getSystemService(android.media.AudioManager::class.java)?.registerAudioRecordingCallback(recordings, main)
            if (settings.callsOnPhoneSpeaker) callSpeaker = CallSpeaker(context).also { it.start() }
            if (settings.resumeAfterCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(AudioManager::class.java)?.addOnModeChangedListener(context.mainExecutor, modeChanged)
            }
            applyPhoneAppsMic()
            settings.registerListener(phoneAppsMicSetting)
        }
    }

    /** Turned on or off in the settings while connected: applies straight away, not at the next connection. */
    private val phoneAppsMicSetting = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "car_mic_for_phone_apps") main.post { applyPhoneAppsMic() }
    }

    /** Starts or stops the car's mic for phone apps to match the setting (main thread). */
    private fun applyPhoneAppsMic() {
        if (closed) return
        val on = settings.carMicForPhoneApps
        // Once made, the same instance stays for the session: its one thread orders every on/off.
        val existing = phoneAppsMic
        when {
            existing != null -> existing.setEnabled(on)
            on -> mic?.let { m ->
                val cfg = m.config
                phoneAppsMic = CarMicForApps(context, micRouter, cfg?.samplingRate ?: 16_000, cfg?.numberOfChannels ?: 1).also { it.start() }
            }
        }
    }

    /** Music or a video AARE paused because a phone app records: it plays on once the app is done. */
    private var pausedForRecording = false
    /** When a phone app records and when it is done, a moment's break (a recording moving to the car's mic) not counted. */
    private val recordingPause = MicTakeover()
    private val recordingDone = Runnable {
        if (closed) return@Runnable
        if (recordingPause.onTick(SystemClock.uptimeMillis()) == MicTakeover.Action.CLOSE) main.post(resumeAfterRecording)
        scheduleRecordingDone()
    }

    /** Plays on what a recording paused, once no call or assistant answer runs on the phone. */
    private val resumeAfterRecording: Runnable = Runnable {
        if (closed || !pausedForRecording) return@Runnable
        // An assistant's spoken answer or a call still going: music now would talk over it.
        val mode = context.getSystemService(android.media.AudioManager::class.java)?.mode
        if (mode != null && mode != android.media.AudioManager.MODE_NORMAL) { main.postDelayed(resumeAfterRecording, CALL_CHECK_MS); return@Runnable }
        pausedForRecording = false
        // Already playing (the driver pressed play), or the car's focus loss paused it and its focus answer brings it back.
        if (!pausedByCar && media.playback.value?.playing != true) media.playPause()
    }

    /**
     * A phone app records (a voice message, an assistant listening): what plays would talk over it,
     * so it pauses, and plays on once the app is done (Settings.pauseWhileRecording). Android tells
     * every app about recordings, without their apps' names, so this needs no Shizuku. AARE's own
     * phone microphone (the car app's voice input) counts too: the music pauses for it as well.
     */
    private val recordings = object : android.media.AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
            if (closed) return
            val recording = PhoneAppsMic.anyTakesOver(
                configs.map { PhoneRecording(-1, it.clientAudioSource, Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it.isClientSilenced) },
                emptySet(),
            )
            if (recordingPause.onRecording(recording, SystemClock.uptimeMillis()) == MicTakeover.Action.OPEN) {
                main.removeCallbacks(resumeAfterRecording)
                pausedForRecording = pausedForRecording || media.playback.value?.playing == true
                if (pausedForRecording) media.pause()
            }
            scheduleRecordingDone()
        }
    }

    private fun scheduleRecordingDone() {
        main.removeCallbacks(recordingDone)
        val at = recordingPause.nextTick()
        if (at >= 0) main.postAtTime(recordingDone, at)
    }

    override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) {
        val quirks = vehicleOverride
        audioFocus.onCarFocus(notification, mediaOnGainTransient = quirks?.mediaOnGainTransient == true)
        val state = notification.focusState
        when {
            // The car keeps only navigation voice for now: pause the phone's music, unless a
            // vehicles.json rule says not to, and play it again on the next gain, like the other
            // pauses the car causes.
            notification.unsolicited && state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY &&
                quirks?.pauseOnGuidanceOnly != false -> main.post {
                if (media.playback.value?.playing == true) { pausedByCar = true; pausedForGuidance = true }
                media.pause()
            }
            // The car took the audio (radio, a call...): pause the phone's media like Android Auto
            // does, and play again when a transient loss ends.
            notification.unsolicited && (state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS || state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT) -> main.post {
                val playing = media.playback.value?.playing == true
                pausedByCar = state == AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT && playing
                // A call on the phone, or an assistant listening over Bluetooth, which the car takes
                // for one: it plays on once that ends (see modeChanged), if chosen.
                pausedForCall = settings.resumeAfterCall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && playing && phoneInCall()
                pausedForGuidance = false
                media.pause()
            }
            // Only the car handing focus back resumes: a gain answering our own request means
            // something else is playing now, and the old music must stay paused. Unless it answers
            // the request made when the phone's call ended, or came after a pause for navigation
            // voice: the car often hands the sound back by answering the prompt's own requests, and
            // the music would stay paused for the rest of the drive.
            state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN || state == AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY -> main.post {
                if (pausedByCar || pausedForCall) {
                    val resume = pausedForCall || resumesAfterCarPause(notification.unsolicited, pausedForGuidance)
                    pausedByCar = false
                    pausedForCall = false
                    pausedForGuidance = false
                    if (resume && media.playback.value?.playing != true) media.playPause()
                }
            }
        }
    }

    /**
     * Media paused by the car (an unsolicited transient loss, or the car keeping only navigation
     * voice), to resume on the car's next gain (main thread).
     */
    private var pausedByCar = false
    /** [pausedByCar] by the car keeping only navigation voice (main thread). */
    private var pausedForGuidance = false
    /** Media paused by a focus loss during a call on the phone, to resume once it ends (main thread, Settings.resumeAfterCall). */
    private var pausedForCall = false

    private fun phoneInCall() = context.getSystemService(AudioManager::class.java)?.mode.let { it != null && it != AudioManager.MODE_NORMAL }

    /**
     * The phone's call ended (an assistant listening over Bluetooth counts as one): the car took
     * the media focus for it and doesn't hand it back by itself, so it is asked again. Releasing
     * first makes it switch back from the call, as a retry does. Only with Settings.resumeAfterCall.
     */
    // Lazy: the listener type is Android 12+, and it is only used there.
    private val modeChanged by lazy { AudioManager.OnModeChangedListener { mode ->
        // Only what the car's loss paused: music merely playing on may have lost the car to its radio.
        if (closed || mode != AudioManager.MODE_NORMAL || audioFocus.mediaFocus || !(pausedForCall || pausedByCar)) return@OnModeChangedListener
        // The call may have begun just after the car's loss: what it paused plays on with the answer.
        pausedForCall = pausedForCall || pausedByCar
        audioFocus.askAgainAfterCall()
    } }

    override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
        closeError = error
        val rejected = session.certificateRefusal
        val refusal = session.pinRefusal
        main.post {
            teardown()
            listener.onEnded(this, reason, rejected, refusal)
        }
    }

    // ------------------------------------------------------------------ video

    /** Matches the head unit's advertised video codecs against what this phone can hardware-encode. */
    private fun pickVideoCodec(configs: List<VideoConfiguration>): VideoCodecKind {
        val advertised = configs.map(::configCodec).toSet()
        val kind = matchVideoCodec(advertised, settings.forceH264) { VideoEncoder.hardwareEncodes(it) }
        val line = "video codec: ${kind.label} (car offers ${advertised.joinToString { it.name.removePrefix("MEDIA_CODEC_VIDEO_") }}" +
            (if (settings.forceH264) ", H.264 forced" else "") + ")"
        Log.i(TAG, line)
        DiagLog.add(line)
        return kind
    }

    private fun videoListener(rightHandDrive: Boolean) = object : VideoChannel.Listener {
        override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int {
            offeredVideo = configs
            return chooseVideo(configs, preferred, vehicleOverride?.maxHeight ?: settings.maxVideoHeight,
                vehicleOverride?.prefer60fps ?: settings.prefer60fps, videoType(videoCodec), vehicleOverride?.videoResolutions)
        }

        override fun onVideoStart(index: Int, config: VideoConfiguration) {
            main.post {
                if (closed) return@post
                try {
                    startProjection(config, rightHandDrive)
                    videoFocusAsks = 0
                    main.removeCallbacks(videoFocusCheck)
                    main.postDelayed(videoFocusCheck, VIDEO_FOCUS_RETRY_MS)
                } catch (e: Exception) {
                    // Never crash the app over one car: end this session and say why.
                    Log.e(TAG, "can't start the projection", e)
                    listener.onStatus(this@CarSession, context.getString(R.string.notif_status_cant_start_video, e.message))
                    aap.stop()
                }
            }
        }

        override fun onVideoFocus(projected: Boolean) {
            DiagLog.add("video focus: ${if (projected) "projected" else "native"}")
            main.post {
                if (projected) logNextKeyFrame = true
                encoder?.setSuspended(!projected)
                // The encoder was paused while the car showed its own screen: time the stall check from here.
                lastFrameMs = SystemClock.elapsedRealtime()
                if (projected) {
                    startVideoReports()
                    encoder?.requestKeyFrame()
                    display?.invalidate()
                }
            }
        }

        override fun onKeyFrameNeeded() {
            encoder?.requestKeyFrame()
            display?.invalidate()
        }

        override fun onUiConfig(config: UiConfig, applied: () -> Unit) {
            // New insets or margins: lay the running projection out again with them. The video
            // size, frame rate and codec stay, so the encoder carries on with its stream (no new
            // codec config mid-session), keeping its running encoder.
            main.post {
                if (closed) return@post
                val updated = (videoConfig ?: return@post).toBuilder().setUiConfig(config).build()
                videoConfig = updated
                val (w, h) = resolution(updated.codecResolution)
                Log.i(TAG, "UI config update from the head unit:" + layoutOf(updated, w, h).describe())
                // Not projecting: the next start lays out with it.
                relayOut(updated, rightHandDrive)
                applied()
            }
        }
    }

    /** The configuration the projection runs with (main thread), for UI config updates. */
    private var videoConfig: VideoConfiguration? = null
    /** Every resolution the car offered: touch maps against its largest, not the one chosen (#269). */
    @Volatile private var offeredVideo: List<VideoConfiguration> = emptyList()

    /** Where the UI goes in the frame (main thread). Compose state: a UI config update re-lays it out in place. */
    private val projectionLayout = mutableStateOf<ProjectionLayout?>(null)

    private fun densityOf(config: VideoConfiguration, w: Int, h: Int) =
        vehicleOverride?.dpi ?: if (config.density > 0) config.density else defaultDensity(minOf(w, h))

    /** Where the UI goes: the car's area, or the visible area of the touch calibration saved for it. */
    private fun layoutOf(config: VideoConfiguration, w: Int, h: Int) = carLayoutOf(config, w, h).let { car ->
        touchCalibration?.margins(w, h)?.let { m -> car.withMargins(m[0], m[1], m[2], m[3]) } ?: car
    }

    /** The car's own area: its reported margins, or vehicles.json's. */
    private fun carLayoutOf(config: VideoConfiguration, w: Int, h: Int) = ProjectionLayout.of(config, w, h).let { base ->
        val o = vehicleOverride
        if (o?.widthMargin == null && o?.heightMargin == null) base
        else {
            // vehicles.json overrides the car's reported margins (split evenly, like the official phone).
            val fw = (o.widthMargin ?: (base.marginLeft + base.marginRight)).coerceIn(0, w - 1)
            val fh = (o.heightMargin ?: (base.marginTop + base.marginBottom)).coerceIn(0, h - 1)
            base.copy(marginLeft = fw / 2, marginRight = fw - fw / 2, marginTop = fh / 2, marginBottom = fh - fh / 2)
        }
    }

    /** Size, frame rate and density of the running projection, for [videoDescription]. */
    private var videoFormat = ""
    /** The running projection's pixel aspect (PixelAspect), for [showLayout]. */
    private var pixelAspect = 1f

    /** What the head unit draws itself (clock, signal, battery), so the car UI leaves it out. */
    private fun applyChrome(config: VideoConfiguration) {
        // Here, not at discovery: a UI config update can change what the head unit draws.
        val chrome = CarChrome.drawnByHeadUnit(aap.headUnitAtLeast(4, 3), aap.uiChrome?.bits ?: 0, config.uiConfig.uiElementsList)
        if (chrome != runtime.headUnitChrome) Log.i(TAG, "the head unit draws its own: $chrome")
        runtime.headUnitChrome = chrome
    }

    /** Lays the car UI out in the frame: its size and density, and where touches land. */
    private fun showLayout(layout: ProjectionLayout, dpi: Int, rightHandDrive: Boolean) {
        runtime.densityDpi = dpi
        // Non-square car pixels: laid out on the logical screen the car's pixels stretch back (PixelAspect).
        val (logicalWidth, logicalHeight) = PixelAspect.logicalSize(layout.contentWidth, layout.contentHeight, pixelAspect)
        runtime.screenWidthDp = logicalWidth * 160 / dpi
        runtime.screenHeightDp = logicalHeight * 160 / dpi
        runtime.rightHandDrive = rightHandDrive
        projectionLayout.value = layout
        // Pass the declared panel as-is (0 when the head unit declares none); the mapper resolves
        // the source resolution, including 0x0, visible-pixel and under-declared panels.
        val inputCh = touchSize
        val native = offeredVideo.map(::visibleSize).maxByOrNull { it.first.toLong() * it.second }
        touch = TouchMapper(inputCh?.first ?: 0, inputCh?.second ?: 0, layout,
            native?.first ?: layout.visibleWidth, native?.second ?: layout.visibleHeight, calibration = touchCalibration)
        // The calibration tool works on the frame with the car's own area (a run in progress ends).
        videoConfig?.let { c -> calibration.onProjection(CalibrationController.Projection(layout.width, layout.height, carLayoutOf(c, layout.width, layout.height), touchCalibration)) }
    }

    /** Lays the running projection out again in place (same size, codec and encoder); nothing when not projecting. Main thread. */
    private fun relayOut(config: VideoConfiguration, rightHandDrive: Boolean) {
        if (display == null) return
        val (w, h) = resolution(config.codecResolution)
        val layout = layoutOf(config, w, h)
        showLayout(layout, densityOf(config, w, h), rightHandDrive)
        applyChrome(config)
        videoDescription = videoFormat + layout.describe()
        display?.invalidate() // a frame with the new layout, which a UI config reply waits for
    }

    // ------------------------------------------------------------------ touch calibration

    /** The head unit's key (carKey) and the car list's name for it, set at discovery. */
    @Volatile private var calibrationKey = ""
    @Volatile private var calibrationCarName = ""
    /** The touch calibration saved for this head unit, or null for the automatic mapping. */
    @Volatile private var touchCalibration: TouchCalibration? = null

    private val calibration = CalibrationController(object : CalibrationController.Host {
        override val car get() = runtime.ui.headUnitName.ifBlank { headUnitName }
        override val driving get() = runtime.ui.drivingRestricted
        override fun apply(c: TouchCalibration?) {
            runCatching { KnownHeadUnits(context).setCalibration(calibrationKey, c, calibrationCarName) }
                .onFailure { Log.w(TAG, "can't save the touch calibration", it) }
            touchCalibration = c
            // In place: the saved area moves the UI, the mapper takes the calibration.
            videoConfig?.let { if (!closed) relayOut(it, runtime.rightHandDrive) }
        }
    }, CalibrationController.Scheduler.of(main))

    private fun startProjection(config: VideoConfiguration, rightHandDrive: Boolean) {
        teardownVideo()
        videoConfig = config
        val (w, h) = resolution(config.codecResolution)
        val fps = if (config.frameRate == VideoFrameRateType.VIDEO_FPS_60) 60 else 30
        val dpi = densityOf(config, w, h)
        val layout = layoutOf(config, w, h)
        // Non-square car pixels: the UI is laid out on a logical screen that the car's pixels
        // stretch back to the right shape (PixelAspect).
        val aspect = PixelAspect.factor(vehicleOverride?.pixelAspect, config.pixelAspectRatioE4.takeIf { config.hasPixelAspectRatioE4() },
            aap.headUnitVersion, headUnit?.make.orEmpty(), useReported = vehicleOverride?.reportedPixelAspect == true)
        pixelAspect = aspect
        videoFormat = "${w}x$h@$fps ${dpi}dpi" + (if (aspect != 1f) " pixel aspect $aspect" else "")
        videoDescription = videoFormat + layout.describe()
        net.prototux.aare.SessionAapLog.i("projection $videoDescription")
        touchCalibration?.let { DiagLog.add("calibration: using the one saved for ${calibrationCarName}: ${it.describe()}") }

        val ch = video ?: return
        // lateinit: the sink below needs to know whether its encoder is still the current one.
        lateinit var enc: VideoEncoder
        enc = VideoEncoder(w, h, fps, kind = videoCodec, constantBitrate = settings.constantBitrateVideo,
            headersOnce = vehicleOverride?.videoHeadersOnce == true, sink = object : VideoEncoder.Sink {
            private var frames = 0
            // Per stream: a restart (new config, encoder error) starts again from the full bitrate.
            private val abr = BitrateController(VideoEncoder.defaultBitrate(w, h, fps)) { encoder?.setBitrate(it) }
            override fun onCodecConfig(data: ByteArray, offset: Int, length: Int) {
                // The stream's profile, level and size (SPS, PPS): what a car's decoder may refuse.
                net.prototux.aare.SessionAapLog.i("video codec config, $length bytes: " + hex(data, offset, minOf(length, 64)))
                ch.sendCodecConfig(data, offset, length)
            }
            override fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyFrame: Boolean) {
                frames++
                lastFrameMs = SystemClock.elapsedRealtime()
                if (frames == 1 || keyFrame && (frames < 100 || logNextKeyFrame) || frames % 600 == 0) {
                    // In the verbose log: whether frames flow, and whether the car had given focus (a black screen).
                    if (keyFrame) logNextKeyFrame = false
                    net.prototux.aare.SessionAapLog.i("video frame $frames (${if (keyFrame) "key, " else ""}$length bytes), focused=${ch.focused}, dropped=${ch.droppedFrames}")
                }
                ch.sendFrame(data, offset, length, ptsUs, keyFrame) { encoder?.recycle(it) }
                abr.onFrame(ch)
            }
            override fun onError(e: Exception) {
                net.prototux.aare.SessionAapLog.e("video encoder failed, restarting", e)
                main.post {
                    // The session ended, or this encoder was already replaced: nothing to restart.
                    if (closed || encoder !== enc) return@post
                    try {
                        if (!encoderRestarts.allow()) throw IllegalStateException("the encoder keeps failing", e)
                        startProjection(videoConfig ?: config, rightHandDrive)
                    } catch (failure: Exception) {
                        // Same as a failed first start: end the session (onClosed tears it all down).
                        Log.e(TAG, "can't restart the projection", failure)
                        listener.onStatus(this@CarSession, context.getString(R.string.notif_status_cant_start_video, failure.message))
                        aap.stop()
                    }
                }
            }
        })
        enc.start()
        encoder = enc

        showLayout(layout, dpi, rightHandDrive)
        applyChrome(config)
        // Assigned before show(), so that a failing show() still has teardownVideo() release the display.
        val pd = ProjectionDisplay(context, w, h, dpi, enc.inputSurface) {
            val d = LocalDensity.current
            val l = projectionLayout.value ?: layout
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                with(d) {
                    runtime.Root(Modifier.padding(start = l.padLeft.toDp(), top = l.padTop.toDp(), end = l.padRight.toDp(), bottom = l.padBottom.toDp())
                        .pixelAspect(aspect))
                }
                // Over the whole frame, margins included: its first step moves them.
                CalibrationOverlay(Modifier.fillMaxSize())
            }
        }
        display = pd
        pd.show()
        lastFrameMs = SystemClock.elapsedRealtime()
        main.removeCallbacks(stallCheck)
        main.postDelayed(stallCheck, STALL_CHECK_MS)
        // A restarted stream (encoder error, new layout) while the car shows it.
        if (ch.focused) startVideoReports()
    }

    /** An encoder that fails again and again ends the session rather than restart forever. */
    private val encoderRestarts = RestartLimiter(MAX_RESTARTS, RESTART_WINDOW_MS)

    /** When the encoder last produced a frame (elapsedRealtime), for [stallCheck]. */
    @Volatile private var lastFrameMs = 0L
    private var stalled = false

    /**
     * Keeps frames coming on a still car screen. The encoder is asked to repeat the last frame
     * (KEY_REPEAT_PREVIOUS_FRAME_AFTER), but some encoders stop producing anything once the
     * picture doesn't change, and a head unit that gets no frames shows black. While the car shows
     * AARE's screen and nothing came out for [STALL_MS], the screen is redrawn, which gives the
     * encoder a new buffer. Main thread; runs while there is a projection.
     */
    private val stallCheck = object : Runnable {
        override fun run() {
            if (closed || display == null) return
            val quiet = SystemClock.elapsedRealtime() - lastFrameMs
            if (video?.focused == true && quiet > STALL_MS) {
                if (!stalled) DiagLog.add("video: no frame from the encoder for $quiet ms; redrawing the car screen")
                stalled = true
                display?.invalidate()
            } else if (quiet <= STALL_MS) {
                stalled = false
            }
            main.postDelayed(this, STALL_CHECK_MS)
        }
    }

    /** The next key frame goes to the log: the one sent because the car gave its screen (again). */
    @Volatile private var logNextKeyFrame = false
    private var videoReports = 0
    @Volatile private var touches = 0

    /** Verbose log: how the car takes the video, more often right after it gave its screen. Main thread. */
    private val videoReport = object : Runnable {
        override fun run() {
            if (closed || display == null) return
            logVideo()
            main.postDelayed(this, videoReportDelayMs(++videoReports))
        }
    }

    private fun startVideoReports() {
        videoReports = 0
        main.removeCallbacks(videoReport)
        main.postDelayed(videoReport, videoReportDelayMs(0))
    }

    private fun logVideo() {
        video?.let { net.prototux.aare.SessionAapLog.i("video: ${it.closingReport ?: it.report()}, $touches touches from the car") }
    }

    private fun teardownVideo() {
        main.removeCallbacks(stallCheck)
        main.removeCallbacks(videoReport)
        if (display != null) logVideo()
        display?.release(); display = null
        encoder?.stop(); encoder = null
    }

    // ------------------------------------------------------------------ input

    @Volatile private var touchSize: Pair<Int, Int>? = null

    /** The car's input channel, for input feedback. */
    @Volatile private var input: InputChannel? = null
    /** Focus navigation by the car's touchpad, when the car says its touchpad is for that. */
    @Volatile private var touchpad: TouchpadNavigator? = null

    /** Keeps the car's input channel and sets up its touchpad, if it has one. */
    private fun setUpInput(ch: InputChannel) {
        input = ch
        val pad = ch.touchPad ?: return
        // A touchpad is used only to move the focus, and only one the car marks for
        // UI navigation; another pad's events go to apps that read touchpads, which AARE has none of.
        val line = if (pad.uiNavigation) {
            val thresholds = TouchpadThresholds.of(pad)
            touchpad = TouchpadNavigator(thresholds) { code -> display?.moveFocus(code) == true }
            "focus navigation, step ${thresholds.move}/${thresholds.multiMove}"
        } else "not for UI navigation: ignored"
        val physical = if (pad.physicalWidth != 0 && pad.physicalHeight != 0) " (${pad.physicalWidth}x${pad.physicalHeight} mm)" else ""
        Log.i(TAG, "input: touchpad ${pad.width}x${pad.height}$physical sensitivity ${pad.sensitivity}: $line")
        DiagLog.add("car touchpad ${pad.width}x${pad.height}$physical: $line")
    }

    /**
     * The car changed its touchpad's sensitivity during the session (reader thread). The navigator
     * is kept, so a swipe under way goes on; its steps change on the main thread, where it runs.
     */
    private fun onTouchpadUpdate(pad: TouchPad) {
        if (!pad.uiNavigation || touchpad == null) return
        val thresholds = TouchpadThresholds.of(pad)
        main.post { touchpad?.thresholds = thresholds }
        DiagLog.add("car touchpad sensitivity ${pad.sensitivity}: step ${thresholds.move}/${thresholds.multiMove}")
    }

    /** A touchpad report (reader thread). */
    private fun onTouchpad(ev: TouchEvent) {
        val nav = touchpad ?: return
        // Drop a report without pointers or naming a pointer it doesn't carry.
        if (ev.pointerDataCount == 0 || ev.actionIndex >= ev.pointerDataCount) return
        val p = ev.getPointerData(0)
        val action = ev.action.number
        main.post {
            if (closed || display == null) return@post
            // The car can click or buzz on each step.
            if (nav.onEvent(action, p.x.toFloat(), p.y.toFloat())) input?.sendFeedback(FeedbackEvent.FEEDBACK_FOCUS_CHANGE)
        }
    }

    /** The car's keys, with metastate and long presses injected on the main thread. */
    private val keys = KeyInjector(ViewConfiguration.getLongPressTimeout().toLong(), object : KeyInjector.Scheduler {
        override fun postDelayed(task: Runnable, delayMs: Long) { main.postDelayed(task, delayMs) }
        override fun cancel(task: Runnable) = main.removeCallbacks(task)
        override fun uptimeMillis() = SystemClock.uptimeMillis()
    }, carLongPressOnly = setOf(KeyEvent.KEYCODE_HOME), emit = ::onKey)

    private val inputListener = object : InputChannel.Listener {
        override fun onTouchPadUpdate(pad: TouchPad) = onTouchpadUpdate(pad)

        override fun onInput(report: InputReport) {
            if (report.hasTouchEvent() && calibration.capturing) {
                // The calibration tool takes the raw touches; the car UI gets none meanwhile.
                calibration.onTouchFromReader(report.touchEvent)
            } else if (report.hasTouchEvent()) {
                if (touches++ == 0) net.prototux.aare.SessionAapLog.i("first touch from the car")
                val ev = touch?.map(report.touchEvent) ?: return
                display?.dispatchTouch(ev)
            }
            if (report.hasKeyEvent()) {
                for (k in report.keyEvent.keysList) {
                    Log.d(TAG, "input key: code=${k.keycode} down=${k.down} meta=${k.metastate} long=${k.longpress}")
                    main.post { if (!closed) keys.onKey(k.keycode, k.down, k.metastate, k.longpress) }
                }
            }
            if (report.hasRelativeEvent()) {
                for (r in report.relativeEvent.dataList) {
                    Log.d(TAG, "input rotary: keycode=${r.keycode} delta=${r.delta}")
                }
                // Rotary controller: each detent moves focus to the next/previous element in layout
                // order, like Android Auto. Compose maps Tab to "focus next" and Shift+Tab to
                // "focus previous" (not D-pad up/down, which is 2D spatial and wrong for a knob).
                for (r in report.relativeEvent.dataList) {
                    val meta = if (r.delta < 0) KeyEvent.META_SHIFT_ON else 0
                    repeat(kotlin.math.abs(r.delta)) {
                        val t = SystemClock.uptimeMillis()
                        display?.dispatchKey(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, meta))
                        display?.dispatchKey(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, meta))
                    }
                }
            }
            if (report.hasTouchpadEvent()) onTouchpad(report.touchpadEvent)
        }
    }

    /** HOME goes to the dashboard; held, it hands the screen back to the car. */
    private val homeKey = LongPressKey(onPress = { runtime.actions.goHome() }, onLongPress = ::exitToCar)

    /**
     * `Settings.carKeyMap` and the codes of `Settings.carKeysSeen`, kept here: [onKey] runs on
     * every press and every release, and decoding both string sets each time is waste. Refreshed
     * when the "Car buttons" chooser changes them (main thread only).
     */
    private var carKeyMap: Map<Int, String> = settings.carKeyMap
    private var carKeysSeen: Set<Int> = settings.carKeysSeen.keys
    private val carKeyPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "car_key_map" -> main.post { carKeyMap = settings.carKeyMap }
            "car_keys_seen" -> main.post { carKeysSeen = settings.carKeysSeen.keys }
        }
    }

    private fun onKey(key: CarKey) {
        val code = key.code
        val down = key.down
        val longPress = key.longPress
        // For the "Car buttons" chooser: every key the car has ever sent, by a readable name.
        if (code !in carKeysSeen) {
            carKeysSeen = carKeysSeen + code
            settings.rememberCarKey(code, KeyEvent.keyCodeToString(code))
        }
        // Some head units send a media key while they set up, before AARE has a car screen (a
        // play as the session starts): acting on it would start the music the driver didn't ask
        // for, and over whatever the car was playing. The driver's own presses come once the
        // screen is up.
        if (display == null && code in MEDIA_KEYS) {
            if (!down) DiagLog.add("ignoring ${KeyEvent.keyCodeToString(code)} from the car: no car screen yet")
            return
        }
        // The car's buttons, in the connection log: which ones reach the phone at all (the voice
        // button on some cars only on a long press, or not during projection). Not the rotary
        // knob's steps or the touchpad's moves, which come by the dozen.
        if (!down && logsCarKey(code)) DiagLog.add("car key ${KeyEvent.keyCodeToString(code)}${if (longPress) ", held" else ""}")
        // A user remap wins over the key's normal behaviour below (never over the guard above:
        // that's about not acting before there's a screen at all, remapped or not).
        resolveCarKeyAction(carKeyMap, code)?.let { action ->
            if (!down) performCarKeyAction(action)
            return
        }
        when (code) {
            KeyEvent.KEYCODE_HOME -> homeKey.onKey(down, key.longPress)
            KeyEvent.KEYCODE_BACK -> if (!down) back()
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST -> if (!down) runtime.assistant()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (!down) media.playPause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (!down && runtime.ui.nowPlaying?.playing != true) media.playPause()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> if (!down) { pausedForRecording = false; media.pause() }
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (!down) media.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (!down) media.previous()
            KEYCODE_MEDIA -> if (!down) runtime.ui.app(runtime.ui.activeMediaAppId)?.let(runtime.actions::openApp)
            KEYCODE_NAVIGATION, KEYCODE_TURN_CARD -> if (!down) runtime.ui.app(runtime.ui.activeNavAppId)?.let(runtime.actions::openApp)
            // The car's dedicated Phone button opens the phone app; the Call/Endcall keys answer and hang up.
            KEYCODE_TEL -> if (!down) runtime.ui.app(CarRuntime.PHONE_ID)?.let(runtime.actions::openApp)
            KeyEvent.KEYCODE_CALL -> if (!down) { if (runtime.ui.call?.ringing == true) runtime.actions.answerCall() else runtime.ui.app(CarRuntime.PHONE_ID)?.let(runtime.actions::openApp) }
            KeyEvent.KEYCODE_ENDCALL -> if (!down) runtime.actions.endCall()
            // The Radio button hands the screen back to the car's own source (radio, media…), like "exit".
            KEYCODE_RADIO -> if (!down) exitToCar()
            else -> dispatchCarKey(key)
        }
    }

    /** A key for the car screen itself: the D-pad, Enter, letters... */
    private fun dispatchCarKey(key: CarKey) {
        val d = display ?: return
        val flags = if (key.down && key.longPress) KeyEvent.FLAG_LONG_PRESS else 0
        d.dispatchKey(KeyEvent(key.downTime, SystemClock.uptimeMillis(),
            if (key.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, key.code,
            key.repeat, key.metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))
        // With a touchpad for navigation, the car is asked for its select feedback.
        if (key.code == KeyEvent.KEYCODE_DPAD_CENTER && !key.down && touchpad != null) input?.sendFeedback(FeedbackEvent.FEEDBACK_SELECT)
    }

    /** A remapped key's action (see [resolveCarKeyAction]), the same ones the keys above reach by default. */
    private fun performCarKeyAction(action: CarKeyAction) {
        when (action) {
            CarKeyAction.HOME -> runtime.actions.goHome()
            CarKeyAction.BACK -> back()
            CarKeyAction.ASSISTANT -> runtime.assistant()
            CarKeyAction.PLAY_PAUSE -> media.playPause()
            CarKeyAction.NEXT -> media.next()
            CarKeyAction.PREVIOUS -> media.previous()
            CarKeyAction.OPEN_NAVIGATION -> runtime.ui.app(runtime.ui.activeNavAppId)?.let(runtime.actions::openApp)
            CarKeyAction.OPEN_MEDIA -> runtime.ui.app(runtime.ui.activeMediaAppId)?.let(runtime.actions::openApp)
            CarKeyAction.OPEN_PHONE -> runtime.ui.app(CarRuntime.PHONE_ID)?.let(runtime.actions::openApp)
            CarKeyAction.NOTHING -> Unit
        }
    }

    /**
     * The car's Back key. A Compose BackHandler on the car screen (the media browser going up a
     * folder) comes first, as it would for the phone's own Back; then the runtime's back stack.
     * The assistant overlay closes before anything under it handles Back.
     */
    private fun back() {
        if (runtime.ui.assistantAppId == null && display?.back() == true) return
        runtime.back()
    }

    // ------------------------------------------------------------------ sensors

    /** Do Not Disturb and auto-reply while connected. */
    private val drivingMode = DrivingMode(context, settings)

    /** The car's GPS as the phone's location, so apps don't mix two positions. */
    private val locationFeed = PhoneLocationFeed(context)

    /** The phone's location on when connected and off afterwards, each if chosen. */
    private val locationSwitch = PhoneLocationSwitch(context)

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
                }.also {
                    // Turned off mid-drive: give the phone its own GPS back now, not at the disconnect.
                    if (settings.carGpsForPhone) locationFeed.push(it) else locationFeed.stop()
                }
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
            if (!isPrimary(channel)) return
            main.post { startAudio(channel) }
        }
        override fun onUnderflow(channel: AudioChannel) { if (isPrimary(channel)) audioOuts[channel.streamType]?.onUnderflow() }
    }

    /** Capture methods that failed in this session: the next best one is used instead. */
    private val failedCapture = HashSet<CaptureMethod>()

    /**
     * Phone audio to one car channel, with the best capture method available. Android Auto's own
     * method (audio policy) feeds music and navigation voice to their own channels; the others
     * only have one mixed stream, which goes to the media channel.
     */
    private fun startAudio(channel: AudioChannel) {
        if (closed) return
        val cfg = channel.config ?: return
        val guidance = channel.streamType == AudioStreamType.AUDIO_STREAM_GUIDANCE
        val singleChannel = vehicleOverride?.singleChannelCapture == true
        val p = projection()
        val method = when (val plan = CaptureOrder.plan(CaptureMethods.best(context, p, failedCapture), guidance, singleChannel)) {
            CaptureOrder.Plan.Skip -> return
            CaptureOrder.Plan.NoMethod -> {
                publishCapture(active = null)
                // Screen capture is all that's left, and Android only asks for it on the phone.
                val onlyCapture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && p == null && !CaptureMethods.env(context, false).let { it.root || it.shizuku || it.privileged }
                runtime.notice(context.getString(R.string.audio_settings_title),
                    context.getString(if (onlyCapture) R.string.audio_notice_allow_capture else R.string.audio_notice_setup_hint))
                return
            }
            is CaptureOrder.Plan.Capture -> plan.method
        }
        // The music channel's method is the one the car audio screen shows.
        if (!guidance) publishCapture(active = method)
        val rate = cfg.samplingRate
        val ch = cfg.numberOfChannels
        val source: PcmSource = when (method) {
            CaptureMethod.AUDIO_POLICY, CaptureMethod.SHIZUKU_POLICY, CaptureMethod.ROOT_POLICY -> {
                val usages = CaptureOrder.policyUsages(guidance, singleChannel,
                    carHasGuidance = audio.any { it.streamType == AudioStreamType.AUDIO_STREAM_GUIDANCE })
                when (method) {
                    CaptureMethod.AUDIO_POLICY -> AudioPolicySource(context, usages, rate, ch, captureVolume())
                    CaptureMethod.SHIZUKU_POLICY -> PolicySource(context, ShellAudio, usages, rate, ch, captureVolume())
                    else -> PolicySource(context, RootAudio, usages, rate, ch, captureVolume())
                }
            }
            CaptureMethod.SHIZUKU -> OutputSource(context, if (CaptureMethods.rootAvailable(context)) RootAudio else ShellAudio, rate, ch)
            CaptureMethod.PLAYBACK_CAPTURE -> PlaybackCaptureSource(p ?: return, rate, ch)
        }
        DiagLog.add(if (guidance) "car audio, navigation channel (${rate} Hz, $ch ch): ${method.label}" else "car audio: ${method.label}")
        audioOuts.remove(channel.streamType)?.stop()
        lateinit var out: CarAudioOut
        val silence = StartSilence.configured(wireless, guidance, aap.headUnitVersion?.first,
            vehicleOverride?.wifiMediaStartSilence, vehicleOverride?.wifiGuidanceStartSilence)
        out = CarAudioOut(source, AudioChannelSink(channel), if (guidance) audioFocus.guidance else audioFocus.media, silence, onFailed = {
            main.post {
                // Only if it's still this stream's output: a late failure (a slow Shizuku bind
                // giving up after teardown, or after a replacement) must not start a capture
                // nothing would ever stop.
                if (!audioOuts.remove(channel.streamType, out)) return@post
                DiagLog.add("car audio: ${method.label} failed, trying the next method")
                failedCapture += method
                captureFailed(method, source.failure)
                startAudio(channel)
                // Split capture gone: the other channel goes to the next method too (the mixed
                // stream needs the media channel alone, or nav voice would play twice).
                if (method in CaptureOrder.FAILURE_MOVES_BOTH) {
                    audio.filter { it.streamType != channel.streamType && isPrimary(it) && audioOuts.containsKey(it.streamType) }.forEach { other ->
                        audioOuts.remove(other.streamType)?.stop()
                        startAudio(other)
                    }
                }
            }
        }, onStarted = { main.post { captureWorks(method) } })
        audioOuts[channel.streamType] = out.also { it.start() }
    }

    /** The car audio screen's view of this session (CaptureStatus.live), main thread. */
    private fun publishCapture(active: CaptureMethod?) {
        if (closed) return
        CaptureStatus.live.value = (CaptureStatus.live.value ?: CaptureStatus.Live(null)).copy(active = active)
    }

    /** [method] failed for [why]: shown now, and remembered for this phone (main thread). */
    private fun captureFailed(method: CaptureMethod, why: String?) {
        CaptureStatus.live.value = (CaptureStatus.live.value ?: CaptureStatus.Live(null)).let { it.copy(failed = it.failed + (method to why)) }
        settings.captureRefusals = settings.captureRefusals + (method to CaptureStatus.Refusal(why, System.currentTimeMillis()))
    }

    /** [method] started: whatever this phone refused before, it works now (main thread). */
    private fun captureWorks(method: CaptureMethod) {
        val refused = settings.captureRefusals
        if (method in refused) settings.captureRefusals = refused - method
    }

    // The head unit normally answers the focus request sent with START. Some setups (Mazda with an
    // AAWireless dongle) sometimes never do, and the car screen stays black until the driver
    // switches to the car's own screen and back: ask again a few times while it has said nothing.
    private var videoFocusAsks = 0
    private val videoFocusCheck = object : Runnable {
        override fun run() {
            val ch = video ?: return
            if (closed || ch.focusHeard) return
            if (videoFocusAsks >= VIDEO_FOCUS_RETRIES) {
                DiagLog.add("video focus: the car never answered")
                return
            }
            videoFocusAsks++
            DiagLog.add("video focus: no answer from the car, asking again ($videoFocusAsks/$VIDEO_FOCUS_RETRIES)")
            ch.requestProjectedFocus()
            main.postDelayed(this, VIDEO_FOCUS_RETRY_MS)
        }
    }

    // ------------------------------------------------------------------ microphone

    private val micListener = object : MicrophoneChannel.Listener {
        override fun onMicData(data: ByteArray, offset: Int, length: Int, timestampUs: Long) = micRouter.onCarMic(data, offset, length)
        override fun onMicOpened(ok: Boolean) = micRouter.onCarMicOpened(ok)
    }

    // ------------------------------------------------------------------ status to the head unit

    private var navBridge: NavStatusBridge? = null
    private val turnIcons = TurnIcons()
    // Guarded by navLock: trips, phone guidance and onRunning come from different threads.
    private val navLock = Any()
    private var hostedGuiding = false
    private var phoneGuiding = false
    /**
     * A route started and the car wasn't asked for projected nav focus yet. Once per route: a
     * driver who switched to the car's own navigation mid-route isn't overridden at every update,
     * but the next route asks again.
     */
    private var navFocusPending = false
    /** The car was asked for projected nav focus and hasn't been given it back yet. */
    private var navFocusProjected = false

    /** A hosted app's trip or a phone app's guidance started, changed or ended. */
    private fun guidanceChanged(hosted: Boolean, guiding: Boolean) {
        val handBack: Boolean
        synchronized(navLock) {
            val was = hostedGuiding || phoneGuiding
            if (hosted) hostedGuiding = guiding else phoneGuiding = guiding
            val now = hostedGuiding || phoneGuiding
            if (now && !was) navFocusPending = true
            else if (!now) navFocusPending = false
            // When projected navigation stops, give nav focus back to the
            // car, or its own navigation may stay suppressed after the route.
            handBack = !now && navFocusProjected
            if (handBack) navFocusProjected = false
        }
        if (handBack && aap.state == AapSession.State.RUNNING) aap.requestNavFocus(NavFocusType.NAV_FOCUS_NATIVE)
        requestNavFocus()
    }

    private fun requestNavFocus() {
        synchronized(navLock) {
            if (!navFocusPending || aap.state != AapSession.State.RUNNING) return
            navFocusPending = false
            navFocusProjected = true
        }
        aap.requestNavFocus(NavFocusType.NAV_FOCUS_PROJECTED)
    }

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
            var lastTrack: List<Any?>? = null
            var lastArt: Bitmap? = null
            media.playback.collectLatest { p ->
                if (p == null) return@collectLatest
                // Not just the title: the art (often loaded after the rest), the artist or the
                // duration can come in a later update of the same track. The art compares by
                // content: a platform session hands out a new Bitmap on every update, and the
                // PNG must be made once per cover, not on every play, pause or seek (it's slower
                // to encode than JPEG, which is no longer accepted here).
                val track = listOf(p.title, p.artist, p.album, p.durationMs)
                if (track != lastTrack || !sameArt(lastArt, p.art)) {
                    lastTrack = track
                    lastArt = p.art
                    val meta = fillMediaMetadata(MediaPlaybackMetadata.newBuilder(), p)
                    p.art?.let { art -> meta.setAlbumArt(ByteString.copyFrom(CarImage.toCarPng(art))) }
                    ch.metadata(meta.build())
                }
                ch.status(MediaPlaybackStatus.newBuilder()
                    .setState(if (p.playing) MediaPlaybackStatus.State.PLAYING else MediaPlaybackStatus.State.PAUSED)
                    .setMediaSource(runtime.ui.app(runtime.ui.activeMediaAppId)?.label ?: p.packageName)
                    .setPlaybackSeconds((p.positionMs / 1000).toInt().coerceAtLeast(0))
                    .build())
            }
        }
    }

    private fun sameArt(a: Bitmap?, b: Bitmap?): Boolean = a === b ||
        (a != null && b != null && a.width == b.width && a.height == b.height && runCatching { a.sameAs(b) }.getOrDefault(false))

    // ------------------------------------------------------------------ teardown

    /** The session ended (main thread): work posted before that and run after must not start anything. */
    private var closed = false

    /** Opt-in automatic pairing with this car's Bluetooth (main thread), see [maybeAutoPair]. */
    private var autoPair: BluetoothAutoPair? = null

    /**
     * The car answered our pairing request: if the phone isn't paired with it yet and the user
     * opted in, pair through Shizuku instead of Android's dialog ([BluetoothAutoPair]). Only the
     * address the car announced on its Bluetooth channel.
     */
    @android.annotation.SuppressLint("MissingPermission") // wrapped: without BLUETOOTH_CONNECT the bond state reads as unknown
    private fun maybeAutoPair(carAddress: String, alreadyPaired: Boolean) {
        if (closed || autoPair != null) return
        val bonded = runCatching {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .any { it.address.equals(carAddress, ignoreCase = true) }
        }.getOrDefault(false)
        val shizuku = CaptureMethods.shizukuAvailable()
        if (!AutoPairing.shouldStart(settings.autoPairCarBluetooth, shizuku, carAddress, alreadyPaired, bonded)) {
            if (settings.autoPairCarBluetooth && !alreadyPaired && !bonded) {
                DiagLog.add("car bluetooth: not pairing automatically (${AutoPairing.whyNot(Build.VERSION.SDK_INT, shizuku)})")
            }
            return
        }
        DiagLog.add("car bluetooth: not paired with $carAddress yet; pairing through Shizuku")
        // The checks stay BluetoothPairing's (#229): this only answers, through Shizuku, what they accepted.
        autoPair = BluetoothAutoPair(context, carAddress).also { p ->
            btPairing?.privileged = p
            p.start()
        }
    }

    /**
     * The loop back's volume for a capture (Settings.carAudioFullVolume). Full volume a killed
     * session left behind is put back when the setting is off, so off has no lasting effect.
     */
    private fun captureVolume(): Int = when {
        settings.carAudioFullVolume && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            settings.carAudioFullVolumeLeft = true
            net.prototux.aare.audio.AudioPolicyCapture.VOLUME_FULL
        }
        settings.carAudioFullVolumeLeft -> net.prototux.aare.audio.AudioPolicyCapture.VOLUME_REPAIR
        else -> net.prototux.aare.audio.AudioPolicyCapture.VOLUME_AS_IS
    }

    private fun teardown() {
        closed = true
        CaptureStatus.live.value = null
        btPairing?.privileged = null
        autoPair?.stop(); autoPair = null
        settings.unregisterListener(carKeyPrefsListener)
        // Session ending while music fed the car: pause it, so it doesn't jump to the phone speaker.
        pauseIfFeedingCar(media, feedingCar = audioOuts.containsKey(AudioStreamType.AUDIO_STREAM_MEDIA))
        audioFocus.close()
        main.removeCallbacks(videoFocusCheck)
        keys.clear()
        net.prototux.aare.notify.CarNotificationListener.sink = null
        audioOuts.values.forEach { it.stop() }; audioOuts.clear()
        phoneBridge?.stop(); phoneBridge = null
        btPairing?.stop(); btPairing = null
        callSpeaker?.stop(); callSpeaker = null
        locationFeed.close()
        if (settings.locationOffAfterCar) locationSwitch.stop()
        drivingMode.stop()
        cluster?.stop()
        context.getSystemService(android.media.AudioManager::class.java)?.unregisterAudioRecordingCallback(recordings)
        main.removeCallbacks(recordingDone)
        main.removeCallbacks(resumeAfterRecording)
        settings.unregisterListener(phoneAppsMicSetting)
        phoneAppsMic?.stop(); phoneAppsMic = null
        // Throws when never added: a session that closed before it got that far, or with the setting off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { context.getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(modeChanged) }
        micRouter.shutdown()
        // The captures closed with the session (above): the loop back's volume is as it was.
        settings.carAudioFullVolumeLeft = false
        calibration.close()
        teardownVideo()
        runtime.stop()
        net.prototux.aare.obd.ObdReader.stop(owner = this)
        CarData.reset(owner = this)
        scope.cancel()
    }

    private fun deviceName(): String {
        val m = Build.MODEL
        return if (m.startsWith(Build.MANUFACTURER, ignoreCase = true)) m else "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} $m"
    }

    /** The car's Bluetooth service, to announce the phone again once the session runs (vehicles.json `pairAgainWhenRunning`). */
    private var bluetooth: BluetoothChannel? = null

    /**
     * The phone's own Bluetooth MAC for the car's pairing handshake, or null if unknown. Prefer the
     * user-set value; else try to read it (Android hides it from apps on most devices, returning
     * null or the 02:00:00:00:00:00 placeholder — then we skip the handshake rather than send junk).
     */
    private fun phoneBtAddress(): String? {
        val set = settings.phoneBluetoothAddress.trim()
        val fromSecure = runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        val fromAdapter = runCatching {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.address else null
        }.getOrNull()
        return listOf(set, fromSecure, fromAdapter).firstOrNull(::isValidBtMac)
    }

    private fun isValidBtMac(s: String?): Boolean =
        !s.isNullOrBlank() && s != "02:00:00:00:00:00" && s.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))

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
        private const val VIDEO_FOCUS_RETRY_MS = 3_000L
        /** The keys that start, stop or skip media, which [onKey] ignores until there is a car screen. */
        private val MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
        private const val VIDEO_FOCUS_RETRIES = 3
        const val KEYCODE_MEDIA = 65537
        const val KEYCODE_NAVIGATION = 65538
        const val KEYCODE_RADIO = 65539
        const val KEYCODE_TEL = 65540
        const val KEYCODE_TURN_CARD = 65544
        /** A projected screen with no new frame for this long gets redrawn (see stallCheck). */
        private const val STALL_MS = 1_000L
        private const val STALL_CHECK_MS = 500L
        /** How often a paused resume looks again whether the phone's call or assistant answer ended. */
        private const val CALL_CHECK_MS = 1_000L
        /** Encoder restarts allowed in [RESTART_WINDOW_MS], for this display and the cluster's. */
        internal const val MAX_RESTARTS = 3
        internal const val RESTART_WINDOW_MS = 10_000L

        /** The part of [c]'s resolution the car shows: the resolution less its declared margins. */
        fun visibleSize(c: VideoConfiguration): Pair<Int, Int> {
            val (w, h) = resolution(c.codecResolution)
            return (w - c.widthMargin.coerceIn(0, w)).coerceAtLeast(1) to (h - c.heightMargin.coerceIn(0, h)).coerceAtLeast(1)
        }

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
         * Whether a gain plays the music the car paused again: the car handing the sound back on
         * its own does; a gain answering a request only after a pause for navigation voice.
         */
        fun resumesAfterCarPause(unsolicited: Boolean, pausedForGuidance: Boolean): Boolean = unsolicited || pausedForGuidance

        /**
         * Picks the best configuration the phone can encode comfortably: the largest one within
         * [maxHeight] (short side), preferring 60 fps when asked, among the head unit's preferred
         * indices when it gave any.
         *
         * Sizes are what the car shows: the configuration's resolution less its margins. A wide
         * screen can offer 1920x1080 with 360 rows of margin for a 1920x720 picture, which is
         * within a 720 limit, while its 1280x720 configuration gets stretched across that width.
         *
         * [resolutions] ("800x480"…, from vehicles.json) leaves only those, when the head unit
         * offers any of them: some head units only get 800x480.
         */
        fun chooseVideo(configs: List<VideoConfiguration>, preferred: List<Int>, maxHeight: Int, prefer60: Boolean, codec: MediaCodecType,
                        resolutions: Set<String>? = null): Int {
            fun portrait(i: Int) = resolution(configs[i].codecResolution).let { it.second > it.first }
            val wanted = resolutions.orEmpty()
            fun allowed(i: Int) = resolution(configs[i].codecResolution).let { (w, h) -> wanted.any { it.equals("${w}x$h", ignoreCase = true) } }
            // A screen has one shape: when a head unit offers both, the shape of its first preferred
            // configuration is the screen's (the other one would be letterboxed or squeezed).
            val candidates = videoCandidates(configs, preferred, codec, TAG).let { all ->
                if (wanted.isEmpty()) all else all.filter(::allowed).ifEmpty { all }
            }.let { all ->
                val shape = portrait(preferred.firstOrNull { it in all } ?: all.firstOrNull() ?: return 0)
                all.filter { portrait(it) == shape }
            }
            fun shortSide(i: Int) = visibleSize(configs[i]).let { minOf(it.first, it.second) }
            fun score(i: Int): Long {
                val c = configs[i]
                val s = shortSide(i)
                val fits = s <= maxHeight
                val fps60 = c.frameRate == VideoFrameRateType.VIDEO_FPS_60
                // Same short side: the wider visible picture is the car's real shape (the other gets stretched).
                val long = visibleSize(c).let { maxOf(it.first, it.second) }
                return (if (fits) 1_000_000_000L else 0L) + (if (fits) s else -s) * 100_000L + long * 10L + (if (fps60 == prefer60) 1 else 0)
            }
            return candidates.maxByOrNull(::score) ?: 0
        }

        /**
         * The configurations worth choosing from: the head unit's preferred ones, and only those in
         * the matched [codec] (a config with no codec given means H.264). With none in that codec at
         * all, the old choice anyway: better a try than no video.
         */
        fun videoCandidates(configs: List<VideoConfiguration>, preferred: List<Int>, codec: MediaCodecType, tag: String): List<Int> {
            fun matches(i: Int) = configCodec(configs[i]) == codec
            val all = configs.indices.filter { preferred.isEmpty() || it in preferred }.ifEmpty { configs.indices.toList() }
            all.filter(::matches).ifEmpty { configs.indices.filter(::matches) }.let { if (it.isNotEmpty()) return it }
            Log.w(tag, "no ${codec.name.removePrefix("MEDIA_CODEC_VIDEO_")} video configuration offered (${configs.map { it.videoCodecType }}), trying one anyway")
            return all
        }

        /** A configuration with no codec set means H.264 (proto default / older head units). */
        fun configCodec(c: VideoConfiguration): MediaCodecType =
            if (c.hasVideoCodecType()) c.videoCodecType else MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP

        /** The head unit's codec type for one of the kinds the phone can hardware-encode. */
        fun videoType(k: VideoCodecKind): MediaCodecType = when (k) {
            VideoCodecKind.H264 -> MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
            VideoCodecKind.H265 -> MediaCodecType.MEDIA_CODEC_VIDEO_H265
            VideoCodecKind.VP9 -> MediaCodecType.MEDIA_CODEC_VIDEO_VP9
            VideoCodecKind.AV1 -> MediaCodecType.MEDIA_CODEC_VIDEO_AV1
        }

        /**
         * The best codec both sides can do: the most efficient one the head unit advertises and the
         * phone can hardware-encode. Falls back to H.264 (universal). [forceH264] pins H.264 for head
         * units that advertise a newer codec but decode it poorly. [hwEncodes] is the phone's side.
         */
        fun matchVideoCodec(advertised: Set<MediaCodecType>, forceH264: Boolean, hwEncodes: (VideoCodecKind) -> Boolean): VideoCodecKind {
            if (forceH264) return VideoCodecKind.H264
            // Best -> worst by compression efficiency; the first both sides support wins.
            for (kind in listOf(VideoCodecKind.AV1, VideoCodecKind.H265, VideoCodecKind.VP9, VideoCodecKind.H264)) {
                if (videoType(kind) in advertised && hwEncodes(kind)) return kind
            }
            return VideoCodecKind.H264
        }
    }
}

private fun androidx.compose.ui.graphics.ImageBitmap.asAndroidBitmapCompat(): Bitmap = asAndroidBitmap()

/** The rotary knob and touchpad keys, left out of the connection log. */
private val NAV_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_NAVIGATE_NEXT,
    KeyEvent.KEYCODE_NAVIGATE_PREVIOUS, KeyEvent.KEYCODE_NAVIGATE_IN, KeyEvent.KEYCODE_NAVIGATE_OUT,
)

/** Whether a key from the car goes into the connection log: every button, not the knob's or touchpad's moves. */
internal fun logsCarKey(code: Int) = code !in NAV_KEYS

/** Whether a message from [packageName] gets the driving auto-reply: not from an app [muted] for the car. */
internal fun autoReplies(packageName: String, muted: Set<String>) = packageName !in muted

/** Whether a message from [packageName] reaches the car screen: its app isn't [muted], and [groups] lets a [group]'s through. */
internal fun showsMessage(packageName: String, group: Boolean, groups: Boolean, muted: Set<String>) = packageName !in muted && (!group || groups)

/** How long until video report [n] (0: the first) after the car gave its screen: soon at first, then one in 30 s. */
internal fun videoReportDelayMs(n: Int): Long = longArrayOf(1_000, 2_000, 7_000, 10_000).getOrElse(n) { 30_000 }

/** [length] bytes of [data] from [offset] in hex. */
internal fun hex(data: ByteArray, offset: Int, length: Int): String =
    (offset until offset + length).joinToString("") { "%02x".format(data[it]) }
