package net.prototux.aare.aap.wireless

import aap_protobuf.aaw.StatusOuterClass.Status
import aap_protobuf.aaw.WifiConnectionRejectionOuterClass.WifiConnectionRejection
import aap_protobuf.aaw.WifiConnectionStatusOuterClass.WifiConnectionStatus
import aap_protobuf.aaw.WifiInfoResponseOuterClass.WifiInfoResponse
import aap_protobuf.aaw.WifiPing.WifiPingRequest
import aap_protobuf.aaw.WifiStartRequestOuterClass.WifiStartRequest
import aap_protobuf.aaw.WifiStartResponseOuterClass.WifiStartResponse
import aap_protobuf.aaw.WifiVersionRequestOuterClass.WifiVersionRequest
import aap_protobuf.aaw.WifiVersionResponseOuterClass.WifiVersionResponse
import com.google.protobuf.InvalidProtocolBufferException
import net.prototux.aare.aap.AapLog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The access point the head unit asked us to join. [toString] leaves the password out. */
class WifiCredentials(
    val ssid: String,
    val password: String,
    /** "AA:BB:CC:DD:EE:FF", or null when the head unit sent none (some send an empty string). */
    val bssid: String?,
    val security: WifiSecurity,
    /** The raw wire value, see [WppSecurityMode]. */
    val securityMode: Int,
    /** 0 STATIC (the phone may remember the network), 1 DYNAMIC; null when absent. */
    val accessPointType: Int?,
) {
    override fun toString() =
        "ssid=\"$ssid\" bssid=${bssid ?: "-"} security=$security(mode $securityMode) apType=${accessPointType ?: "-"} " +
            "password=${if (password.isEmpty()) "none" else "${password.length} chars"}"
}

/** Outcome of [WirelessBootstrap.Listener.connectWifi]. */
sealed interface JoinResult {
    /** The phone is on the network. [gateway] stands in for the head unit if it sent no IP. */
    class Joined(val localAddress: String? = null, val gateway: String? = null) : JoinResult

    /** [status] is a [WppStatus] code reported to the head unit, e.g. [WppStatus.PHONE_WIFI_DISABLED]. */
    class Failed(val status: Int, val message: String) : JoinResult
}

class WirelessBootstrapException(
    message: String,
    val status: Int = WppStatus.WIFI_NETWORK_UNAVAILABLE,
    cause: Throwable? = null,
    /**
     * Failed before the head unit told us its endpoint and network (it hung up, went silent,
     * or sent something unusable): a car that isn't ready yet, so trying again later can work.
     */
    val whileNegotiating: Boolean = false,
) : Exception(message, cause)

/**
 * Phone side of the Bluetooth RFCOMM bootstrap of wireless Android Auto: learn the head unit's
 * AAP endpoint and access point, join it, report back. Blocking; call [run] on a dedicated thread.
 *
 * The orders seen in the wild are all accepted: version request then start request (openauto
 * sends both back to back, real cars too), a bare start request (WirelessAndroidAutoDongle, which
 * aborts unless the next thing it reads is our WifiInfoRequest), and credentials sent without
 * being asked. The head unit is only told "success" once [Listener.connectWifi] says the phone
 * really is on the network.
 *
 * Threads: a reader thread answers pings at once and queues everything else for [run]; while
 * the phone joins the network a keepalive thread pings the head unit, because some cars give up
 * on a silent RFCOMM link within ~15 s and the user may still be looking at Android's
 * "connect to device" dialog.
 */
