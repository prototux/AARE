package net.prototux.aare.transport.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.InetAddresses
import android.net.Network
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.PinStore
import net.prototux.aare.aap.SocketTransport
import net.prototux.aare.aap.Transport
import net.prototux.aare.aap.wireless.AA_WIRELESS_UUID
import net.prototux.aare.aap.wireless.JoinResult
import net.prototux.aare.aap.wireless.WifiCredentials
import net.prototux.aare.aap.wireless.WirelessBootstrap
import net.prototux.aare.aap.wireless.WppEndpoint
import net.prototux.aare.aap.wireless.WppSetup
import net.prototux.aare.aap.wireless.WppTls
import net.prototux.aare.aap.wireless.WppVersion
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Wireless connection setup failed; [message] says at which step. */
class WirelessException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Runtime permissions the user has not granted (yet). Request [missing] and try again. */
class WirelessPermissionException(val missing: List<String>) :
    SecurityException("wireless Android Auto needs the permission(s): ${missing.joinToString { it.substringAfterLast('.') }}")

/**
 * Wireless Android Auto, phone side: Bluetooth RFCOMM to the head unit's Android Auto service,
 * the [WirelessBootstrap] exchange over it, joining the head unit's Wi-Fi, then a TCP connection
 * to its AAP server. The phone is the client at every step. The head unit must be bonded (paired)
 * and is usually already connected for calls (HFP) when this runs.
 *
 * With [Options.wppTcp], on Android 11+, a head unit that offered WPP over TCP gets the same exchange
 * over TLS on its network instead (see [WppTcpConfigs], docs/decisions.md).
 */
@SuppressLint("MissingPermission") // every entry point calls requirePermissions()
class WirelessConnector(context: Context, private val log: AapLog, private val options: Options = Options()) {

    class Options(
        val rfcommAttempts: Int = 3,
        /** Joining the head unit's network, including the time the user takes to approve it. */
        val joinTimeoutMs: Long = 40_000,
        /**
         * Joining a stored network for WPP over TCP: short, since Bluetooth is the way back when the
         * car's access point is not there.
         */
        val tcpJoinTimeoutMs: Long = 10_000,
        /** Gearhead: 10 s, 3 tries. The head unit's server may come up a bit after its Wi-Fi. */
        val tcpConnectTimeoutMs: Int = 10_000,
        val tcpAttempts: Int = 3,
        /** Pin the BSSID the head unit sent: lets Android skip the approval dialog next time. */
        val matchBssid: Boolean = true,
        val bootstrap: WirelessBootstrap.Options = WirelessBootstrap.Options(),
        /** What WPP over TCP needs; null (the setting off) runs WPP over RFCOMM only, storing nothing. */
        val wppTcp: WppTcp? = null,
    )

    /**
     * WPP over TCP's TLS: [identity] is the phone certificate of the AAP session (also used for the
     * same), [pins] the trusted head unit certificates (see [WppTls]).
     */
    class WppTcp(val identity: () -> PhoneIdentity, val pins: PinStore)

    private val context = context.applicationContext
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val tcpConfigs = WppTcpConfigs(this.context)
    /** WPP over TCP runs from Android 11 on only. */
    private val wppOverTcp = options.wppTcp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Bonded devices that publish the Android Auto wireless service. Devices whose services are
     * not known yet get an SDP query (answered later by ACTION_UUID), so a head unit paired a
     * moment ago may only show up on the next call; [refresh] queries every bonded device, for
     * head units that enabled wireless after pairing (phones cache SDP results).
     */
    fun findHeadUnits(refresh: Boolean = false): List<BluetoothDevice> {
        requirePermissions(bluetoothPermissions())
        val adapter = adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices.orEmpty().filter { device ->
            if (refresh || device.uuids.isNullOrEmpty()) device.fetchUuidsWithSdp()
            isHeadUnit(device)
        }
    }

    /**
     * Bonded head units offering wireless Android Auto that are already connected over Bluetooth.
     * For the case where the car was connected before AARE could react to the ACL-connect
     * broadcast (#56): we can't wait for an edge that already passed, so we look at the current
     * state. Uses the hidden isConnected(); if it can't be read, returns empty and the normal
     * ACL-connect path is unaffected.
     */
    @SuppressLint("MissingPermission")
    fun findConnectedHeadUnits(): List<BluetoothDevice> {
        requirePermissions(bluetoothPermissions())
        val adapter = adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices.orEmpty().filter { isHeadUnit(it) && isConnected(it) }
    }

    private fun isConnected(device: BluetoothDevice): Boolean = runCatching {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
    }.getOrDefault(false)

