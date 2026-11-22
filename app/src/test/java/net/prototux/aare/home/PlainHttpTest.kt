package net.prototux.aare.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PlainHttpTest {
    private fun reply(text: String, method: String = "GET", max: Int = 1024) =
        PlainHttp.readReply(ByteArrayInputStream(text.toByteArray()), method, max)

    @Test
    fun theRequestHeadIsHttp11WithTheHost() {
        val head = PlainHttp.head(URL("http://192.168.1.20:8123/api/x?a=1"), "POST",
            listOf(Header("Authorization", "Bearer t"), Header("Host", "evil"), Header("Content-Length", "99")), 2)
        assertEquals(
            "POST /api/x?a=1 HTTP/1.1\r\nHost: 192.168.1.20:8123\r\nConnection: close\r\nAccept-Encoding: identity\r\n" +
                "Authorization: Bearer t\r\nContent-Length: 2\r\n\r\n",
            head,
        )
        assertTrue(PlainHttp.head(URL("http://ha.local"), "GET", emptyList(), 0).startsWith("GET / HTTP/1.1\r\nHost: ha.local\r\n"))
    }

    @Test(expected = IOException::class)
    fun aLineBreakInAHeaderIsRefused() {
        PlainHttp.head(URL("http://ha.local/"), "GET", listOf(Header("X-A", "1\r\nX-Injected: 2")), 0)
    }

    @Test
    fun repliesAreReadByLengthChunksOrToTheEnd() {
        reply("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nokEXTRA").let { assertEquals(200, it.code); assertEquals("ok", it.body) }
        assertEquals("hello world", reply("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6;x=y\r\n world\r\n0\r\n\r\n").body)
        assertEquals("rest", reply("HTTP/1.0 200 OK\nServer: x\n\nrest").body)
        assertEquals("", reply("HTTP/1.1 204 No Content\r\n\r\n").body)
        // An interim 100 Continue is skipped.
        assertEquals(201, reply("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n").code)
        reply("HTTP/1.1 302 Found\r\nLocation: /other\r\nContent-Length: 0\r\n\r\n").let { assertEquals("/other", it.location) }
        assertNull(reply("HTTP/1.1 200 OK\r\n\r\n").location)
    }

    @Test
    fun bigOrBrokenRepliesAreRefused() {
        for (r in listOf(
            "HTTP/1.1 200 OK\r\nContent-Length: 2000\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n800\r\n",
            "HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(2000),
            "SSH-2.0-OpenSSH\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nab",
        )) {
            try {
                reply(r)
                throw AssertionError("accepted: ${r.take(40)}")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun aRequestGoesOverALoopbackSocket() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val seen = LinkedBlockingQueue<String>()
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { s ->
                        val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val head = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                        val length = head.firstOrNull { it.startsWith("Content-Length:") }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val body = CharArray(length).also { input.read(it) }.concatToString()
                        seen += head.joinToString("\n") + "\n\n" + body
                        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndone".toByteArray())
                    }
                }
            }
            val url = URL("http://127.0.0.1:${server.localPort}/hook")
            val r = PlainHttp.exchange(InetAddress.getLoopbackAddress(), url, "POST", listOf(Header("Content-Type", "text/plain")), "open".toByteArray(), 5_000, 1024)
            assertEquals(200, r.code)
            assertEquals("done", r.body)
            val request = seen.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("the server got nothing")
            assertTrue(request, request.startsWith("POST /hook HTTP/1.1\nHost: 127.0.0.1:${server.localPort}\n"))
            assertTrue(request, request.endsWith("\n\nopen"))
        }
    }
}
