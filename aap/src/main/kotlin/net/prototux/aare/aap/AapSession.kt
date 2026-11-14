package net.prototux.aare.aap

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestOuterClass.AudioFocusRequest
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AuthResponseOuterClass.AuthResponse
import aap_protobuf.service.control.message.ByeByeReasonOuterClass.ByeByeReason
import aap_protobuf.service.control.message.ByeByeRequestOuterClass.ByeByeRequest
import aap_protobuf.service.control.message.ByeByeResponseOuterClass.ByeByeResponse
import aap_protobuf.service.control.message.ChannelOpenRequestOuterClass.ChannelOpenRequest
import aap_protobuf.service.control.message.ChannelOpenResponseOuterClass.ChannelOpenResponse
import aap_protobuf.service.control.message.NavFocusNotificationOuterClass.NavFocusNotification
import aap_protobuf.service.control.message.NavFocusRequestNotificationOuterClass.NavFocusRequestNotification
import aap_protobuf.service.control.message.NavFocusTypeOuterClass.NavFocusType
import aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest
import aap_protobuf.service.control.message.PingResponseOuterClass.PingResponse
import aap_protobuf.service.control.message.ServiceDiscoveryRequestOuterClass.ServiceDiscoveryRequest
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.control.message.VoiceSessionNotificationOuterClass.VoiceSessionNotification
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** The phone's TLS identity. */
class PhoneIdentity(val certificate: X509Certificate, val privateKey: PrivateKey, val chain: List<X509Certificate> = emptyList())

/** What the phone tells the head unit about itself in the service discovery request. */
class PhoneDescription(
    val deviceName: String,
    val label: String,
    val smallIcon: ByteArray? = null,
    val mediumIcon: ByteArray? = null,
    val largeIcon: ByteArray? = null,
    /** Stable per-install id, so head units remember this phone. */
    val instanceId: String? = null,
)

class SessionOptions(
    /**
     * Whether the phone originates pings. The official phone only does on wireless links; on USB
     * it just answers the head unit's.
     */
    val sendPings: Boolean = true,
    /** The head unit certificates the user trusts; null accepts any certificate. */
    val pins: PinStore? = null,
    /** Identifies the head unit in [pins] (Bluetooth address, USB accessory); required with [pins]. */
    val peerKey: String? = null,
    /** Trust the first certificate of a head unit with nothing trusted yet (Bluetooth-bonded ones). */
    val trustOnFirstUse: Boolean = false,
) {
    init {
        require(pins == null || peerKey != null) { "peerKey is required with pins" }
    }
}

/** Minimal logging hook so the protocol module has no Android dependency. */
interface AapLog {
    fun d(msg: String) {}
    fun i(msg: String)
    fun w(msg: String, t: Throwable? = null)
    fun e(msg: String, t: Throwable? = null)

    object Stdout : AapLog {
        override fun d(msg: String) = println("D/aap: $msg")
        override fun i(msg: String) = println("I/aap: $msg")
        override fun w(msg: String, t: Throwable?) { println("W/aap: $msg"); t?.printStackTrace(System.out) }
        override fun e(msg: String, t: Throwable?) { println("E/aap: $msg"); t?.printStackTrace(System.out) }
    }
}

/** Send priorities: lower goes first. Control traffic must never wait behind video. */
object Priority {
    const val CONTROL = 0
    const val INPUT = 1
    const val AUDIO = 2
    const val VIDEO = 3
}

/**
 * One AAP connection, phone side.
 *
 * Threads: a reader dispatches incoming messages in order (handlers must not block); a writer
 * drains a priority queue and does all TLS sealing in send order; a small scheduler runs pings and
 * the watchdog. Any I/O or protocol error ends the session exactly once via [Callbacks.onClosed].
 */
