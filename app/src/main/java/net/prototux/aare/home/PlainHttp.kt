package net.prototux.aare.home

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * HTTP/1.1 without TLS over a plain socket, for addresses on the home network only (the checks are
 * in [HomeHttp.homeAddress]). Android's HttpURLConnection refuses cleartext for the whole app, and
 * allowing it in the network security config can't be limited to private addresses: every part of
 * AARE (the browser…) could then use plain http to any host. Connecting here to the one address
 * AARE checked also means a name can't resolve to a home address for the check and to another
 * one for the request.
 *
 * One request per connection (Connection: close), no compression; a reply with a Content-Length,
 * chunked, or read until the server closes, capped like the https ones.
 */
internal object PlainHttp {
    class Reply(val code: Int, val location: String?, val body: String)

    /** Headers AARE writes itself: an item's own with these names are dropped. */
    private val OWN_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding", "accept-encoding")
    private const val MAX_LINE = 8 * 1024
    private const val MAX_HEADERS = 100

    /** Sends the request to [address] (on [url]'s port) and reads the reply. Blocking. */
    fun exchange(address: InetAddress, url: URL, method: String, headers: List<Header>, body: ByteArray, timeoutMs: Int, maxBody: Int): Reply =
        Socket().use { s ->
            s.connect(InetSocketAddress(address, if (url.port >= 0) url.port else url.defaultPort), timeoutMs)
            s.soTimeout = timeoutMs
            val out = s.getOutputStream()
            out.write(head(url, method, headers, body.size).encodeToByteArray())
            out.write(body)
            out.flush()
            readReply(BufferedInputStream(s.getInputStream()), method, maxBody)
        }

    /** The request line and headers, ending with the blank line. Throws on a line break in any of them. */
    fun head(url: URL, method: String, headers: List<Header>, bodySize: Int): String {
        val host = if (url.port >= 0 && url.port != url.defaultPort) "${url.host}:${url.port}" else url.host
        val lines = buildList {
            add("$method ${url.file.ifEmpty { "/" }} HTTP/1.1")
            add("Host: $host")
            add("Connection: close")
            add("Accept-Encoding: identity")
            headers.filter { it.name.lowercase() !in OWN_HEADERS }.forEach { add("${it.name}: ${it.value}") }
            if (bodySize > 0 || method == "POST" || method == "PUT") add("Content-Length: $bodySize")
        }
        // A CR or LF in a value would let it add headers, or a second request, of its own.
        if (lines.any { l -> l.any { it == '\r' || it == '\n' } }) throw IOException("line break in a request header")
        return lines.joinToString("\r\n", postfix = "\r\n\r\n")
    }

    /** The status, the Location header and the (capped) body of a reply to [method]. */
    fun readReply(input: InputStream, method: String, maxBody: Int): Reply {
        while (true) {
            val status = readLine(input) ?: throw IOException("no reply")
            val code = STATUS.matchEntire(status)?.groupValues?.get(1)?.toInt() ?: throw IOException("not an HTTP reply")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: throw IOException("reply cut short")
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                if (headers.size > MAX_HEADERS) throw IOException("too many reply headers")
            }
            // 100 Continue and the other interim replies come before the real one.
            if (code in 100..199) continue
            val body = when {
                method == "HEAD" || code == 204 || code == 304 -> ""
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input, maxBody)
                headers["content-length"] != null -> {
                    val n = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("bad Content-Length")
                    if (n > maxBody) throw IOException("reply over ${maxBody / 1024} KiB")
                    String(readExactly(input, n.toInt()), Charsets.UTF_8)
                }
                else -> HomeHttp.readCapped(input, maxBody)
            }
            return Reply(code, headers["location"], body)
        }
    }

    private fun readChunked(input: InputStream, maxBody: Int): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("reply cut short")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)?.takeIf { it >= 0 } ?: throw IOException("bad chunk size")
            if (size == 0) {
                // Trailers, up to the blank line.
                while (!readLine(input).isNullOrEmpty()) Unit
                return out.toString("UTF-8")
            }
            if (out.size() + size > maxBody) throw IOException("reply over ${maxBody / 1024} KiB")
            out.write(readExactly(input, size))
            if (readLine(input)?.isEmpty() != true) throw IOException("bad chunk end")
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw IOException("reply cut short")
            off += r
        }
        return buf
    }

    /** A line without its CRLF (or LF), or null at the end of the stream before any byte. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else throw IOException("reply cut short")
            if (b == '\n'.code) break
            if (out.size() >= MAX_LINE) throw IOException("reply line too long")
            out.write(b)
        }
        return out.toString("ISO-8859-1").removeSuffix("\r")
    }

    private val STATUS = Regex("""HTTP/1\.[01] (\d{3})(?: .*)?""")
}
