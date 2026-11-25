package net.prototux.aare.aap.channels

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.bluetooth.message.BluetoothAuthenticationDataOuterClass.BluetoothAuthenticationData
import aap_protobuf.service.bluetooth.message.BluetoothAuthenticationResultOuterClass.BluetoothAuthenticationResult
import aap_protobuf.service.bluetooth.message.BluetoothPairingMethodOuterClass.BluetoothPairingMethod
import aap_protobuf.service.bluetooth.message.BluetoothPairingRequestOuterClass.BluetoothPairingRequest
import aap_protobuf.service.bluetooth.message.BluetoothPairingResponseOuterClass.BluetoothPairingResponse
import aap_protobuf.service.inputsource.InputSourceServiceOuterClass.InputSourceService.TouchPad
import aap_protobuf.service.inputsource.message.FeedbackEventOuterClass.FeedbackEvent
import aap_protobuf.service.inputsource.message.InputFeedbackOuterClass.InputFeedback
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.sink.message.KeyBindingRequestOuterClass.KeyBindingRequest
import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import aap_protobuf.service.navigationstatus.NavigationStatusServiceOuterClass.NavigationStatusService
import aap_protobuf.service.navigationstatus.message.NavigationCurrentPositionOuterClass.NavigationCurrentPosition
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnDistanceEventOuterClass.NavigationNextTurnDistanceEvent
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent
import aap_protobuf.service.navigationstatus.message.NavigationStateOuterClass.NavigationState
import aap_protobuf.service.navigationstatus.message.NavigationStatusOuterClass.NavigationStatus
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorRequestOuterClass.SensorRequest
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import net.prototux.aare.aap.AapMessage
import net.prototux.aare.aap.BluetoothMsg
import net.prototux.aare.aap.InputMsg
import net.prototux.aare.aap.MediaPlaybackMsg
import net.prototux.aare.aap.NavStatusMsg
import net.prototux.aare.aap.Priority
import net.prototux.aare.aap.SensorMsg
import net.prototux.aare.aap.ServiceChannel
import java.util.concurrent.TimeUnit

/** Touch screen, touch pad, buttons and rotary controller of the car. */
class InputChannel(service: Service, private val listener: Listener) : ServiceChannel(service, "input") {
    interface Listener {
        /** Called on the session's reader thread. */
        fun onInput(report: InputReport)

        /** The car changed its touchpad's sensitivity; [pad] is the new [touchPad] (reader thread). */
        fun onTouchPadUpdate(pad: TouchPad) {}
    }

    val keycodes: List<Int> = service.inputSourceService.keycodesSupportedList
    // The main touch panel: a car with a cluster/second screen lists that one as is_secondary.
    val touchScreen = service.inputSourceService.touchscreenList.firstOrNull { !it.isSecondary }
        ?: service.inputSourceService.touchscreenList.firstOrNull()
    @Volatile var touchPad = service.inputSourceService.touchpadList.firstOrNull()
        private set
    /** The feedback (a click, a haptic pulse) the car can play when asked, from its descriptor. */
    val feedbackEvents: Set<FeedbackEvent> = service.inputSourceService.feedbackEventsSupportedList.toSet()

    /**
     * Asks the car to play [event] (any thread). Only for an event the car listed: it
     * sends none of the others.
     */
    fun sendFeedback(event: FeedbackEvent) {
        if (!opened || event !in feedbackEvents) return
        send(InputMsg.INPUT_FEEDBACK, InputFeedback.newBuilder().setEvent(event).build())
    }

    override fun onOpened() {
        // Bind every key the car has: we decide later what each one does.
        send(InputMsg.KEY_BINDING_REQUEST, KeyBindingRequest.newBuilder().addAllKeycodes(keycodes).build())
    }

    /** An update only changes the first touchpad's sensitivity. */
    override fun onServiceUpdate(update: Service) {
        val pad = touchPad ?: return
        val updated = update.inputSourceService.touchpadList.firstOrNull() ?: return
        if (!updated.hasSensitivity()) return
        val changed = pad.toBuilder().setSensitivity(updated.sensitivity).build()
        touchPad = changed
        listener.onTouchPadUpdate(changed)
    }

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            InputMsg.INPUT_REPORT -> listener.onInput(InputReport.parseFrom(msg.bodyBytes()))
            InputMsg.KEY_BINDING_RESPONSE -> Unit
        }
    }
}

