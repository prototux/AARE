package net.prototux.aare.home

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL

/**
 * The HTTP side of home actions and tiles: timeouts, a capped reply, and redirects followed only
 * on the same host (and never from https to http), so a server can't bounce a request, with its
 * token, somewhere else. https goes through HttpURLConnection. Plain http:// is refused unless the
 * server's [HttpServer.allowLanHttp] is on, and then goes only to an address on the home network,
 * through [PlainHttp].
 */
object HomeHttp {
    const val TIMEOUT_MS = 10_000
    /** Replies over this are refused: a tile needs a small value, an action only the status. */
    const val MAX_BODY = 64 * 1024
    private const val MAX_REDIRECTS = 3

    /** Methods offered in the editor (HttpURLConnection has no PATCH). */
    val METHODS = listOf("POST", "GET", "PUT", "DELETE")

    class Response(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /**
     * Plain http:// refused: to an address outside the home network ([outsideHome]), or to one on
     * it while the server's "allow plain http on my home network" is off.
     */
    class PlainHttpRefused(val outsideHome: Boolean) :
        IOException(if (outsideHome) "plain http to an address outside the home network refused" else "plain http is off for this server")

    /** [url] made absolute: a path ("/api/…") goes on [base]. Null unless that gives an http(s) URL with a host. */
    fun resolve(base: String, url: String): URL? = runCatching {
        val u = url.trim()
        // Anything with a scheme stands alone (and only http and https pass).
        val absolute = if ("://" in u) URI(u)
            else URI(base.trim().trimEnd('/') + "/" + u.trimStart('/'))
        absolute.takeIf { (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrBlank() }?.toURL()
    }.getOrNull()

    /** Same scheme, host and port. */
    fun sameOrigin(a: URL, b: URL): Boolean =
        a.protocol.equals(b.protocol, true) && a.host.equals(b.host, true) && port(a) == port(b)

    /** A redirect is followed only to the same host, over http(s), and not from https down to http. */
    fun redirectAllowed(from: URL, to: URL): Boolean =
        from.host.equals(to.host, true) && (to.protocol.equals("http", true) || to.protocol.equals("https", true)) &&
            !(from.protocol.equals("https", true) && to.protocol.equals("http", true))

    /**
     * A host plain http may go to, judged by its name alone (no lookup): localhost, a *.local name,
     * or an address written out that [isHomeAddress] accepts.
     */
    fun isHomeHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || (h.endsWith(".local") && h.length > ".local".length)) return true
        return literalAddress(h)?.let(::isHomeAddress) ?: false
    }

