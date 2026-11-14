package net.prototux.aare.transport.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import net.prototux.aare.aap.AapLog
import net.prototux.aare.aap.wireless.JoinResult
import net.prototux.aare.aap.wireless.WifiCredentials
import net.prototux.aare.aap.wireless.WifiSecurity
import net.prototux.aare.aap.wireless.WppStatus
import java.net.Inet4Address
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The head unit's Wi-Fi network for one session: joined with a [WifiNetworkSpecifier] request,
 * which gives an app-private, local-only network (no internet, never the default route) that
 * lasts as long as the request is registered. Sockets must be bound to [network].
 *
 * Android 10+ shows a system "connect to device" dialog for such a request, unless this app
 * already got approval for the same SSID *and* BSSID (hence passing the BSSID when known). The
 * request is only accepted from a foreground app (or, on newer versions, a foreground service).
 * On single-radio phones, joining drops the phone's normal Wi-Fi for the session.
 */
@SuppressLint("MissingPermission") // checked by WirelessConnector before use
internal class HeadUnitNetwork(
    context: Context,
    private val log: AapLog,
    /** How long the request may take, user approval included. */
    private val timeoutMs: Long,
    private val matchBssid: Boolean = true,
    /** Called right before the network request; the handle it returns is closed once the request is answered. */
    private val preApprove: ((WifiCredentials) -> AutoCloseable?)? = null,
) {
    private val context = context.applicationContext
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)

    /** The joined network; null until [join] succeeded. */
    @Volatile var network: Network? = null
        private set
    @Volatile var ssid: String? = null
        private set
    /** Called (on a ConnectivityManager thread) when the joined network goes away. */
    @Volatile var onLost: (() -> Unit)? = null

    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var pending: CompletableFuture<JoinResult>? = null
    @Volatile private var released = false

    /** Blocks until the phone has an IPv4 address on the head unit's network, or fails. */
    fun join(credentials: WifiCredentials): JoinResult {
        if (released) return JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "cancelled")
        if (wifi?.isWifiEnabled != true) {
            // Apps cannot turn Wi-Fi on since Android 10; the head unit shows its own hint for this status.
            return JoinResult.Failed(WppStatus.PHONE_WIFI_DISABLED, "Wi-Fi is turned off on the phone")
        }
        ssid = credentials.ssid
        findExisting(credentials)?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return joinLegacy(credentials)

        val specifier = try {
            specifier(credentials)
        } catch (e: IllegalArgumentException) {
            return JoinResult.Failed(WppStatus.WIFI_INCORRECT_CREDENTIALS, "unusable credentials: ${e.message}")
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val result = CompletableFuture<JoinResult>()
        pending = result
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                log.i("wireless: \"${credentials.ssid}\" available as $n")
                network = n
                connectivity.getLinkProperties(n)?.let { if (ipv4(it) != null) result.complete(joined(it)) }
            }

            // onAvailable can come before DHCP results are visible; wait for an IPv4 address.
            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                if (n == network && ipv4(lp) != null) result.complete(joined(lp))
            }

            // Not found within the timeout, or the user declined the dialog.
            override fun onUnavailable() {
                result.complete(JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE,
                    "network \"${credentials.ssid}\" not found, or the connection request was declined"))
            }

            override fun onLost(n: Network) {
                if (n != network) return
                log.w("wireless: lost the head unit's network \"${credentials.ssid}\"")
                if (!result.isDone) {
                    network = null
                } else {
                    onLost?.invoke()
                }
            }
        }
        callback = cb
        val approval = preApprove?.let { runCatching { it(credentials) }.onFailure { e -> log.w("wireless: pre-approval failed", e) }.getOrNull() }
        try {
            log.i("wireless: requesting network \"${credentials.ssid}\" (${credentials.security}); Android may ask the user to approve")
            connectivity.requestNetwork(request, cb, timeoutMs.toInt())
            if (released) { // raced with release()
                runCatching { connectivity.unregisterNetworkCallback(cb) }
                return JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "cancelled")
            }
            return try {
                result.get(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "no address on \"${credentials.ssid}\" after ${timeoutMs / 1000} s")
            }
        } finally {
            // Only the answer to this request needed it: the approval is not kept for later requests.
            approval?.let { runCatching { it.close() } }
        }
    }

    /** Drops the network request (Android then disconnects from the head unit). Idempotent. */
    fun release() {
        released = true
        pending?.complete(JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "cancelled"))
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        network = null
        // Android 8/9: forget the network AARE added for this session.
        legacyNetworkId?.let { id -> @Suppress("DEPRECATION") runCatching { wifi?.removeNetwork(id) } }
        legacyNetworkId = null
    }

    /** The Wi-Fi configuration added by [joinLegacy], removed again by [release]. */
    @Volatile private var legacyNetworkId: Int? = null

    /**
     * Android 8 and 9 have no network specifier: add the head unit's network as an app-added Wi-Fi
     * configuration, connect to it, and wait for an IPv4 address on it.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // CHANGE_WIFI_STATE is a normal permission; SSIDs need location, asked at setup
    private fun joinLegacy(c: WifiCredentials): JoinResult {
        val wm = wifi ?: return JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "no Wi-Fi on this phone")
        val conf = android.net.wifi.WifiConfiguration().apply {
            SSID = "\"${c.ssid}\""
            if (matchBssid && c.bssid != null) BSSID = c.bssid
            when (c.security) {
                WifiSecurity.OPEN -> allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                // A transition-mode (WPA2/WPA3) access point accepts a WPA2 PSK.
                WifiSecurity.WPA2_PERSONAL, WifiSecurity.WPA2_WPA3_PERSONAL -> {
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                    preSharedKey = "\"${c.password}\""
                }
                WifiSecurity.WPA3_PERSONAL ->
                    return JoinResult.Failed(WppStatus.WIFI_INCORRECT_CREDENTIALS, "WPA3-only networks need Android 10 or newer")
                WifiSecurity.UNSUPPORTED ->
                    return JoinResult.Failed(WppStatus.WIFI_INCORRECT_CREDENTIALS, "security mode ${c.securityMode} is not supported")
            }
        }
        val id = wm.addNetwork(conf)
        if (id == -1) return JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "Android refused to add \"${c.ssid}\"")
        legacyNetworkId = id
        log.i("wireless: joining \"${c.ssid}\" (Android 8/9)")

        val result = CompletableFuture<JoinResult>()
        pending = result
        val listen = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            // The network id, not the SSID: Android 9 reports "<unknown ssid>" without location services.
            private fun ours() = wm.connectionInfo?.networkId == id

            override fun onAvailable(n: Network) {
                if (!ours()) return
                network = n
                connectivity.getLinkProperties(n)?.let { if (ipv4(it) != null) result.complete(joined(it)) }
            }

            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                if (network == null && ours()) network = n
                if (n == network && ipv4(lp) != null) result.complete(joined(lp))
            }

            override fun onLost(n: Network) {
                if (n != network) return
                log.w("wireless: lost the head unit's network \"${c.ssid}\"")
                if (result.isDone) onLost?.invoke() else network = null
            }
        }
        callback = cb
        connectivity.registerNetworkCallback(listen, cb)
        wm.disconnect()
        wm.enableNetwork(id, true)
        wm.reconnect()
        return try {
            result.get(timeoutMs + 5_000, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            JoinResult.Failed(WppStatus.WIFI_NETWORK_UNAVAILABLE, "no address on \"${c.ssid}\" after ${timeoutMs / 1000} s")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun specifier(c: WifiCredentials): WifiNetworkSpecifier {
        val builder = WifiNetworkSpecifier.Builder().setSsid(c.ssid)
        val bssid = c.bssid
        if (matchBssid && bssid != null) builder.setBssid(MacAddress.fromString(bssid))
        when (c.security) {
            WifiSecurity.OPEN -> {}
            // A transition-mode (WPA2/WPA3) access point accepts a WPA2 PSK, also from phones without SAE.
            WifiSecurity.WPA2_PERSONAL, WifiSecurity.WPA2_WPA3_PERSONAL -> builder.setWpa2Passphrase(c.password)
            WifiSecurity.WPA3_PERSONAL -> builder.setWpa3Passphrase(c.password)
            WifiSecurity.UNSUPPORTED -> throw IllegalArgumentException("security mode ${c.securityMode} is not supported")
        }
        return builder.build()
    }

    /**
     * If the phone already is on that SSID (a saved network, or a previous session's request still
     * up), a specifier request could make Android tear that connection down and rebuild it; use
     * the existing network instead. Reading SSIDs needs the location permission; without it we
     * just make the request. When the head unit gave a BSSID the network must match it too, so a
     * look-alike access point with the same SSID is never reused.
     */
    private fun findExisting(c: WifiCredentials): JoinResult? {
        val ssid = c.ssid
        val wantBssid = if (matchBssid) c.bssid else null
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val found = CompletableFuture<Network>()
        val listen = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Since Android 12 the SSID is only in the capabilities' WifiInfo, and only with this flag.
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    val info = caps.transportInfo as? WifiInfo ?: return
                    if (unquote(info.ssid) == ssid && bssidMatches(info.bssid, wantBssid)) found.complete(n)
                }
                override fun onLost(n: Network) = onExistingLost(n, found)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                @Suppress("DEPRECATION") // the only way to read the SSID on Android 10/11
                override fun onAvailable(n: Network) {
                    val info = wifi?.connectionInfo
                    if (unquote(info?.ssid) == ssid && bssidMatches(info?.bssid, wantBssid)) found.complete(n)
                }
                override fun onLost(n: Network) = onExistingLost(n, found)
            }
        }
        connectivity.registerNetworkCallback(listen, cb)
        // Callbacks for networks that are already up arrive right after registration.
        val n = runCatching { found.get(EXISTING_LOOKUP_MS, TimeUnit.MILLISECONDS) }.getOrNull()
        val lp = n?.let { connectivity.getLinkProperties(it) }
        if (n == null || lp == null || ipv4(lp) == null || released) {
            runCatching { connectivity.unregisterNetworkCallback(cb) }
            return null
        }
        // Keep the listen callback for the session: it reports when that network goes away.
        log.i("wireless: already connected to \"$ssid\", using that network")
        network = n
        callback = cb
        return joined(lp)
    }

    private fun onExistingLost(n: Network, found: CompletableFuture<Network>) {
        if (n != network || !found.isDone) return
        log.w("wireless: lost the head unit's network \"$ssid\"")
        onLost?.invoke()
    }

    private fun joined(lp: LinkProperties): JoinResult.Joined {
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.dhcpServerAddress else null
        return JoinResult.Joined(localAddress = ipv4(lp)?.hostAddress, gateway = gateway?.hostAddress)
    }

    private fun ipv4(lp: LinkProperties) = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }

    /** No BSSID wanted matches anything; otherwise the phone must know the current one and it must be equal. */
    private fun bssidMatches(actual: String?, wanted: String?) =
        wanted == null || actual.equals(wanted, ignoreCase = true)

    private fun unquote(s: String?) = s?.removeSurrounding("\"")

    private companion object {
        const val EXISTING_LOOKUP_MS = 700L
    }
}
