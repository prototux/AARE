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
import net.prototux.aare.aap.SocketTransport
import net.prototux.aare.aap.Transport
import net.prototux.aare.aap.wireless.AA_WIRELESS_UUID
import net.prototux.aare.aap.wireless.JoinResult
import net.prototux.aare.aap.wireless.WifiCredentials
import net.prototux.aare.aap.wireless.WirelessBootstrap
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
 */
@SuppressLint("MissingPermission") // every entry point calls requirePermissions()
class WirelessConnector(context: Context, private val log: AapLog, private val options: Options = Options()) {

    class Options(
        val rfcommAttempts: Int = 3,
        /** Joining the head unit's network, including the time the user takes to approve it. */
        val joinTimeoutMs: Long = 40_000,
        /** Gearhead: 10 s, 3 tries. The head unit's server may come up a bit after its Wi-Fi. */
        val tcpConnectTimeoutMs: Int = 10_000,
        val tcpAttempts: Int = 3,
        /** Pin the BSSID the head unit sent: lets Android skip the approval dialog next time. */
        val matchBssid: Boolean = true,
        val bootstrap: WirelessBootstrap.Options = WirelessBootstrap.Options(),
    )

    private val context = context.applicationContext
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

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

    /**
     * Head units (Bluetooth addresses) that answered our version response, then sent no
     * WifiStartRequest before the message timeout: they wait for the phone to ask, so this
     * connector's redials to them send one (see [WirelessBootstrap.Options.phoneStartDelayMs]).
     */
    private val waitingForPhone: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // ---- one connection attempt ----

    private inner class Attempt(private val device: BluetoothDevice) {
        private val aborted = AtomicBoolean(false)
        @Volatile private var rfcomm: BluetoothSocket? = null
        @Volatile private var bootstrap: WirelessBootstrap? = null
        @Volatile private var network: HeadUnitNetwork? = null
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
                val socket = openRfcomm(adapter)
                val net = HeadUnitNetwork(context, log, options.joinTimeoutMs, options.matchBssid).also { network = it }
                val (ip, port) = bootstrap(socket, net)
                val headUnitNetwork = net.network ?: throw WirelessException("lost the head unit's network")
                val tcpSocket = connectTcp(headUnitNetwork, ip, port)
                val name = "wifi \"${net.ssid}\" $ip:$port (${runCatching { device.name }.getOrNull() ?: device.address})"
                val t = WirelessTransport(SocketTransport(tcpSocket, name), ::abort)
                transport = t
                // A dead Wi-Fi link otherwise only shows up as a TCP timeout much later.
                net.onLost = { t.close() }
                if (aborted.get()) throw WirelessException("cancelled")
                log.i("wireless: connected, $name")
                return t
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
         * Releases TCP, RFCOMM and the network request (last, so a blocked join() wakes up). Safe to call
         * more than once: every close is idempotent.
         */
        private fun releaseAll() {
            transport?.let { runCatching { it.close() } }
            tcp?.let { runCatching { it.close() } }
            bootstrap?.close()
            rfcomm?.let { runCatching { it.close() } }
            network?.release()
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

        /** Runs the RFCOMM exchange on its own thread, which then keeps the link for the session. */
        private fun bootstrap(socket: BluetoothSocket, net: HeadUnitNetwork): Pair<String, Int> {
            val ready = CompletableFuture<Pair<String, Int>>()
            val b = WirelessBootstrap(socket.inputStream, socket.outputStream, object : WirelessBootstrap.Listener {
                override fun connectWifi(credentials: WifiCredentials): JoinResult = net.join(credentials)
                override fun onReady(ip: String, port: Int) {
                    ready.complete(ip to port)
                }
            }, log, bootstrapOptions())
            bootstrap = b
            thread(name = "aa-wireless-rfcomm", isDaemon = true) {
                try {
                    b.run()
                    // Some head units (the dongle) hang up once they have our answers; the session goes on.
                    log.i("wireless: Bluetooth bootstrap link ended")
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
                if (cause is net.prototux.aare.aap.wireless.WirelessBootstrapException && cause.noStartRequest && waitingForPhone.add(device.address)) {
                    log.i("wireless: the head unit waited for the phone to ask it to start; the next attempt will ask")
                }
                throw if (cause is IOException) cause else WirelessException(cause.message ?: "wireless bootstrap failed", cause)
            } catch (e: TimeoutException) {
                throw WirelessException("wireless bootstrap took too long", e)
            }
        }

        private fun connectTcp(network: Network, ip: String, port: Int): Socket {
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
                tcp = socket
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
         * The bootstrap options for this attempt. A wireless adapter gets only a short wait for its
         * first message on the first attempt: a quick redial helps one that missed the connection;
         * later attempts get the normal timeout. A head unit that waited for the phone last time
         * ([waitingForPhone]) is asked to start.
         */
        private fun bootstrapOptions(): WirelessBootstrap.Options {
            var result = options.bootstrap
            val name = runCatching { device.name }.getOrNull()
            if (WirelessBootstrap.isDongle(name) && dongleQuickTryUsed.compareAndSet(false, true)) {
                log.i("wireless: \"$name\" is a wireless adapter: ${WirelessBootstrap.DONGLE_FIRST_MESSAGE_TIMEOUT_MS} ms for its first message")
                result = result.copy(firstMessageTimeoutMs = WirelessBootstrap.DONGLE_FIRST_MESSAGE_TIMEOUT_MS)
            }
            if (device.address in waitingForPhone && result.phoneStartDelayMs <= 0) {
                result = result.copy(phoneStartDelayMs = WirelessBootstrap.PHONE_START_DELAY_MS)
            }
            return result
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
        /** Some Android versions report 128-bit SDP UUIDs byte-reversed; Gearhead accepts both. */
        private val AA_UUID_REVERSED: UUID = UUID.fromString("669a0c20-0008-f4bd-e611-cb52007ae14d")

        /** Whether [device]'s cached SDP services include Android Auto wireless. Needs BLUETOOTH_CONNECT. */
        @SuppressLint("MissingPermission")
        fun isHeadUnit(device: BluetoothDevice): Boolean = hasAaUuid(device.uuids)

        internal fun hasAaUuid(uuids: Array<out ParcelUuid>?): Boolean =
            uuids.orEmpty().any { it.uuid == AA_UUID || it.uuid == AA_UUID_REVERSED }
    }
}
