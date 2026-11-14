package net.prototux.aare.home

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * The HTTP side of home actions and tiles, over HttpURLConnection: timeouts, a capped reply, and
 * redirects followed only on the same host (and never from https to http), so a server can't
 * bounce a request, with its token, somewhere else.
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

    /** A redirect is followed only to the same host, and not from https down to http. */
    fun redirectAllowed(from: URL, to: URL): Boolean =
        from.host.equals(to.host, true) && !(from.protocol.equals("https", true) && to.protocol.equals("http", true))

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
     * (capped) reply. Blocking. Throws on a network error, a refused redirect or a too-big reply.
     */
    fun request(method: String, url: URL, own: List<Header>, body: String, server: HttpServer): Response {
        var current = url
        var m = method
        var b = body
        repeat(MAX_REDIRECTS + 1) {
            val c = current.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.requestMethod = m
                c.useCaches = false
                // Per hop: a redirect to another port of the host isn't the server's origin any more.
                val sent = headersFor(server, current, own)
                sent.forEach { c.setRequestProperty(it.name, it.value) }
                if (b.isNotEmpty() && m != "GET") {
                    if (sent.none { it.name.equals("Content-Type", true) }) c.setRequestProperty("Content-Type", contentType(b))
                    c.doOutput = true
                    val bytes = b.encodeToByteArray()
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                }
                val code = c.responseCode
                if (code in 300..399 && code != 304) {
                    val location = c.getHeaderField("Location") ?: return Response(code, "")
                    val next = runCatching { URL(current, location) }.getOrNull()
                    if (next == null || !redirectAllowed(current, next)) throw IOException("redirect to another host refused")
                    // 307/308 keep the method and body; the others become a GET, as browsers do.
                    if (code != 307 && code != 308) { m = "GET"; b = "" }
                    current = next
                    return@repeat
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                return Response(code, stream?.use { readCapped(it, MAX_BODY) }.orEmpty())
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
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
