package net.prototux.aare.transport.wireless

import android.content.Context
import androidx.core.content.edit
import net.prototux.aare.aap.wireless.WifiCredentials
import net.prototux.aare.aap.wireless.WppEndpoint
import net.prototux.aare.aap.wireless.WppSecurityMode
import net.prototux.aare.aap.wireless.WppVersion
import org.json.JSONException
import org.json.JSONObject

/**
 * Where a head unit serves WPP over TCP and how to join its access point first (see
 * docs/decisions.md). [version]: the head unit's WPP version when it told us.
 */
internal class WppTcpConfig(val endpoint: WppEndpoint, val credentials: WifiCredentials, val version: WppVersion) {
    override fun toString() = "WPP $version ${endpoint.ip}:${endpoint.port} $credentials"
}

/**
 * The WPP-over-TCP configuration of each head unit, by Bluetooth address: while one is stored the
 * phone runs WPP over TCP with that head unit instead of RFCOMM. Holds the access point's
 * passphrase, so it lives in a private preferences file (the app has no backups).
 *
 * A head unit whose TCP attempt failed is remembered with its WPP version: it gets no TCP again
 * (nothing stored, nothing tried) until it reports another version or [forgetAll] runs, which the
 * setting's switch does.
 */
internal class WppTcpConfigs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wpp_tcp", Context.MODE_PRIVATE)

    fun get(address: String): WppTcpConfig? {
        val json = prefs.getString(address, null) ?: return null
        return try {
            val o = JSONObject(json)
            val mode = o.getInt("securityMode")
            val password = o.getString("password")
            WppTcpConfig(
                WppEndpoint(o.getString("ip"), o.getInt("port")),
                WifiCredentials(
                    ssid = o.getString("ssid"),
                    password = password,
                    bssid = o.optString("bssid").ifEmpty { null },
                    security = WppSecurityMode.toSecurity(mode, password),
                    securityMode = mode,
                    accessPointType = null,
                ),
                WppVersion(o.getInt("major"), o.getInt("minor")),
            )
        } catch (e: JSONException) {
            forget(address)
            null
        }
    }

    /** Stores [config] for [address], unless TCP already failed with this head unit at the same version. */
    fun put(address: String, config: WppTcpConfig): Boolean {
        if (!allowed(address, config.version)) return false
        val c = config.credentials
        val json = JSONObject()
            .put("ip", config.endpoint.ip).put("port", config.endpoint.port)
            .put("ssid", c.ssid).put("password", c.password).put("bssid", c.bssid.orEmpty()).put("securityMode", c.securityMode)
            .put("major", config.version.major).put("minor", config.version.minor)
        prefs.edit(commit = true) {
            putString(address, json.toString())
            remove(FAILED + address)
        }
        return true
    }

    /** Whether TCP may be stored or tried for [address] whose head unit now reports [version]. */
    fun allowed(address: String, version: WppVersion): Boolean = tcpAllowed(failedAt(address), version)

    /** TCP failed with [address] at [version]: forget its configuration and do not try again (see the class). */
    fun failed(address: String, version: WppVersion) = prefs.edit(commit = true) {
        remove(address)
        putString(FAILED + address, version.toString())
    }

    /** Forgets every configuration and every remembered failure. */
    fun forgetAll() = prefs.edit(commit = true) { clear() }

    private fun forget(address: String) = prefs.edit(commit = true) { remove(address) }

    private fun failedAt(address: String): WppVersion? = prefs.getString(FAILED + address, null)?.let(::parseVersion)

    companion object {
        private const val FAILED = "failed:"

        /** A failure only blocks TCP while the head unit reports the version it failed at. */
        fun tcpAllowed(failedAt: WppVersion?, version: WppVersion): Boolean = failedAt != version

        fun parseVersion(s: String): WppVersion? {
            val (major, minor) = s.split('.').takeIf { it.size == 2 } ?: return null
            return WppVersion(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null)
        }
    }
}