/**
 * Car sensors: driving status, night mode, GPS, speed, gear, fuel, odometer, doors, tyres...
 *
 * Every sensor the car offers is asked for when the channel opens (car apps, the assistant, the
 * dashboard and the car data pages read them), driving status first. The requests go out one at
 * a time (see [SensorRequests]).
 */
class SensorChannel(service: Service, private val listener: Listener) : ServiceChannel(service, "sensors") {
    interface Listener {
        fun onSensors(batch: SensorBatch)
    }

    /** Sensors of a type this build doesn't know read back as the default (LOCATION): skip them. */
    val available: List<SensorType> = service.sensorSourceService.sensorsList.filter { it.hasSensorType() }.map { it.sensorType }

    private val requests = SensorRequests(
        // Driving status and the ones that drive the UI first, then whatever else the car offers.
        order = (WANTED.filter { it in available } + available.filter { it !in WANTED }).distinct(),
        periodOf = { type ->
            // min_update_period's unit isn't documented by AAP; assumed milliseconds. High-rate
            // sensors would otherwise flood the channel, so throttle just those.
            if (type in HIGH_RATE) 500L else 0L
        },
        send = { type, period -> send(SensorMsg.REQUEST, SensorRequest.newBuilder().setType(type).setMinUpdatePeriod(period).build()) },
        schedule = { task -> session.schedule(task, SensorRequests.TIMEOUT_MS, TimeUnit.MILLISECONDS) },
        log = { session.log.i("sensors: $it") },
    )

    override fun onOpened() = requests.start()

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            SensorMsg.BATCH -> listener.onSensors(SensorBatch.parseFrom(msg.bodyBytes()))
            // A refused sensor just stays silent; either answer lets the next request go.
            SensorMsg.RESPONSE, SensorMsg.ERROR -> requests.answered()
        }
    }

    override fun onClosed() = requests.stop()

    companion object {
        /** Requested first, driving status before anything; every other one the car offers follows. */
        val WANTED = listOf(
            SensorType.SENSOR_DRIVING_STATUS_DATA,
            SensorType.SENSOR_NIGHT_MODE,
            SensorType.SENSOR_LOCATION,
            SensorType.SENSOR_SPEED,
            SensorType.SENSOR_GEAR,
            SensorType.SENSOR_PARKING_BRAKE,
            SensorType.SENSOR_COMPASS,
            SensorType.SENSOR_FUEL,
        )

        /** Sensors that report many times a second: ask for updates no faster than every 500 ms. */
        val HIGH_RATE = setOf(
            SensorType.SENSOR_ACCELEROMETER_DATA,
            SensorType.SENSOR_GYROSCOPE_DATA,
            SensorType.SENSOR_DEAD_RECKONING_DATA,
            SensorType.SENSOR_GPS_SATELLITE_DATA,
        )
    }
}

/**
 * Turn-by-turn for the instrument cluster / head-unit widgets. Head units on protocol 1.6 and up
 * read NavigationState + CurrentPosition; older ones only the (deprecated) turn and distance
 * events, and answer the newer messages with "unexpected message" (0xff). Android Auto sends one
 * set or the other by the head unit's version, never both: a VW Play (protocol 1.5) answered two
 * refused messages on every update when it got both.
 */
class NavigationStatusChannel(service: Service) : ServiceChannel(service, "navstatus") {
    /** An unknown cluster type reads back as the default (IMAGE): only trust one the car set. */
    val wantsImages = service.navigationStatusService.let {
        it.hasType() && it.type == NavigationStatusService.InstrumentClusterType.IMAGE
    }
    val imageOptions = service.navigationStatusService.imageOptions
    val minIntervalMs = service.navigationStatusService.minimumIntervalMs
    @Volatile var active = false
        private set

