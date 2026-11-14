package net.prototux.aare.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL

class HomeHttpTest {
    private val server = HttpServer("https://ha.lan:8123/", listOf(Header("Authorization", "Bearer t"), Header("Accept", "*/*")))

    @Test
    fun pathsGoOnTheBaseUrl() {
        assertEquals("https://ha.lan:8123/api/states/x", HomeHttp.resolve(server.baseUrl, "/api/states/x").toString())
        assertEquals("https://ha.lan:8123/api/states/x", HomeHttp.resolve(server.baseUrl, "api/states/x").toString())
        assertEquals("https://other.org/hook", HomeHttp.resolve(server.baseUrl, "https://other.org/hook").toString())
        assertNull(HomeHttp.resolve("", "/api"))
        assertNull(HomeHttp.resolve(server.baseUrl, "ftp://ha.lan/x"))
        assertNull(HomeHttp.resolve("not a url", "/x"))
    }

    @Test
    fun theServersHeadersGoOnlyToTheServer() {
        val own = HomeHttp.headersFor(server, URL("https://ha.lan:8123/api/x"), emptyList())
        assertEquals(server.headers, own)
        // Another host, another port, or plain http on the same host: not the server's token.
        assertTrue(HomeHttp.headersFor(server, URL("https://other.org/x"), emptyList()).isEmpty())
        assertTrue(HomeHttp.headersFor(server, URL("https://ha.lan:9000/x"), emptyList()).isEmpty())
        assertTrue(HomeHttp.headersFor(server, URL("http://ha.lan:8123/x"), emptyList()).isEmpty())
    }

    @Test
    fun anItemsHeadersWinByName() {
        val h = HomeHttp.headersFor(server, URL("https://ha.lan:8123/x"), listOf(Header("authorization", "Bearer mine")))
        assertEquals(listOf(Header("Accept", "*/*"), Header("authorization", "Bearer mine")), h)
    }

    @Test
    fun redirectsStayOnTheHostAndOnHttps() {
        assertTrue(HomeHttp.redirectAllowed(URL("https://ha.lan/a"), URL("https://ha.lan/b")))
        assertTrue(HomeHttp.redirectAllowed(URL("http://ha.lan/a"), URL("https://ha.lan/a")))
        assertFalse(HomeHttp.redirectAllowed(URL("https://ha.lan/a"), URL("https://evil.org/a")))
        assertFalse(HomeHttp.redirectAllowed(URL("https://ha.lan/a"), URL("http://ha.lan/a")))
    }

    @Test
    fun repliesAreCapped() {
        assertEquals("ok", HomeHttp.readCapped(ByteArrayInputStream("ok".toByteArray()), 10))
        try {
            HomeHttp.readCapped(ByteArrayInputStream(ByteArray(11)), 10)
            throw AssertionError("an 11-byte reply passed a 10-byte cap")
        } catch (_: IOException) {
        }
    }

    @Test
    fun jsonBodiesAreSentAsJson() {
        assertEquals("application/json", HomeHttp.contentType(""" {"entity_id":"x"}"""))
        assertEquals("text/plain; charset=utf-8", HomeHttp.contentType("open"))
    }
}