    /**
     * Connects to [device] and returns the AAP byte pipe. Cancelling the coroutine aborts the
     * attempt and releases everything it set up. Must run while the app is in the foreground (an
     * activity or a foreground service): Android refuses Wi-Fi requests from background apps.
     */
    suspend fun connect(device: BluetoothDevice): Transport {
        val attempt = Attempt(device)
        val result = CompletableDeferred<Transport>()
        thread(name = "aa-wireless-connect", isDaemon = true) {
            try {
                result.complete(attempt.run())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        try {
            return result.await()
        } catch (e: CancellationException) {
            attempt.abort()
            throw e
        }
    }

    /** Blocking variant of [connect], for callers that already have a worker thread. */
    fun connectBlocking(device: BluetoothDevice): Transport = Attempt(device).run()

    /** Permissions [connect] needs on this Android version; the UI can request the missing ones. */
    fun requiredPermissions(): List<String> = bluetoothPermissions() + wifiPermissions()

    fun missingPermissions(): List<String> = requiredPermissions().filter { !granted(it) }

    /** A wireless adapter got its one quick first try (see [WirelessBootstrap.isDongle]); for this connector's attempts. */
    private val dongleQuickTryUsed = AtomicBoolean(false)

    // ---- one connection attempt ----

    /** How a [WirelessBootstrap] run ended for the connector. */
    private sealed interface Outcome {
        class Ready(val ip: String, val port: Int) : Outcome
        class MovingToTcp(val setup: WppSetup) : Outcome
    }

    private inner class Attempt(private val device: BluetoothDevice) {
        private val aborted = AtomicBoolean(false)
        @Volatile private var rfcomm: BluetoothSocket? = null
        @Volatile private var bootstrap: WirelessBootstrap? = null
        @Volatile private var network: HeadUnitNetwork? = null
        /** The TLS socket of WPP over TCP. */
        @Volatile private var wpp: Socket? = null
        @Volatile private var tcp: Socket? = null
        @Volatile private var transport: Transport? = null

        fun run(): Transport {
            requirePermissions(requiredPermissions())
            val adapter = adapter ?: throw WirelessException("this phone has no Bluetooth")
            if (!adapter.isEnabled) throw WirelessException("Bluetooth is turned off")
            // A secure RFCOMM connect to an unbonded device starts pairing, and the session trusts the head
            // unit's certificate on first use because the user bonded it. BONDING: the user is pairing it now.
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                throw WirelessException("${device.address} is not paired: pair the head unit in Bluetooth settings first")
            }
            try {
                // A head unit with a stored TCP configuration gets WPP over TCP, no RFCOMM.
                if (wppOverTcp) tcpConfigs.get(device.address)?.let { config -> overTcp(config, setupToken = null)?.let { return it } }
                // Over RFCOMM; a second run after a WifiSetupInfo whose TCP attempt failed, which then
                // carries on over RFCOMM (that head unit's next WifiSetupInfo is declined, see onSetupInfo).
                repeat(2) {
                    val socket = openRfcomm(adapter)
                    val net = newNetwork(options.joinTimeoutMs)
                    when (val outcome = bootstrap(socket.inputStream, socket.outputStream, net, link = null)) {
                        is Outcome.Ready -> return aap(net, outcome.ip, outcome.port)
                        is Outcome.MovingToTcp -> {
                            // The bootstrap closed RFCOMM and joined nothing; start over on TCP at once.
                            runCatching { socket.close() }
                            net.release()
                            val setup = outcome.setup
                            val config = WppTcpConfig(setup.endpoint, setup.credentials, setup.version)
                            tcpConfigs.put(device.address, config)
                            overTcp(config, setup.setupToken)?.let { return it }
                            log.w("wireless: WPP over TCP failed after the head unit asked for it; Bluetooth again")
                        }
                    }
                }
                throw WirelessException("the head unit asked for WPP over TCP again after it failed")
            } catch (e: Throwable) {
                // Only an outside abort turns the worker's socket errors into "cancelled": read it before marking.
                val wasAborted = aborted.getAndSet(true)
                // Not abort(): if it already ran, it missed what this thread opened after it (between a
                // checkNotAborted() and the field write), and every such path ends here.
                releaseAll()
                if (wasAborted && e !is WirelessException) throw WirelessException("cancelled", e)
                throw e
            }
        }

        /** Stops the attempt and releases everything it opened. Idempotent. */
        fun abort() {
            if (aborted.compareAndSet(false, true)) releaseAll()
        }

        /**
         * Releases TCP, the WPP link (RFCOMM or TLS) and the network request (last, so a blocked join()
         * wakes up). Safe to call more than once: every close is idempotent.
         */
        private fun releaseAll() {
            transport?.let { runCatching { it.close() } }
            tcp?.let { runCatching { it.close() } }
            bootstrap?.close()
            wpp?.let { runCatching { it.close() } }
            rfcomm?.let { runCatching { it.close() } }
            network?.release()
        }

        private fun newNetwork(timeoutMs: Long) = HeadUnitNetwork(context, log, timeoutMs, options.matchBssid).also { network = it }

        /** The AAP connection on the head unit's network, once WPP told us where. */
        private fun aap(net: HeadUnitNetwork, ip: String, port: Int): Transport {
            val headUnitNetwork = net.network ?: throw WirelessException("lost the head unit's network")
            val tcpSocket = connectTcp(headUnitNetwork, ip, port) { tcp = it }
            val name = "wifi \"${net.ssid}\" $ip:$port (${runCatching { device.name }.getOrNull() ?: device.address})"
            val t = WirelessTransport(SocketTransport(tcpSocket, name), ::abort)
            transport = t
            // A dead Wi-Fi link otherwise only shows up as a TCP timeout much later.
            net.onLost = { t.close() }
            if (aborted.get()) throw WirelessException("cancelled")
            log.i("wireless: connected, $name")
            return t
        }

        /**
         * WPP over TCP: join the stored network, TLS to the head unit's WPP
         * server, the WPP exchange there, then AAP on the same network. Returns null when it failed:
         * the head unit is then remembered as failed at its WPP version (see [WppTcpConfigs]) and the
         * caller goes on over RFCOMM.
         */
        private fun overTcp(config: WppTcpConfig, setupToken: ByteArray?): Transport? {
            val tls = options.wppTcp ?: return null
            log.i("wireless: WPP over TCP with ${device.address}: $config")
            val net = newNetwork(options.tcpJoinTimeoutMs)
            val joined = when (val result = net.join(config.credentials)) {
                is JoinResult.Joined -> result
                is JoinResult.Failed -> {
                    checkNotAborted()
                    tcpConfigs.failed(device.address, config.version)
                    log.w("wireless: WPP over TCP: could not join \"${config.credentials.ssid}\": ${result.message}; " +
                        "no more TCP with this head unit at WPP ${config.version}, trying Bluetooth")
                    net.release()
                    network = null
                    return null
                }
            }
            try {
                val headUnitNetwork = net.network ?: throw WirelessException("lost the head unit's network")
                // 0.0.0.0: the head unit's server is its network's DHCP server.
                val host = config.endpoint.ip.takeUnless { it == "0.0.0.0" } ?: joined.dhcpServer ?: joined.gateway
                    ?: throw WirelessException("the head unit's network has no DHCP server for its WPP endpoint 0.0.0.0")
                val raw = connectTcp(headUnitNetwork, host, config.endpoint.port) { wpp = it }
                // Small frames that each want an answer: no Nagle delay.
                runCatching { raw.tcpNoDelay = true }
                checkNotAborted()
                // Its own pin key: the head unit's WPP certificate need not be its AAP one. Trusted on first
                // use like wireless AAP, since the configuration came over the bonded Bluetooth link.
                val socket = WppTls.serve(raw, tls.identity(), tls.pins, "wpp:${device.address}", trustOnFirstUse = true)
                wpp = socket
                log.i("wireless: WPP over TLS (${socket.session.protocol}) with $host:${config.endpoint.port}")
                return when (val outcome = bootstrap(socket.inputStream, socket.outputStream, net, WirelessBootstrap.TcpLink(joined, setupToken), config)) {
                    is Outcome.Ready -> aap(net, outcome.ip, outcome.port)
                    is Outcome.MovingToTcp -> throw WirelessException("unexpected WifiSetupInfo over TCP")
                }
            } catch (e: Exception) {
                if (aborted.get()) throw e
                tcpConfigs.failed(device.address, config.version)
                log.w("wireless: WPP over TCP failed: ${e.message}; no more TCP with this head unit at WPP ${config.version}, trying Bluetooth")
                bootstrap?.close()
                transport?.let { runCatching { it.close() } }
                listOfNotNull(tcp, wpp).forEach { runCatching { it.close() } }
                net.release()
                bootstrap = null; transport = null; tcp = null; wpp = null; network = null
                return null
            }
        }

        private fun openRfcomm(adapter: BluetoothAdapter): BluetoothSocket {
            // An ongoing discovery slows connections down a lot. Needs BLUETOOTH_SCAN on 12+.
            try {
                adapter.cancelDiscovery()
            } catch (e: SecurityException) {
                log.d("wireless: cannot cancel discovery: $e")
            }
            var last: IOException? = null
            for (attempt in 1..options.rfcommAttempts) {
                checkNotAborted()
                // Secure (authenticated, encrypted) like Gearhead; the device must be bonded. The
                // UUID is resolved over SDP by connect().
                val socket = device.createRfcommSocketToServiceRecord(AA_UUID)
                rfcomm = socket
                try {
                    socket.connect()
                    log.i("wireless: RFCOMM connected to ${device.address}")
                    return socket
                } catch (e: IOException) {
                    last = e
                    runCatching { socket.close() }
                    log.w("wireless: RFCOMM attempt $attempt/${options.rfcommAttempts} to ${device.address} failed: ${e.message}")
                    if (attempt < options.rfcommAttempts) Thread.sleep(1_000L * attempt)
                }
            }
            throw WirelessException("cannot reach the Android Auto service of ${device.address} over Bluetooth " +
                "(is wireless Android Auto enabled on the head unit? re-pairing can help)", last)
        }

        /**
         * Runs the WPP exchange on its own thread, which then keeps the link for the session: over RFCOMM,
         * or over TCP with [link] (then [stored] is the configuration that got us there).
         */
        private fun bootstrap(
            input: InputStream,
            output: OutputStream,
            net: HeadUnitNetwork,
            link: WirelessBootstrap.TcpLink?,
            stored: WppTcpConfig? = null,
        ): Outcome {
            val ready = CompletableFuture<Outcome>()
            // What the head unit offered for WPP over TCP (and its version), and the credentials that got us on its network.
            var offered: Pair<WppEndpoint, WppVersion>? = null
            var joinedWith: WifiCredentials? = stored?.credentials
            val b = WirelessBootstrap(input, output, object : WirelessBootstrap.Listener {
                override fun connectWifi(credentials: WifiCredentials): JoinResult =
                    net.join(credentials).also { if (it is JoinResult.Joined) joinedWith = credentials }

                override fun onReady(ip: String, port: Int) {
                    keep(offered, joinedWith)
                    ready.complete(Outcome.Ready(ip, port))
                }

                override fun onWppEndpoint(endpoint: WppEndpoint, version: WppVersion) {
                    offered = endpoint to version
                }

                override fun onSetupInfo(setup: WppSetup): Boolean {
                    if (!tcpConfigs.allowed(device.address, setup.version)) {
                        log.i("wireless: WPP over TCP failed with this head unit at WPP ${setup.version}: staying on Bluetooth")
                        return false
                    }
                    ready.complete(Outcome.MovingToTcp(setup))
                    return true
                }

                override fun onRejected(reason: Int) {
                    log.w("wireless: the head unit refused us over TCP (reason $reason)")
                    stored?.let { tcpConfigs.failed(device.address, it.version) }
                }
            }, log, bootstrapOptions(link))
            bootstrap = b
            thread(name = if (link == null) "aa-wireless-rfcomm" else "aa-wireless-wpp-tcp", isDaemon = true) {
                try {
                    b.run()
                    // Some head units (the dongle) hang up once they have our answers; the session goes on.
                    log.i("wireless: ${if (link == null) "Bluetooth bootstrap" else "WPP-over-TCP"} link ended")
                } catch (e: Throwable) {
                    ready.completeExceptionally(e)
                }
                if (!ready.isDone) ready.completeExceptionally(WirelessException("bootstrap ended without result"))
            }
            return try {
                // The bootstrap bounds itself (message timeouts, our own start requests, join timeout); this is a backstop.
                ready.get(options.joinTimeoutMs + 3 * options.bootstrap.messageTimeoutMs + options.bootstrap.phoneStartRetryLimitMs, TimeUnit.MILLISECONDS)
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                throw if (cause is IOException) cause else WirelessException(cause.message ?: "wireless bootstrap failed", cause)
            } catch (e: TimeoutException) {
                throw WirelessException("wireless bootstrap took too long", e)
            }
        }

        /**
         * Stores the endpoint a head unit offered in its version request with the credentials that got
         * us onto its network, for WPP over TCP next time; not for a DYNAMIC
         * access point, whose credentials change.
         */
        private fun keep(offered: Pair<WppEndpoint, WppVersion>?, credentials: WifiCredentials?) {
            if (offered == null || credentials == null) return
            val (endpoint, version) = offered
            if (credentials.accessPointType == ACCESS_POINT_DYNAMIC) {
                log.i("wireless: not keeping WPP over TCP for ${device.address}: its access point is DYNAMIC")
                return
            }
            if (tcpConfigs.put(device.address, WppTcpConfig(endpoint, credentials, version))) {
                log.i("wireless: WPP over TCP at ${endpoint.ip}:${endpoint.port} from the next connection with ${device.address}")
            } else {
                log.i("wireless: WPP over TCP failed with ${device.address} at WPP $version: not keeping its endpoint")
            }
        }

        /** [track] gets each socket before it connects, so that an abort can close it. */
        private fun connectTcp(network: Network, ip: String, port: Int, track: (Socket) -> Unit): Socket {
            // Literal addresses need no DNS; resolve anything else on the head unit's network.
            val address = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) InetAddresses.parseNumericAddress(ip)
                // Older: only literal addresses here, which InetAddress parses without DNS.
                else if (ip.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }) InetAddress.getByName(ip)
                else throw IllegalArgumentException(ip)
            }.getOrElse { network.getByName(ip) }
            var last: IOException? = null
            for (attempt in 1..options.tcpAttempts) {
                checkNotAborted()
                // Bound to the head unit's network: it is not the default network (no internet).
                val socket = network.socketFactory.createSocket()
                track(socket)
                try {
                    socket.connect(InetSocketAddress(address, port), options.tcpConnectTimeoutMs)
                    return socket
                } catch (e: IOException) {
                    last = e
                    runCatching { socket.close() }
                    log.w("wireless: TCP attempt $attempt/${options.tcpAttempts} to $ip:$port failed: ${e.message}")
                    if (attempt < options.tcpAttempts) Thread.sleep(1_000)
                }
            }
            throw WirelessException("cannot connect to the head unit's Android Auto server at $ip:$port", last)
        }

        private fun checkNotAborted() {
            if (aborted.get()) throw WirelessException("cancelled")
        }

        /**
         * Gives a wireless adapter only a short wait for its first message on
         * the first attempt: a quick redial helps one that missed the connection. Later attempts get
         * the normal timeout.
         */
        private fun bootstrapOptions(link: WirelessBootstrap.TcpLink?): WirelessBootstrap.Options {
            val name = runCatching { device.name }.getOrNull()
            val adapter = WirelessBootstrap.isDongle(name)
            val base = options.bootstrap.copy(wppOverTcp = wppOverTcp, adapter = adapter, tcp = link)
            if (!adapter || !dongleQuickTryUsed.compareAndSet(false, true)) return base
            log.i("wireless: \"$name\" is a wireless adapter: ${WirelessBootstrap.DONGLE_FIRST_MESSAGE_TIMEOUT_MS} ms for its first message")
            return base.copy(firstMessageTimeoutMs = WirelessBootstrap.DONGLE_FIRST_MESSAGE_TIMEOUT_MS)
        }
    }

    /** The TCP transport plus what keeps it alive: closing it also drops the RFCOMM link and the network request. */
    private class WirelessTransport(private val tcp: SocketTransport, private val release: () -> Unit) : Transport {
        override val name: String get() = tcp.name
        override val input: InputStream get() = tcp.input
        override val output: OutputStream get() = tcp.output
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            tcp.close()
            release()
        }
    }

    // ---- permissions ----

    private fun bluetoothPermissions(): List<String> =
        // Before Android 12 the Bluetooth permissions are install-time ones.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    private fun wifiPermissions(): List<String> = buildList {
        add(Manifest.permission.CHANGE_NETWORK_STATE)
        // Wi-Fi network requests involve scans: location before Android 13, "nearby devices" since.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else add(Manifest.permission.ACCESS_FINE_LOCATION)
        // Android 17 drops traffic to local addresses (the head unit's AAP server) without it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun requirePermissions(permissions: List<String>) {
        val missing = permissions.filter { !granted(it) }
        if (missing.isNotEmpty()) throw WirelessPermissionException(missing)
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        val AA_UUID: UUID = UUID.fromString(AA_WIRELESS_UUID)
        /** WifiInfoResponse.access_point_type DYNAMIC: the head unit changes its credentials. */
        private const val ACCESS_POINT_DYNAMIC = 1
        /** Some Android versions report 128-bit SDP UUIDs byte-reversed; Gearhead accepts both. */
        private val AA_UUID_REVERSED: UUID = UUID.fromString("669a0c20-0008-f4bd-e611-cb52007ae14d")

        /** Whether [device]'s cached SDP services include Android Auto wireless. Needs BLUETOOTH_CONNECT. */
        @SuppressLint("MissingPermission")
        fun isHeadUnit(device: BluetoothDevice): Boolean = hasAaUuid(device.uuids)

        internal fun hasAaUuid(uuids: Array<out ParcelUuid>?): Boolean =
            uuids.orEmpty().any { it.uuid == AA_UUID || it.uuid == AA_UUID_REVERSED }
    }
}