    private class Step(
        val state: NavigationState?, val position: NavigationCurrentPosition?,
        val turn: NavigationNextTurnEvent?, val distance: NavigationNextTurnDistanceEvent?,
    )
    private val lock = Any()
    private var lastSentMs = 0L
    /** The newest step that came inside the rate-limit window; sent when the window ends. */
    private var pending: Step? = null
    private var flushScheduled = false

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            NavStatusMsg.START -> active = true
            NavStatusMsg.STOP -> active = false
        }
    }

    fun status(s: NavigationStatus.NavigationStatusEnum) = synchronized(lock) {
        // A step held back for the rate limit must not light the cluster up again after navigation
        // ended, nor show the abandoned route's maneuver while rerouting.
        if (s != NavigationStatus.NavigationStatusEnum.ACTIVE) pending = null
        // Older head units don't know REROUTING; Android Auto leaves it out for them too.
        if (s == NavigationStatus.NavigationStatusEnum.REROUTING && !modern) return@synchronized
        send(NavStatusMsg.STATUS, NavigationStatus.newBuilder().setStatus(s).build(), Priority.INPUT)
    }

    /** The head unit reads NavigationState and CurrentPosition (protocol 1.6+), not the old events. */
    private val modern: Boolean get() = session.headUnitAtLeast(1, 6)

    /**
     * Sends the current step, rate-limited to what the head unit asked for. A step inside the
     * window is held and sent when it ends (newest wins), so the cluster never stays on a stale one.
     */
    fun update(
        state: NavigationState?, position: NavigationCurrentPosition?,
        turn: NavigationNextTurnEvent?, distance: NavigationNextTurnDistanceEvent?, force: Boolean = false,
    ) = synchronized(lock) {
        val step = Step(state, position, turn, distance)
        val wait = lastSentMs + minIntervalMs - now()
        if (!force && wait > 0) {
            pending = step
            if (!flushScheduled) {
                flushScheduled = true
                session.schedule({ flush() }, wait, TimeUnit.MILLISECONDS)
            }
            return@synchronized
        }
        pending = null
        sendStep(step)
    }

    /** Runs on the session's timer thread. */
    private fun flush() = synchronized(lock) {
        flushScheduled = false
        val step = pending ?: return@synchronized
        pending = null
        sendStep(step)
    }

    private fun sendStep(step: Step) {
        lastSentMs = now()
        if (modern) {
            step.state?.let { send(NavStatusMsg.STATE, it, Priority.INPUT) }
            step.position?.let { send(NavStatusMsg.CURRENT_POSITION, it, Priority.INPUT) }
        } else {
            step.turn?.let { send(NavStatusMsg.TURN_EVENT, it, Priority.INPUT) }
            step.distance?.let { send(NavStatusMsg.DISTANCE_EVENT, it, Priority.INPUT) }
        }
    }

    override fun onClosed() = synchronized(lock) { pending = null }

    private fun now() = System.nanoTime() / 1_000_000
}

/**
 * A service AARE has nothing to say on, opened because the head unit expects it open (a
 * vehicles.json `openServices` quirk). Whatever the car sends on it is ignored.
 */
class PassiveChannel(service: Service, name: String) : ServiceChannel(service, name) {
    override fun onMessage(msg: AapMessage) = Unit
}

/** Now-playing info for the head unit's own widgets and cluster. */
class MediaPlaybackChannel(service: Service) : ServiceChannel(service, "playback") {
    override fun onMessage(msg: AapMessage) = Unit

    fun status(s: MediaPlaybackStatus) = send(MediaPlaybackMsg.STATUS, s, Priority.INPUT)
    fun metadata(m: MediaPlaybackMetadata) = send(MediaPlaybackMsg.METADATA, m, Priority.AUDIO)
}

/**
 * Wi-Fi projection: listed by head units that offer wireless Android Auto, with their access
 * point's BSSID. Android Auto opens it whenever a head unit lists it, over USB too, so the car sees
 * the channels it was built against. AARE joins the car's Wi-Fi through the Bluetooth bootstrap
 * instead and has nothing to say on it, so it is only opened, and what the car sends is ignored.
 */
class WifiProjectionChannel(service: Service) : ServiceChannel(service, "wifi") {
    override fun onMessage(msg: AapMessage) = Unit
}

/**
 * Bluetooth: the head unit gives its address; the phone asks it to pair so calls go through the
 * car (HFP). Android hides the phone's own address from normal apps, so [phoneAddress] may be
 * unknown, in which case we just let the user pair the usual way.
 *
 * While the car pairs, it sends the passkey or PIN it shows ([Listener.onAuthenticationData]); the
 * phone checks it against Android's pairing request and answers with [sendAuthenticationResult].
 */
