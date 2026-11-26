package net.prototux.aare.player

import net.prototux.aare.home.HomeHttp
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

/**
 * Thin wrapper around upstream's [HomeHttp] for Jellyfin's opt-in plain http://:
 * - delegates host/address rules to [HomeHttp]
 * - additionally accepts IPv6 link-local addresses (`fe80::/10`, including zone IDs such as
 *   `[fe80::1%wlan0]`), which [HomeHttp] does not include
 * - checks that *all* addresses returned by DNS for a plain http:// host are home addresses
 *   (unlike [HomeHttp.homeAddress], which connects a raw socket to one chosen IP, `HttpURLConnection`
 *   and ExoPlayer connect by hostname and may pick any resolved address).
 */
internal object HomeNetwork {
    fun isHomeHost(host: String): Boolean {
        if (HomeHttp.isHomeHost(host)) return true
        val raw = host.removeSurrounding("[", "]")
        if (':' !in raw) return false
        val h = raw.substringBefore('%')
        if (h.isEmpty() || !h.all { it.isLetterOrDigit() || it == ':' || it == '.' }) return false
        return runCatching { InetAddress.getByName(h) }.getOrNull()?.let(::isHomeAddress) ?: false
    }

    fun isHomeAddress(a: InetAddress): Boolean =
        HomeHttp.isHomeAddress(a) || (a is Inet6Address && a.isLinkLocalAddress)

    /**
     * Null if [url] may be used, else why not: https always may; plain http only to a home host
     * ([isHomeHost]) when [allowLanHttp] is on. By name only: [requireHomeAddress] checks DNS too.
     */
    fun plainHttpRefusal(url: URL, allowLanHttp: Boolean): PlainHttpRefused? = when {
        !url.protocol.equals("http", true) -> null
        !isHomeHost(url.host) -> PlainHttpRefused(outsideHome = true)
        !allowLanHttp -> PlainHttpRefused(outsideHome = false)
        else -> null
    }

    /**
     * Verifies [url] before opening a connection: [plainHttpRefusal] must pass, and for plain
     * http:// every address [lookup] returns for the host must be a home address (so a *.local
     * name resolving to a public IP is refused). Throws [PlainHttpRefused] if disallowed.
     */
    fun requireHomeAddress(
        url: URL,
        allowLanHttp: Boolean,
        lookup: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    ) {
        if (!url.protocol.equals("http", true)) return
        plainHttpRefusal(url, allowLanHttp)?.let { throw it }
        val addrs = lookup(url.host)
        if (addrs.isEmpty() || !addrs.all(::isHomeAddress)) throw PlainHttpRefused(outsideHome = true)
    }
}
