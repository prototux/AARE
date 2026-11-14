package net.prototux.aare.transport.wireless

import android.content.Context
import androidx.core.content.edit
import net.prototux.aare.aap.wireless.WifiCredentials
import net.prototux.aare.aap.wireless.WppEndpoint
import net.prototux.aare.aap.wireless.WppSecurityMode
import org.json.JSONException
import org.json.JSONObject

/** Where a head unit serves WPP over TCP and how to join its access point first (see docs/decisions.md). */
internal class WppTcpConfig(val endpoint: WppEndpoint, val credentials: WifiCredentials) {
    override fun toString() = "${endpoint.ip}:${endpoint.port} $credentials"
}

/**
 * The WPP-over-TCP configuration of each head unit, by Bluetooth address, kept
 * them: while one is stored the phone runs WPP over TCP with that head unit instead of RFCOMM. Holds
 * the access point's passphrase, so it lives in a private preferences file (the app has no backups).
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
            )
        } catch (e: JSONException) {
            forget(address)
            null
        }
    }

    fun put(address: String, config: WppTcpConfig) {
        val c = config.credentials
        val json = JSONObject()
            .put("ip", config.endpoint.ip).put("port", config.endpoint.port)
            .put("ssid", c.ssid).put("password", c.password).put("bssid", c.bssid.orEmpty()).put("securityMode", c.securityMode)
        prefs.edit(commit = true) { putString(address, json.toString()) }
    }

    fun forget(address: String) = prefs.edit(commit = true) { remove(address) }
}