class BluetoothChannel(service: Service, private val phoneAddress: String?, private val listener: Listener) : ServiceChannel(service, "bluetooth") {
    interface Listener {
        fun onPairingResponse(alreadyPaired: Boolean, ok: Boolean) {}
        /** The passkey or PIN the head unit shows for the pairing in progress. Reader thread. */
        fun onAuthenticationData(authData: String) {}
    }

    val carAddress: String = service.bluetoothService.carAddress
    val methods: List<BluetoothPairingMethod> = service.bluetoothService.supportedPairingMethodsList

    /** The car sent authentication data that hasn't been answered yet. */
    @Volatile private var authDataPending = false

    override fun onOpened() {
        // Announce the phone to the car. Some head units withhold the projected screen until they
        // know which phone is connected (they keep video focus "native"); this pairing request over
        // the Bluetooth service channel is how the official phone identifies itself. Only when we
        // have a real phone Bluetooth address; Android hides it from apps on most devices.
        phoneAddress?.let { requestPairing(it) }
    }

    /**
     * Announces the phone again, once the session is running. A VW Play (Visteon MIB Regio) drops
     * its Bluetooth link to the phone when projection starts, answers the request sent at channel
     * open with "already paired", and only connects hands-free again (calls through the car) when
     * the request comes after the session is up. Only for cars whose vehicles.json rule asks for it
     * (`pairAgainWhenRunning`). Returns whether it was sent: not when the channel isn't open or the
     * phone's address is unknown.
     */
    fun announceAgain(): Boolean {
        if (!opened) return false
        val address = phoneAddress ?: return false
        requestPairing(address)
        return true
    }

    fun requestPairing(phoneAddress: String) {
        send(BluetoothMsg.PAIRING_REQUEST, BluetoothPairingRequest.newBuilder().setPhoneAddress(canonicalAddress(phoneAddress)).setPairingMethod(pairingMethod(methods)).build())
    }

    /**
     * Tells the car whether its authentication data matched: once per authentication data it sent,
     * never unasked. Head units before protocol 1.5 don't know this message, and it isn't sent to
     * them. False when not sent.
     */
    fun sendAuthenticationResult(status: MessageStatus): Boolean {
        if (!authDataPending || !session.headUnitAtLeast(1, 5)) return false
        authDataPending = false
        send(BluetoothMsg.AUTHENTICATION_RESULT, BluetoothAuthenticationResult.newBuilder().setStatus(status).build())
        return true
    }

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            BluetoothMsg.PAIRING_RESPONSE -> {
                val r = BluetoothPairingResponse.parseFrom(msg.bodyBytes())
                listener.onPairingResponse(r.alreadyPaired, r.status.number == 0)
            }
            BluetoothMsg.AUTHENTICATION_DATA -> {
                // The pairing method it carries is ignored: Android's
                // own pairing request says which method is in use.
                val d = BluetoothAuthenticationData.parseFrom(msg.bodyBytes())
                if (d.hasAuthData()) {
                    authDataPending = true
                    listener.onAuthenticationData(d.authData)
                }
            }
        }
    }

    companion object {
        /**
         * The address as Android writes it (upper case, colons). Head units compare it as text with
         * the phones they have paired: a VW Play (Visteon MIB Regio) answered a lower-case address
         * with a plain "accepted", not "already paired", and listed the phone twice, once for
         * Bluetooth and once for the projection, so calls didn't go through the car (#117).
         */
        fun canonicalAddress(address: String): String = address.trim().uppercase(java.util.Locale.ROOT)

        /**
         * The pairing method to ask for: numeric comparison when the car offers it, else PIN, else
         * the first method the car lists (even "unavailable": head units that pair today keep getting
         * exactly the method they pair with), else numeric comparison.
         */
        fun pairingMethod(offered: List<BluetoothPairingMethod>): BluetoothPairingMethod = when {
            BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON in offered -> BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON
            BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN in offered -> BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN
            else -> offered.firstOrNull() ?: BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON
        }
    }
}