    /**
     * An address on the home network: private IPv4 (10/8, 172.16/12, 192.168/16), link-local
     * 169.254/16, loopback (127/8, ::1) or an IPv6 unique local address (fc00::/7).
     */
    fun isHomeAddress(a: InetAddress): Boolean {
        val b = a.address.map { it.toInt() and 0xff }
        return when (a) {
            is Inet4Address -> b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254) || b[0] == 127
            is Inet6Address -> a.isLoopbackAddress || (b[0] and 0xfe) == 0xfc
            else -> false
        }
    }

    /**
     * Null if [url] may be sent, else why not: https always may; plain http only to a home host
     * ([isHomeHost]) with [server]'s option on. By name only: [homeAddress] checks the lookup too.
     */
    fun plainRefusal(server: HttpServer, url: URL): PlainHttpRefused? = when {
        !url.protocol.equals("http", true) -> null
        !isHomeHost(url.host) -> PlainHttpRefused(outsideHome = true)
        !server.allowLanHttp -> PlainHttpRefused(outsideHome = false)
        else -> null
    }

    /**
     * The address to send plain http [url] to: [url] must pass [plainRefusal], and its name must
     * resolve to a home address too (a *.local name pointing elsewhere is refused). Throws
     * [PlainHttpRefused] otherwise.
     */
    fun homeAddress(server: HttpServer, url: URL, lookup: (String) -> Array<InetAddress> = InetAddress::getAllByName): InetAddress {
        plainRefusal(server, url)?.let { throw it }
        return lookup(url.host).firstOrNull(::isHomeAddress) ?: throw PlainHttpRefused(outsideHome = true)
    }

    /** [host] as an address if it is written as one, else null; never a DNS lookup. */
    private fun literalAddress(host: String): InetAddress? {
        val h = host.removeSurrounding("[", "]")
        val literal = IPV4.matches(h) || (':' in h && h.all { it.isLetterOrDigit() || it == ':' || it == '.' })
        return if (literal) runCatching { InetAddress.getByName(h) }.getOrNull() else null
    }

    private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")

    /**
     * The headers for a request to [url]: the server's defaults only when [url] is on the server's
     * own origin (its token isn't sent to another host), then the item's own, which win by name.
     */
    fun headersFor(server: HttpServer, url: URL, own: List<Header>): List<Header> {
        val base = resolve(server.baseUrl, "/")
        val defaults = if (base != null && sameOrigin(base, url)) server.headers else emptyList()
        val ownNames = own.map { it.name.lowercase() }.toSet()
        return defaults.filter { it.name.lowercase() !in ownNames } + own
    }

    /** The Content-Type to send with [body] when none was set: JSON if it looks like JSON. */
    fun contentType(body: String): String {
        val t = body.trimStart()
        return if (t.startsWith("{") || t.startsWith("[")) "application/json" else "text/plain; charset=utf-8"
    }

    /**
     * Sends the request with [server]'s defaults (see [headersFor]) and [own] headers, and reads the
     * (capped) reply. Blocking. Throws on a network error, a refused redirect, refused plain http
     * ([PlainHttpRefused]) or a too-big reply.
     */
    fun request(method: String, url: URL, own: List<Header>, body: String, server: HttpServer): Response {
        var current = url
        var m = method
        var b = body
        repeat(MAX_REDIRECTS + 1) {
            // Per hop: a redirect to another port of the host isn't the server's origin any more.
            val headers = headersFor(server, current, own)
            val bytes = if (b.isNotEmpty() && m != "GET") b.encodeToByteArray() else ByteArray(0)
            val sent = if (bytes.isNotEmpty() && headers.none { it.name.equals("Content-Type", true) }) headers + Header("Content-Type", contentType(b)) else headers
            val reply = if (current.protocol.equals("http", true)) {
                PlainHttp.exchange(homeAddress(server, current), current, m, sent, bytes, TIMEOUT_MS, MAX_BODY)
            } else {
                secure(current, m, sent, bytes)
            }
            if (reply.code in 300..399 && reply.code != 304) {
                val location = reply.location ?: return Response(reply.code, "")
                val next = runCatching { URL(current, location) }.getOrNull()
                if (next == null || !redirectAllowed(current, next)) throw IOException("redirect to another host refused")
                // 307/308 keep the method and body; the others become a GET, as browsers do.
                if (reply.code != 307 && reply.code != 308) { m = "GET"; b = "" }
                current = next
                return@repeat
            }
            return Response(reply.code, reply.body)
        }
        throw IOException("too many redirects")
    }

    /** One https exchange over HttpURLConnection, redirects not followed (their body isn't read). */
    private fun secure(url: URL, method: String, headers: List<Header>, body: ByteArray): PlainHttp.Reply {
        val c = url.openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.requestMethod = method
            c.useCaches = false
            headers.forEach { c.setRequestProperty(it.name, it.value) }
            if (body.isNotEmpty()) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code in 300..399 && code != 304) return PlainHttp.Reply(code, c.getHeaderField("Location"), "")
            val stream = if (code >= 400) c.errorStream else c.inputStream
            return PlainHttp.Reply(code, null, stream?.use { readCapped(it, MAX_BODY) }.orEmpty())
        } finally {
            c.disconnect()
        }
    }

    /** The stream as UTF-8 text; throws if it is longer than [max] bytes. */
    fun readCapped(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) throw IOException("reply over ${max / 1024} KiB")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    private fun port(u: URL) = if (u.port >= 0) u.port else u.defaultPort
}