class WirelessBootstrap(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: Listener,
    private val log: AapLog,
    private val options: Options = Options(),
) {
    interface Listener {
        /**
         * Join the head unit's access point and block until the phone has an address on it, or
         * it failed. Called on the [run] thread; it must bound its own wait (the head unit will
         * not wait forever either, ~45 s on open-headunit and aa-proxy-rs).
         */
        fun connectWifi(credentials: WifiCredentials): JoinResult

        /**
         * The head unit was told we are on its network: open the AAP TCP connection to [ip]:[port]
         * now. Called on the [run] thread, which afterwards keeps the RFCOMM link alive (see
         * [Options.holdLink]), so hand the connect off to another thread or return promptly.
         */
        fun onReady(ip: String, port: Int)
    }

    class Options(
        /** Longest silence from the head unit while we still need something from it. */
        val messageTimeoutMs: Long = 15_000,
        /** Re-send WifiInfoRequest once if the credentials have not come after this long. */
        val infoRetryMs: Long = 5_000,
        /** Ping interval while joining the network; 0 disables. aa-proxy-rs uses 2 s for picky cars. */
        val keepaliveIntervalMs: Long = 2_000,
        /**
         * Minimum spacing between our frames. openauto parses at most one frame per socket read,
         * so two frames that arrive together leave the second one stuck in its buffer.
         */
        val frameGapMs: Long = 100,
        /**
         * After [Listener.onReady], keep reading (answering pings) until the link closes or
         * [close] is called, as the official phone does: open-headunit holds the channel for the
         * whole session and Android Auto re-dials a dropped one. If false, [run] returns right
         * after [Listener.onReady] and the reader keeps answering pings until [close].
         */
        val holdLink: Boolean = true,
        /** Sent as device_serial in WifiVersionResponse, like the official phone; null to omit. */
        val deviceSerial: String? = null,
    )

    enum class State { NEW, NEGOTIATING, JOINING, READY, DONE, FAILED }

    @Volatile var state = State.NEW
        private set

    private sealed class Inbound {
        class Message(val frame: WppFrame) : Inbound()
        class End(val error: Throwable?) : Inbound()
    }

    private val inbox = LinkedBlockingQueue<Inbound>()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private var lastWriteNs = 0L
    /** Set once the head unit hung up; later sends are skipped. */
    @Volatile private var linkEnded = false

    /**
     * Runs the bootstrap: returns once the link ended after a successful [Listener.onReady] (or
     * right after it, without [Options.holdLink]). Throws [WirelessBootstrapException] when the
     * bootstrap fails, after telling the head unit why where that makes sense; the streams are
     * closed then.
     */
    fun run() {
        check(started.compareAndSet(false, true)) { "WirelessBootstrap.run() called twice" }
        thread(name = "wpp-reader", isDaemon = true) { readLoop() }
        try {
            state = State.NEGOTIATING
            val (start, credentials) = negotiate()
            state = State.JOINING
            val joined = join(credentials)
            val ip = start.ipAddress.trim().takeUnless { it.isEmpty() || it == "0.0.0.0" } ?: joined.gateway
            if (ip.isNullOrEmpty()) {
                sendQuietly(WppMessageId.START_RESPONSE, startResponse(null, WppStatus.INVALID_HOST))
                throw fail("head unit sent no IP address and the network has no gateway", WppStatus.INVALID_HOST)
            }
            if (ip != start.ipAddress) log.w("wireless: head unit sent no usable IP (\"${start.ipAddress}\"), using the gateway $ip")

            // Order as the official phone (aa-proxy-rs and open-headunit both expect 7 then 6). The
            // dongle hangs up after reading two frames, so failing to send these is not fatal: the
            // head unit already knows everything it needs and the TCP connect is the real test.
            sendQuietly(WppMessageId.START_RESPONSE, startResponse(joined.localAddress, WppStatus.SUCCESS))
            sendQuietly(WppMessageId.CONNECTION_STATUS, connectionStatus(WppStatus.SUCCESS, null))
            ensureOpen()
            state = State.READY
            log.i("wireless: on the head unit's network, AAP server at $ip:${start.port}")
            listener.onReady(ip, start.port)
            if (options.holdLink) hold()
            state = State.DONE
        } catch (e: Throwable) {
            state = State.FAILED
            close()
            if (e is WirelessBootstrapException) throw e
            throw WirelessBootstrapException("wireless bootstrap failed: ${e.message}", cause = e)
        }
    }

    /** Ends the bootstrap or the held link: closes both streams, which unblocks [run]. Idempotent. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { input.close() }
        runCatching { output.close() }
        // offer, not put: the queue is unbounded, and put() throws when close() runs on an
        // interrupted thread (an AAP session closing from its own timer).
        inbox.offer(Inbound.End(null))
    }

    // ---- phase 1: learn the endpoint and the credentials ----

    /** Waits until we have both the start request and the credentials, whatever their order. */
    private fun negotiate(): Pair<WifiStartRequest, WifiCredentials> {
        var start: WifiStartRequest? = null
        var credentials: WifiCredentials? = null
        var infoRequestedAt = 0L
        var infoRetried = false
        var deadline = now() + options.messageTimeoutMs

        while (start == null || credentials == null) {
            val retryAt = if (infoRequestedAt != 0L && !infoRetried) infoRequestedAt + options.infoRetryMs else Long.MAX_VALUE
            val event = inbox.poll(maxOf(0, minOf(deadline, retryAt) - now()), TimeUnit.MILLISECONDS)
            ensureOpen()
            if (event == null) {
                if (now() >= deadline) {
                    val waitingFor = when {
                        start == null && credentials == null -> "a WifiStartRequest (is a projection already running on the head unit?)"
                        start == null -> "a WifiStartRequest after the credentials"
                        else -> "a WifiInfoResponse"
                    }
                    throw fail("timed out after ${options.messageTimeoutMs} ms waiting for $waitingFor")
                }
                if (now() >= retryAt) {
                    // Also nudges openauto if our first request got stuck behind another frame.
                    log.w("wireless: no credentials yet, asking again")
                    send(WppMessageId.INFO_REQUEST, ByteArray(0))
                    infoRetried = true
                }
                continue
            }
            val frame = when (event) {
                is Inbound.End -> throw fail(
                    "head unit closed the Bluetooth link before sending " +
                        if (start == null) "its WifiStartRequest" else "the Wi-Fi credentials",
                    cause = event.error,
                )
                is Inbound.Message -> event.frame
            }
            when (frame.id) {
                WppMessageId.VERSION_REQUEST -> {
                    answerVersionRequest(frame.payload)
                    deadline = now() + options.messageTimeoutMs
                }
                WppMessageId.START_REQUEST -> {
                    val request = parse("WifiStartRequest") { WifiStartRequest.parseFrom(frame.payload) }
                    log.i("wireless: head unit AAP server ${request.ipAddress}:${request.port}" +
                        if (request.hasReason()) " (reason ${request.reason})" else "")
                    if (request.port !in 1..65535) {
                        sendQuietly(WppMessageId.START_RESPONSE, startResponse(null, WppStatus.INVALID_HOST))
                        throw fail("head unit sent an invalid port ${request.port}", WppStatus.INVALID_HOST)
                    }
                    if (start != null) log.w("wireless: repeated WifiStartRequest, using the latest one")
                    start = request
                    // A phone that already has the credentials skips the info request.
                    if (credentials == null && infoRequestedAt == 0L) {
                        send(WppMessageId.INFO_REQUEST, ByteArray(0))
                        infoRequestedAt = now()
                    }
                    deadline = now() + options.messageTimeoutMs
                }
                WppMessageId.INFO_RESPONSE -> {
                    credentials = parseCredentials(frame.payload)
                    log.i("wireless: credentials $credentials" + if (start == null) " (unsolicited, waiting for WifiStartRequest)" else "")
                    deadline = now() + options.messageTimeoutMs
                }
                else -> onOtherMessage(frame)
            }
        }
        return start to credentials
    }

    private fun answerVersionRequest(payload: ByteArray) {
        val request = runCatching { WifiVersionRequest.parseFrom(payload) }.getOrNull()
        if (request == null) {
            log.w("wireless: unparsable WifiVersionRequest (${payload.size} B), answering anyway")
        } else {
            val car = request.carInfo
            log.i("wireless: head unit WPP version ${request.major}.${request.minor}" +
                (if (request.hasCarInfo()) " car=\"${car.make} ${car.model}\" hu=\"${car.headUnitMake} ${car.headUnitModel}\"" else "") +
                (if (request.hasSupportedWifiChannelType()) " channelType=${request.supportedWifiChannelType}" else "") +
                (if (request.hasWppInfo()) " (offers WPP over TCP at ${request.wppInfo.ipAddress}:${request.wppInfo.port}, unsupported)" else ""))
        }
        // Gearhead's answer: echo the version, the device serial, and the Wi-Fi channel type the
        // head unit said it supports. Some strict OEM head units (e.g. Toyota, #46) withhold the
        // WifiStartRequest until they get a satisfactory response, not just a bare SUCCESS.
        val response = WifiVersionResponse.newBuilder()
            .setMajor(request?.takeIf { it.hasMajor() }?.major ?: 1)
            .setMinor(request?.takeIf { it.hasMinor() }?.minor ?: 0)
            .setStatus(WppStatus.SUCCESS)
        options.deviceSerial?.let { response.setDeviceSerial(it) }
        request?.takeIf { it.hasSupportedWifiChannelType() }?.let { response.setSelectedWifiChannelType(it.supportedWifiChannelType) }
        send(WppMessageId.VERSION_RESPONSE, response.build().toByteArray())
    }

    private fun parseCredentials(payload: ByteArray): WifiCredentials {
        val info = parse("WifiInfoResponse") { WifiInfoResponse.parseFrom(payload) }
        val bssid = info.bssid.trim().takeIf { MAC.matches(it) && it != "00:00:00:00:00:00" }
        if (info.bssid.isNotBlank() && bssid == null) log.w("wireless: ignoring malformed BSSID \"${info.bssid}\"")
        return WifiCredentials(
            ssid = info.ssid,
            password = info.password,
            bssid = bssid?.uppercase(),
            security = WppSecurityMode.toSecurity(info.securityMode, info.password),
            securityMode = info.securityMode,
            accessPointType = if (info.hasAccessPointType()) info.accessPointType.number else null,
        )
    }

    // ---- phase 2: join the network ----

    private fun join(credentials: WifiCredentials): JoinResult.Joined {
        val invalid = when {
            credentials.ssid.isEmpty() -> "head unit sent an empty SSID"
            credentials.security == WifiSecurity.UNSUPPORTED -> "unsupported Wi-Fi security mode ${credentials.securityMode}"
            // An open network (or one without a passphrase) lets anyone in range impersonate the head unit.
            credentials.security == WifiSecurity.OPEN -> "refusing an open Wi-Fi network (security mode ${credentials.securityMode})"
            credentials.password.isEmpty() -> "head unit sent no Wi-Fi passphrase"
            else -> null
        }
        val result = if (invalid != null) {
            JoinResult.Failed(WppStatus.WIFI_INCORRECT_CREDENTIALS, invalid)
        } else {
            val keepalive = Keepalive().also { it.start() }
            try {
                listener.connectWifi(credentials)
            } catch (e: Exception) {
                log.w("wireless: joining \"${credentials.ssid}\" threw", e)
                JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, e.message ?: e.toString())
            } finally {
                keepalive.stop()
            }
        }
        ensureOpen()
        return when (result) {
            is JoinResult.Joined -> {
                log.i("wireless: joined \"${credentials.ssid}\" as ${result.localAddress ?: "?"} (gateway ${result.gateway ?: "?"})")
                result
            }
            is JoinResult.Failed -> {
                // Only type 6 means "the phone could not get onto the network"; a failed type 7
                // would tell the head unit its endpoint was refused.
                sendQuietly(WppMessageId.CONNECTION_STATUS, connectionStatus(result.status, result.message))
                Thread.sleep(FAIL_LINGER_MS) // let the status leave before the caller drops the link
                throw fail("could not join \"${credentials.ssid}\": ${result.message}", result.status)
            }
        }
    }

    /** Pings the head unit while [Listener.connectWifi] blocks. */
    private inner class Keepalive {
        private val stopped = CountDownLatch(1)

        fun start() {
            if (options.keepaliveIntervalMs <= 0) return
            thread(name = "wpp-keepalive", isDaemon = true) {
                while (!stopped.await(options.keepaliveIntervalMs, TimeUnit.MILLISECONDS) && !linkEnded && !closed.get()) {
                    val ping = WifiPingRequest.newBuilder().setTimestamp(System.currentTimeMillis()).build()
                    sendQuietly(WppMessageId.PING_REQUEST, ping.toByteArray())
                }
            }
        }

        fun stop() = stopped.countDown()
    }

    // ---- phase 3: keep the link while the session runs ----

    private fun hold() {
        while (true) {
            when (val event = inbox.take()) {
                is Inbound.End -> {
                    if (!closed.get()) log.i("wireless: head unit closed the Bluetooth link" + (event.error?.let { " ($it)" } ?: ""))
                    return
                }
                is Inbound.Message -> when (event.frame.id) {
                    WppMessageId.INFO_RESPONSE, WppMessageId.PING_RESPONSE -> log.d("wireless: late ${event.frame}")
                    else -> onOtherMessage(event.frame)
                }
            }
        }
    }

    /** Messages that do not drive the exchange; pings never get here (see [readLoop]). */
    private fun onOtherMessage(frame: WppFrame) {
        when (frame.id) {
            WppMessageId.PING_RESPONSE -> log.d("wireless: ping answered")
            WppMessageId.CONNECTION_REJECTION -> {
                val reason = runCatching { WifiConnectionRejection.parseFrom(frame.payload).reason }.getOrNull()
                if (state == State.READY) log.w("wireless: head unit sent a connection rejection (reason $reason)")
                else throw fail("head unit rejected the connection (reason $reason)", WppStatus.INSTRUCT_USER_TO_CHECK_THE_PHONE)
            }
            WppMessageId.START_REQUEST, WppMessageId.VERSION_REQUEST ->
                log.w("wireless: ignoring ${WppMessageId.name(frame.id)} during the session")
            else -> log.i("wireless: ignoring $frame")
        }
    }

    // ---- I/O ----

    private fun readLoop() {
        var error: Throwable? = null
        try {
            while (true) {
                val frame = readWppFrame(input) ?: break
                log.d("wireless: <- $frame")
                if (frame.id == WppMessageId.PING_REQUEST) {
                    // Answered here so pings are answered even while connectWifi() blocks.
                    sendQuietly(WppMessageId.PING_RESPONSE, frame.payload)
                } else {
                    inbox.offer(Inbound.Message(frame))
                }
            }
        } catch (e: IOException) {
            if (!closed.get()) error = e
        }
        linkEnded = true
        inbox.offer(Inbound.End(error))
    }

    private fun send(id: Int, payload: ByteArray) {
        if (linkEnded) throw IOException("link closed")
        synchronized(writeLock) {
            val waitMs = (lastWriteNs + options.frameGapMs * 1_000_000 - System.nanoTime()) / 1_000_000
            if (lastWriteNs != 0L && waitMs > 0) Thread.sleep(waitMs)
            log.d("wireless: -> ${WppMessageId.name(id)} (${payload.size} B)")
            writeWppFrame(output, id, payload)
            lastWriteNs = System.nanoTime()
        }
    }

    /** Sends unless the link is gone; for messages whose loss must not end the bootstrap. */
    private fun sendQuietly(id: Int, payload: ByteArray) {
        if (linkEnded || closed.get()) return
        try {
            send(id, payload)
        } catch (e: IOException) {
            if (!closed.get()) log.w("wireless: could not send ${WppMessageId.name(id)}: $e")
        }
    }

    private fun startResponse(localAddress: String?, status: Int): ByteArray =
        WifiStartResponse.newBuilder()
            // "the address it will connect from" (open-headunit); head units only log it
            .apply { if (localAddress != null) ipAddress = localAddress }
            .setStatus(Status.forNumber(status) ?: Status.STATUS_WIFI_NETWORK_UNAVAILABLE)
            .build().toByteArray()

    private fun connectionStatus(status: Int, message: String?): ByteArray =
        WifiConnectionStatus.newBuilder()
            .setStatus(Status.forNumber(status) ?: Status.STATUS_WIFI_NETWORK_UNAVAILABLE)
            .apply { if (message != null) errorMessage = message }
            .build().toByteArray()

    private inline fun <T> parse(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: InvalidProtocolBufferException) {
            throw fail("malformed $what from the head unit", cause = e)
        }

    private fun ensureOpen() {
        if (closed.get()) throw WirelessBootstrapException("wireless bootstrap cancelled")
    }

    private fun fail(message: String, status: Int = WppStatus.WIFI_NETWORK_UNAVAILABLE, cause: Throwable? = null) =
        WirelessBootstrapException(message, status, cause, whileNegotiating = state == State.NEGOTIATING)
            .also { log.e("wireless: $message", cause) }

    private fun now() = System.nanoTime() / 1_000_000

    private companion object {
        val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        const val FAIL_LINGER_MS = 300L
    }
}