class AapSession(
    private val transport: Transport,
    private val identity: PhoneIdentity,
    private val phone: PhoneDescription,
    private val callbacks: Callbacks,
    private val log: AapLog = AapLog.Stdout,
    private val options: SessionOptions = SessionOptions(),
) {
    interface Callbacks {
        /**
         * The head unit listed its services. Return the channels to open; each is opened in turn
         * and receives [ServiceChannel.onOpened] when the head unit accepts it.
         */
        fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel>

        /** The session is up: every requested channel has been answered. */
        fun onRunning(session: AapSession) {}

        fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) {}
        fun onNavFocus(session: AapSession, notification: NavFocusNotification) {}

        /** The car says whether it can take calls (head unit to phone, control message 24). */
        fun onCallAvailability(session: AapSession, available: Boolean) {}

        /** Called once, from any thread. [error] is null for an orderly bye-bye. */
        fun onClosed(session: AapSession, reason: String, error: Throwable?)
    }

    enum class State { VERSION, HANDSHAKE, AUTH, DISCOVERY, OPENING, RUNNING, CLOSED }

    @Volatile var state = State.VERSION
        private set
    /** The state the session was in when it closed. */
    @Volatile var closedDuring = State.VERSION
        private set
    /**
     * The head unit refused the phone certificate: it reported an authentication failure, ended
     * the TLS handshake it had started, or hung up during it. Null if it didn't, and for timeouts
     * before TLS began.
     */
    @Volatile var certificateRefusal: CertificateRefusal? = null
        private set
    @Volatile private var tlsStarted = false
    @Volatile var discovery: ServiceDiscoveryResponse? = null
        private set
    var headUnitVersion: Pair<Int, Int>? = null
        private set

    private val cryptor = Cryptor(identity.certificate, identity.privateKey, identity.chain,
        pins = options.pins, peerKey = options.peerKey, trustOnFirstUse = options.trustOnFirstUse)

    /** Set when the session ended because the head unit's certificate is not trusted (yet). */
    val pinRefusal: PinRefusal? get() = cryptor.pinRefusal

    @Volatile private var tlsUp = false
    private val reader = FrameReader(transport.input, log) { if (tlsUp) cryptor else null }
    private val writer = FrameWriter(transport.output) { if (tlsUp) cryptor else null }

    private val queue = PriorityBlockingQueue<Outgoing>()
    private val seq = AtomicLong()
    private val closed = AtomicBoolean(false)
    private val runningEntered = AtomicBoolean(false)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "aap-timer").apply { isDaemon = true }
    }
    private val channels = java.util.concurrent.ConcurrentHashMap<Int, ServiceChannel>()
    private var pendingOpens = 0
    /** Channels whose open request awaits its response. */
    private val awaitingOpen = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** Channels opened only once the sensor channel has been answered (see [onServiceDiscovery]). */
    private val deferredOpens = AtomicReference<List<ServiceChannel>?>(null)
    /** Channels that sent data before they were open, logged once each (reader thread only). */
    private val earlyData = HashSet<Int>()
    /**
     * Set once the head unit said bye-bye: it may hang up before our response is written, and
     * that EOF or broken pipe is then the orderly end it announced, not a failure.
     */
    @Volatile private var headUnitByeBye: String? = null

    @Volatile private var lastInboundMs = now()
    // How the link looked when it failed, for the log (#182: USB links dropping with EIO).
    @Volatile private var lastOutboundMs = now()
    @Volatile private var writingSinceMs = -1L
    @Volatile private var slowestWriteMs = 0L
    @Volatile private var pingIntervalMs = 1_000L
    /**
     * Silence that means the link is dead. Only applied once the head unit has shown it takes part
     * in keep-alives (some never answer the phone's pings); USB unplugs are caught by I/O errors.
     * Generous because some Wi-Fi head units go off channel for 20 s while scanning.
     */
    @Volatile private var deadAfterMs = 20_000L
    @Volatile private var pingEvidence = false

    /** Round trip of the last ping, for diagnostics. */
    @Volatile var lastRttMs = -1L
        private set

    private class Outgoing(
        val priority: Int, val seq: Long, val channel: Int, val control: Boolean, val encrypted: Boolean,
        val plain: ByteArray, val length: Int, val onSent: (() -> Unit)?,
    ) : Comparable<Outgoing> {
        override fun compareTo(other: Outgoing): Int =
            if (priority != other.priority) priority.compareTo(other.priority) else seq.compareTo(other.seq)
    }

    fun start() {
        Thread(::readLoop, "aap-reader").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }.start()
        Thread(::writeLoop, "aap-writer").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }.start()
        // The head unit opens with a version request; if nothing comes at all, give up.
        schedule({
            if (state == State.VERSION) fail("no version request from head unit", null)
        }, 15, TimeUnit.SECONDS)
        log.i("session started on ${transport.name}")
    }

    /** Ends the session politely: bye-bye, then close after a short grace period. */
    fun stop(reason: ByeByeReason = ByeByeReason.USER_SELECTION) {
        if (closed.get()) return
        if (state == State.RUNNING || state == State.OPENING) {
            sendControl(ControlMsg.BYEBYE_REQUEST, ByeByeRequest.newBuilder().setReason(reason).build())
            schedule({ close("stopped by phone", null) }, 1, TimeUnit.SECONDS)
        } else {
            close("stopped by phone", null)
        }
    }

    // ------------------------------------------------------------------ sending

    /** Queues a message. [plain] must hold the 2-byte id followed by the body. */
    fun sendRaw(channel: Int, plain: ByteArray, length: Int = plain.size, control: Boolean = false,
                encrypted: Boolean = true, priority: Int = Priority.CONTROL, onSent: (() -> Unit)? = null) {
        if (closed.get()) return
        queue.offer(Outgoing(priority, seq.incrementAndGet(), channel, control, encrypted, plain, length, onSent))
    }

    fun send(channel: Int, id: Int, body: MessageLite, control: Boolean = false, priority: Int = Priority.CONTROL) {
        val size = body.serializedSize
        val plain = ByteArray(2 + size)
        plain[0] = (id shr 8).toByte(); plain[1] = id.toByte()
        val out = com.google.protobuf.CodedOutputStream.newInstance(plain, 2, size)
        body.writeTo(out)
        out.checkNoSpaceLeft()
        sendRaw(channel, plain, control = control, encrypted = true, priority = priority)
    }

    fun sendControl(id: Int, body: MessageLite) = send(0, id, body)

    /**
     * Asks the head unit for audio focus. A change that follows a stream's STOP goes at
     * [Priority.AUDIO], so it can't overtake that STOP or the data still queued before it.
     */
    fun requestAudioFocus(type: AudioFocusRequestType, priority: Int = Priority.CONTROL) =
        send(0, ControlMsg.AUDIO_FOCUS_REQUEST, AudioFocusRequest.newBuilder().setAudioFocusType(type).build(), priority = priority)

    fun requestNavFocus(type: NavFocusType) =
        sendControl(ControlMsg.NAV_FOCUS_REQUEST, NavFocusRequestNotification.newBuilder().setFocusType(type).build())

    fun voiceSession(active: Boolean) = sendControl(
        ControlMsg.VOICE_SESSION_NOTIFICATION,
        VoiceSessionNotification.newBuilder().setStatus(
            if (active) aap_protobuf.service.control.message.VoiceSessionStatusOuterClass.VoiceSessionStatus.VOICE_SESSION_START
            else aap_protobuf.service.control.message.VoiceSessionStatusOuterClass.VoiceSessionStatus.VOICE_SESSION_END,
        ).build(),
    )

    // ------------------------------------------------------------------ loops

    private fun writeLoop() {
        try {
            while (!closed.get()) {
                val m = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val started = now()
                writingSinceMs = started
                // Never downgrade: a message flagged encrypted while TLS is down throws (ends the session).
                writer.write(m.channel, m.control, m.encrypted, m.plain, m.length)
                val done = now()
                writingSinceMs = -1L
                lastOutboundMs = done
                if (done - started > slowestWriteMs) slowestWriteMs = done - started
                m.onSent?.invoke()
            }
        } catch (e: Throwable) {
            fail("write failed", e)
        }
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val msg = reader.read()
                lastInboundMs = now()
                dispatch(msg)
            }
        } catch (e: FramingException) {
            onFramingError(e)
        } catch (e: Throwable) {
            fail(if (closed.get()) "closed" else "read failed", e)
        }
    }

    /**
     * Tell the head unit its framing broke (FRAMING_ERROR, in the clear on
     * the control channel, whether TLS is up or not) before hanging up. The write gets a short
     * grace in case the writer is stuck.
     */
    private fun onFramingError(e: FramingException) {
        if (closed.get()) return
        sendRaw(0, AapMessage.plaintext(ControlMsg.FRAMING_ERROR, ByteArray(0)), encrypted = false,
            onSent = { fail("framing error", e) })
        schedule({ fail("framing error", e) }, 200, TimeUnit.MILLISECONDS)
    }

    private fun dispatch(msg: AapMessage) {
        // Once TLS is up the head unit has no legitimate reason to send anything in the clear; drop it, don't end the session.
        if (tlsUp && !msg.encrypted) {
            log.w("dropping plaintext frame after TLS is up: $msg")
            return
        }
        if (msg.channel == 0 && !msg.control) {
            handleControl(msg)
            return
        }
        val ch = channels[msg.channel]
        if (ch == null) {
            log.w("message for unknown channel: $msg")
            return
        }
        if (msg.control) {
            // Channel-control messages on a service channel: channel open response, ping on old units.
            when (msg.id) {
                ControlMsg.CHANNEL_OPEN_RESPONSE -> onChannelOpenResponse(ch, msg)
                ControlMsg.CHANNEL_CLOSE_NOTIFICATION -> {
                    ch.opened = false
                    runHandler(ch) { ch.onClosed() }
                }
                else -> log.w("unexpected control message on channel ${ch.id}: $msg")
            }
            return
        }
        // A channel's data counts only once the channel is open.
        if (!ch.opened) {
            if (earlyData.add(ch.id)) log.w("dropping data on channel ${ch.id} (${ch.name}) before it is open: $msg")
            return
        }
        runHandler(ch) { ch.onMessage(msg) }
    }

    private fun onChannelOpenResponse(ch: ServiceChannel, msg: AapMessage) {
        if (ch.id !in awaitingOpen) {
            // An open response for a channel that isn't being opened (a
            // duplicate, or one never asked for) gets UNEXPECTED_MESSAGE on that channel, in the clear.
            log.w("unexpected channel open response on channel ${ch.id} (${ch.name})")
            sendRaw(ch.id, AapMessage.plaintext(ControlMsg.UNEXPECTED_MESSAGE, ByteArray(0)), encrypted = false)
            return
        }
        val body = msg.bodyBytes()
        val accepted = try {
            openAccepted(body)
        } catch (e: InvalidProtocolBufferException) {
            log.w("dropping malformed channel open response on channel ${ch.id} (${ch.name})", e)
            return
        }
        awaitingOpen.remove(ch.id)
        if (accepted) {
            ch.opened = true
            log.i("channel ${ch.id} (${ch.name}) open")
            runHandler(ch) { ch.onOpened() }
        } else {
            val resp = ChannelOpenResponse.parseFrom(body)
            log.w("head unit refused channel ${ch.id} (${ch.name}): ${if (resp.hasStatus()) resp.status else "unknown status"}")
        }
        if (ch.service.hasSensorSourceService()) openDeferred()
        if (--pendingOpens <= 0 && state == State.OPENING) enterRunning()
    }

    private inline fun runHandler(ch: ServiceChannel, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // A broken handler must not take the whole car connection down.
            log.e("channel ${ch.id} (${ch.name}) handler failed", e)
        }
    }

    // ------------------------------------------------------------------ control channel

    private fun handleControl(msg: AapMessage) {
        log.d("rx $msg")
        // Until service discovery only the version and TLS handshake messages make sense.
        if (state.ordinal < State.DISCOVERY.ordinal &&
            msg.id != ControlMsg.VERSION_REQUEST && msg.id != ControlMsg.ENCAPSULATED_SSL &&
            msg.id != ControlMsg.AUTH_COMPLETE
        ) {
            fail("unexpected control message in state $state: $msg", null)
            return
        }
        when (msg.id) {
            ControlMsg.VERSION_REQUEST -> onVersionRequest(msg)
            ControlMsg.ENCAPSULATED_SSL -> onHandshakeBytes(msg.bodyBytes())
            ControlMsg.AUTH_COMPLETE -> onAuthComplete(msg)
            ControlMsg.SERVICE_DISCOVERY_RESPONSE -> onServiceDiscovery(msg)
            ControlMsg.PING_REQUEST -> {
                pingEvidence = true
                val req = PingRequest.parseFrom(msg.bodyBytes())
                sendControl(ControlMsg.PING_RESPONSE, PingResponse.newBuilder().setTimestamp(req.timestamp).build())
            }
            ControlMsg.PING_RESPONSE -> {
                pingEvidence = true
                val resp = PingResponse.parseFrom(msg.bodyBytes())
                lastRttMs = (System.nanoTime() / 1_000 - resp.timestamp) / 1_000
            }
            ControlMsg.AUDIO_FOCUS_NOTIFICATION -> {
                val n = AudioFocusNotification.parseFrom(msg.bodyBytes())
                log.i("audio focus: ${n.focusState} unsolicited=${n.unsolicited}")
                callbacks.onAudioFocus(this, n)
            }
            ControlMsg.CALL_AVAILABILITY_STATUS -> {
                // The car tells whether it can take calls (its hands-free link is up).
                val available = aap_protobuf.service.control.message.CallAvailabilityStatusOuterClass.CallAvailabilityStatus
                    .parseFrom(msg.bodyBytes()).callAvailable
                log.i("car call availability: $available")
                callbacks.onCallAvailability(this, available)
            }
            ControlMsg.NAV_FOCUS_NOTIFICATION -> {
                val n = NavFocusNotification.parseFrom(msg.bodyBytes())
                log.i("nav focus: ${n.focusType}")
                callbacks.onNavFocus(this, n)
            }
            ControlMsg.BYEBYE_REQUEST -> {
                val reason = runCatching { ByeByeRequest.parseFrom(msg.bodyBytes()).reason }.getOrNull()
                log.i("head unit says bye-bye: $reason")
                val why = "head unit ended the session ($reason)"
                headUnitByeBye = why
                sendRaw(0, AapMessage.plaintext(ControlMsg.BYEBYE_RESPONSE, ByeByeResponse.getDefaultInstance().toByteArray()),
                    onSent = { close(why, null) })
            }
            ControlMsg.BYEBYE_RESPONSE -> close("session ended", null)
            ControlMsg.CHANNEL_OPEN_RESPONSE -> log.w("channel open response on control channel")
            else -> log.d("unhandled control message $msg")
        }
    }

    private fun onVersionRequest(msg: AapMessage) {
        if (state != State.VERSION) {
            fail("version request in state $state", null)
            return
        }
        val b = msg.body
        val o = msg.bodyOffset
        val major = if (msg.bodyLength >= 2) ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff) else 1
        val minor = if (msg.bodyLength >= 4) ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff) else 0
        headUnitVersion = major to minor
        // Like the official phone: 1.7 to anything up to 1.7, 6.1 to the newer GAL numbering.
        // Status is always success; feature gating follows the head unit's own version.
        val (replyMajor, replyMinor) = if (major < 1 || (major == 1 && minor <= 7)) 1 to 7 else 6 to 1
        log.i("head unit protocol $major.$minor, answering $replyMajor.$replyMinor")
        val body = byteArrayOf(
            (replyMajor shr 8).toByte(), replyMajor.toByte(),
            (replyMinor shr 8).toByte(), replyMinor.toByte(),
            0, 0, // STATUS_SUCCESS
        )
        sendRaw(0, AapMessage.plaintext(ControlMsg.VERSION_RESPONSE, body), encrypted = false)
        enterHandshake()
    }

    /** Starts the TLS handshake, with its own timeout whichever way the head unit got here. */
    private fun enterHandshake() {
        state = State.HANDSHAKE
        cryptor.beginHandshake()
        schedule({
            if (state == State.HANDSHAKE) fail("TLS handshake did not complete (head unit rejected our certificate?)", null)
        }, 15, TimeUnit.SECONDS)
    }

    private fun onHandshakeBytes(bytes: ByteArray) {
        if (state != State.HANDSHAKE) {
            log.w("TLS bytes in state $state")
            if (state.ordinal > State.HANDSHAKE.ordinal) return
            enterHandshake()
        }
        tlsStarted = true
        val out = cryptor.handshake(bytes)
        log.d("TLS: got ${bytes.size} bytes, sending ${out.size}, done=${cryptor.handshakeDone}")
        if (out.isNotEmpty()) sendRaw(0, AapMessage.plaintext(ControlMsg.ENCAPSULATED_SSL, out), encrypted = false)
        if (cryptor.handshakeDone) {
            state = State.AUTH
            log.i("TLS up: ${cryptor.protocol} ${cryptor.cipherSuite}; head unit cert: " +
                (cryptor.peerCertificates.firstOrNull()?.subjectX500Principal?.name ?: "none"))
            // A head unit that goes silent after TLS (no AUTH_COMPLETE) would otherwise hang the
            // reader forever: the version/handshake timers don't cover the AUTH state.
            schedule({ if (state == State.AUTH) fail("no AUTH_COMPLETE after the TLS handshake", null) }, 10, TimeUnit.SECONDS)
        }
    }

    private fun onAuthComplete(msg: AapMessage) {
        val status = runCatching { AuthResponse.parseFrom(msg.bodyBytes()).status }.getOrDefault(0)
        if (status != 0) {
            certificateRefusal = CertificateRefusal(status, explicit = true)
            fail("head unit rejected authentication (status $status)", null)
            return
        }
        if (state != State.AUTH || !cryptor.handshakeDone) {
            fail("auth complete in state $state (TLS handshake done=${cryptor.handshakeDone})", null)
            return
        }
        // From here on everything is encrypted.
        tlsUp = true
        state = State.DISCOVERY
        val req = ServiceDiscoveryRequest.newBuilder()
            .setDeviceName(phone.deviceName)
            .setLabelText(phone.label)
        phone.smallIcon?.let { req.smallIcon = com.google.protobuf.ByteString.copyFrom(it) }
        phone.mediumIcon?.let { req.mediumIcon = com.google.protobuf.ByteString.copyFrom(it) }
        phone.largeIcon?.let { req.largeIcon = com.google.protobuf.ByteString.copyFrom(it) }
        if (headUnitAtLeast(1, 6)) phone.instanceId?.let {
            req.setPhoneInfo(aap_protobuf.shared.PhoneInfoOuterClass.PhoneInfo.newBuilder().setInstanceId(it))
        }
        sendControl(ControlMsg.SERVICE_DISCOVERY_REQUEST, req.build())
        schedule({
            if (state == State.DISCOVERY) fail("no service discovery response", null)
        }, 10, TimeUnit.SECONDS)
    }

    private fun onServiceDiscovery(msg: AapMessage) {
        if (state != State.DISCOVERY) {
            fail("service discovery response in state $state", null)
            return
        }
        val resp = try {
            ServiceDiscoveryResponse.parseFrom(msg.bodyBytes())
        } catch (e: com.google.protobuf.InvalidProtocolBufferException) {
            // Say what broke instead of a bare "read failed" (a required field we can't read, usually).
            fail("bad service discovery response", e)
            return
        }
        discovery = resp
        log.i("head unit: ${describe(resp)}")
        resp.connectionConfiguration?.pingConfiguration?.let { pc ->
            if (pc.hasIntervalMs() && pc.intervalMs > 0) pingIntervalMs = pc.intervalMs.toLong().coerceIn(250, 5_000)
            if (pc.hasTimeoutMs() && pc.timeoutMs > 0) deadAfterMs = maxOf(pc.timeoutMs.toLong() * 3, 20_000L)
        }
        val wanted = callbacks.onServiceDiscovery(this, resp)
        state = State.OPENING
        pendingOpens = wanted.size
        for (ch in wanted) {
            ch.attach(this)
            channels[ch.id] = ch
        }
        // The sensor channel first, the others after it (once its
        // startup checks pass; here once the head unit has answered the sensor channel, or after
        // DEFERRED_OPEN_MS if it doesn't).
        val sensors = wanted.filter { it.service.hasSensorSourceService() }
        if (sensors.isEmpty() || sensors.size == wanted.size) {
            wanted.forEach(::requestOpen)
        } else {
            deferredOpens.set(wanted - sensors.toSet())
            sensors.forEach(::requestOpen)
            schedule({ openDeferred() }, DEFERRED_OPEN_MS, TimeUnit.MILLISECONDS)
        }
        if (wanted.isEmpty()) {
            enterRunning()
        } else {
            // A head unit that never answers one open request must not keep the session out of RUNNING.
            schedule({
                if (state != State.OPENING) return@schedule
                log.w("channels not open after 10 s: ${channels.values.filter { !it.opened }.joinToString { "${it.id} (${it.name})" }}")
                enterRunning()
            }, 10, TimeUnit.SECONDS)
        }
        startKeepAlive()
    }

    private fun requestOpen(ch: ServiceChannel) {
        awaitingOpen.add(ch.id)
        send(ch.id, ControlMsg.CHANNEL_OPEN_REQUEST,
            ChannelOpenRequest.newBuilder().setPriority(ch.openPriority).setServiceId(ch.id).build(),
            control = true)
    }

    /** Opens the channels held back for the sensor channel, once (reader or timer thread). */
    private fun openDeferred() {
        if (closed.get()) return
        deferredOpens.getAndSet(null)?.forEach(::requestOpen)
    }

    /** From the reader or, on an open timeout, the timer: [Callbacks.onRunning] must still run once. */
    private fun enterRunning() {
        if (state != State.OPENING || !runningEntered.compareAndSet(false, true)) return
        state = State.RUNNING
        log.i("session running: ${channels.values.count { it.opened }}/${channels.size} channels open")
        callbacks.onRunning(this)
    }

    /** Whether the head unit asked for at least this protocol version. */
    fun headUnitAtLeast(major: Int, minor: Int): Boolean {
        val (m, n) = headUnitVersion ?: return false
        return m > major || (m == major && n >= minor)
    }

    private fun startKeepAlive() {
        scheduler.scheduleWithFixedDelay({
            if (closed.get()) return@scheduleWithFixedDelay
            if (options.sendPings) {
                sendControl(ControlMsg.PING_REQUEST, PingRequest.newBuilder().setTimestamp(System.nanoTime() / 1_000).build())
            }
            val silent = now() - lastInboundMs
            if (silent > (if (pingEvidence) deadAfterMs else 60_000L)) fail("head unit silent for $silent ms", null)
        }, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS)
    }

    // ------------------------------------------------------------------ teardown

    private fun fail(reason: String, error: Throwable?) {
        if (closed.get()) return
        headUnitByeBye?.let { close(it, null); return }
        log.w("session failed: $reason (${linkStats(now(), lastInboundMs, lastOutboundMs, writingSinceMs, slowestWriteMs, queue.size)})", error)
        close(reason, error ?: AapException(reason))
    }

    private fun close(reason: String, error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        closedDuring = state
        if (state == State.HANDSHAKE && tlsStarted && !cryptor.peerRefused && certificateRefusal == null)
            certificateRefusal = CertificateRefusal(0, explicit = CertificateRefusal.saidByHeadUnit(error))
        state = State.CLOSED
        // The engine only reports a failed read: say what actually happened.
        val why = cryptor.pinRefusal?.let { "head unit certificate not trusted (SHA-256 ${it.fingerprint})" }
            ?: "head unit sent no certificate: AARE only connects to head units that present one".takeIf { cryptor.peerCertificateMissing }
            ?: reason
        log.i("session closed: $why")
        try {
            // A throwing transport must not skip the channels' and the owner's teardown.
            runCatching { transport.close() }.onFailure { log.w("closing ${transport.name} failed", it) }
            for (ch in channels.values) runHandler(ch) { ch.onClosed() }
            queue.clear()
            callbacks.onClosed(this, why, error)
        } finally {
            // Last: close() often runs on the timer thread itself (timeouts, stop grace), and
            // shutdownNow() interrupts it, which would break any interruptible call made after it.
            scheduler.shutdownNow()
        }
    }

    val isClosed get() = closed.get()

    /**
     * Schedules a one-shot task on the session's timer thread; silently dropped once the session
     * has shut its timer down.
     */
    internal fun schedule(task: () -> Unit, delay: Long, unit: TimeUnit) {
        try {
            scheduler.schedule(task, delay, unit)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    companion object {
        const val PROTOCOL_MAJOR = 1
        const val PROTOCOL_MINOR = 7
        /** Longest wait for the sensor channel's answer before opening the other channels. */
        private const val DEFERRED_OPEN_MS = 1_000L

        private fun now() = System.nanoTime() / 1_000_000

        /**
         * The link's last moments, for a failed session's log line: tells a link that died while
         * idle from one that died while a write was stuck or the queue was backing up.
         */
        internal fun linkStats(nowMs: Long, lastInMs: Long, lastOutMs: Long, writingSinceMs: Long, slowestWriteMs: Long, queued: Int): String =
            buildString {
                append("last in ${nowMs - lastInMs} ms ago, last out ${nowMs - lastOutMs} ms ago")
                if (writingSinceMs >= 0) append(", a write blocked for ${nowMs - writingSinceMs} ms")
                append(", slowest write ${slowestWriteMs} ms, $queued queued")
            }

        fun describe(r: ServiceDiscoveryResponse): String {
            val hu = r.headunitInfo
            val name = listOf(hu.make.ifEmpty { r.make }, hu.model.ifEmpty { r.model }, hu.headUnitMake.ifEmpty { r.headUnitMake },
                hu.headUnitModel.ifEmpty { r.headUnitModel }).filter { it.isNotEmpty() }.joinToString(" ")
            val services = r.channelsList.joinToString { serviceKind(it) + "#" + it.id }
            return "$name [${r.displayName}] services: $services"
        }

        /**
         * Whether a channel open response accepts the channel. Only SUCCESS
         * does, so a status this build doesn't know (which protobuf keeps as an unknown field and
         * would read as the SUCCESS default) leaves the channel closed. A response with no status
         * at all still counts as SUCCESS: proto3 head units (Ford SYNC 4, #74) leave the 0 out.
         */
        internal fun openAccepted(body: ByteArray): Boolean {
            val resp = ChannelOpenResponse.parseFrom(body)
            if (resp.hasStatus()) return resp.status == MessageStatus.STATUS_SUCCESS
            val input = CodedInputStream.newInstance(body)
            while (true) {
                val tag = input.readTag()
                if (tag == 0) return true
                if (tag ushr 3 == 1) return false
                input.skipField(tag)
            }
        }

        fun serviceKind(s: Service): String = when {
            s.hasMediaSinkService() -> if (s.mediaSinkService.videoConfigsCount > 0) "video" else "audio:${s.mediaSinkService.audioType}"
            s.hasMediaSourceService() -> "mic"
            s.hasInputSourceService() -> "input"
            s.hasSensorSourceService() -> "sensors"
            s.hasBluetoothService() -> "bluetooth"
            s.hasNavigationStatusService() -> "navstatus"
            s.hasMediaPlaybackService() -> "playback"
            s.hasPhoneStatusService() -> "phonestatus"
            s.hasMediaBrowserService() -> "mediabrowser"
            s.hasVendorExtensionService() -> "vendor"
            s.hasGenericNotificationService() -> "notifications"
            s.hasWifiProjectionService() -> "wifi"
            s.hasRadioService() -> "radio"
            else -> "unknown"
        }
    }
}

/**
 * A service channel the phone opened. Subclasses implement one service (video, audio, input...).
 */
abstract class ServiceChannel(val service: Service, val name: String) {
    val id: Int get() = service.id
    lateinit var session: AapSession
        private set
    @Volatile var opened = false
        internal set

    /** Priority sent in the channel open request (the head unit mostly ignores it). */
    open val openPriority: Int = 0

    internal fun attach(s: AapSession) { session = s }

    /** The head unit accepted the channel. Start the service's own handshake here. */
    open fun onOpened() {}

    /** A message on this channel. Called on the session's reader thread: don't block. */
    abstract fun onMessage(msg: AapMessage)

    /** The session ended or the head unit closed the channel. */
    open fun onClosed() {}

    protected fun send(msgId: Int, body: MessageLite, priority: Int = Priority.CONTROL) =
        session.send(id, msgId, body, priority = priority)
}
