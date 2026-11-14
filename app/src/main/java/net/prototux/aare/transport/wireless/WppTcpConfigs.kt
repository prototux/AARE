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
 * Failed TCP attempts are counted per head unit with its WPP version, and a successful one clears
 * the count: after [MAX_FAILURES] in a row (a slow access point alone costs one), or at once when
 * the head unit rejects the phone ([refused]), it gets no TCP again (nothing stored, nothing tried)
 * until it reports another version or [forgetAll] runs, which the setting's switch does.
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

    /**
     * Stores [config] for [address], unless TCP is off for this head unit at the same version. Its
     * failure count stays: only a TCP attempt that works clears it.
     */
    fun put(address: String, config: WppTcpConfig): Boolean {
        if (!allowed(address, config.version)) return false
        val c = config.credentials
        val json = JSONObject()
            .put("ip", config.endpoint.ip).put("port", config.endpoint.port)
            .put("ssid", c.ssid).put("password", c.password).put("bssid", c.bssid.orEmpty()).put("securityMode", c.securityMode)
            .put("major", config.version.major).put("minor", config.version.minor)
        prefs.edit(commit = true) { putString(address, json.toString()) }
        return true
    }

    /** Whether TCP may be stored or tried for [address] whose head unit now reports [version]. */
    fun allowed(address: String, version: WppVersion): Boolean = tcpAllowed(failures(address), version)

    /**
     * TCP failed with [address] at [version]: one more failure in a row. Returns true when that turns
     * TCP off for it (see the class), which also forgets its configuration.
     */
    fun failed(address: String, version: WppVersion): Boolean = strike(address, nextFailures(failures(address), version))

    /** The head unit at [address] rejected the phone over TCP at [version]: TCP is off for it at once. */
    fun refused(address: String, version: WppVersion) {
        strike(address, Failures(version, MAX_FAILURES))
    }

    /** TCP worked with [address]: its failures no longer count. */
    fun succeeded(address: String) {
        if (prefs.contains(FAILURES + address)) prefs.edit(commit = true) { remove(FAILURES + address) }
    }

    /** Forgets every configuration and every remembered failure. */
    fun forgetAll() = prefs.edit(commit = true) { clear() }

    private fun forget(address: String) = prefs.edit(commit = true) { remove(address) }

    private fun strike(address: String, failures: Failures): Boolean {
        val off = !tcpAllowed(failures, failures.version)
        prefs.edit(commit = true) {
            putString(FAILURES + address, "${failures.version}/${failures.count}")
            if (off) remove(address)
        }
        return off
    }

    private fun failures(address: String): Failures? = prefs.getString(FAILURES + address, null)?.let(::parseFailures)

    /** [count] TCP failures in a row with a head unit, the last ones while it reported [version]. */
    data class Failures(val version: WppVersion, val count: Int)

    companion object {
        private const val FAILURES = "failures:"

        /** TCP failures in a row, at one WPP version, before TCP is off for a head unit. */
        const val MAX_FAILURES = 3

        /** Failures only block TCP while the head unit reports the version they happened at. */
        fun tcpAllowed(failures: Failures?, version: WppVersion): Boolean =
            failures == null || failures.version != version || failures.count < MAX_FAILURES

        /** One more failure at [version]: a head unit that reports another version starts again from one. */
        fun nextFailures(before: Failures?, version: WppVersion): Failures =
            Failures(version, if (before?.version == version) before.count + 1 else 1)

        fun parseFailures(s: String): Failures? {
            val (version, count) = s.split('/').takeIf { it.size == 2 } ?: return null
            return Failures(parseVersion(version) ?: return null, count.toIntOrNull()?.takeIf { it > 0 } ?: return null)
        }

        fun parseVersion(s: String): WppVersion? {
            val (major, minor) = s.split('.').takeIf { it.size == 2 } ?: return null
            return WppVersion(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null)
        }
    }
}
