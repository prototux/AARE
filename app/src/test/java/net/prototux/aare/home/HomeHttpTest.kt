package net.prototux.aare.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
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
        assertFalse(HomeHttp.redirectAllowed(URL("http://ha.lan/a"), URL("ftp://ha.lan/a")))
    }

    @Test
    fun homeHostsAreLocalNamesAndPrivateAddresses() {
        for (h in listOf("localhost", "homeassistant.local", "HA.LOCAL.", "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.20",
            "169.254.3.4", "127.0.0.1", "[::1]", "[fd12:3456::1]", "[fc00::1]")) {
            assertTrue(h, HomeHttp.isHomeHost(h))
        }
        for (h in listOf("example.org", "ha.lan", "local", "evil.local.example.org", "8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.1.1",
            "11.0.0.1", "[2001:db8::1]", "[fe00::1]", "10.1", "010.0.0.1")) {
            assertFalse(h, HomeHttp.isHomeHost(h))
        }
    }

    @Test
    fun plainHttpNeedsTheOptionAndAHomeHost() {
        val off = HttpServer("http://192.168.1.20:8123")
        val on = off.copy(allowLanHttp = true)
        // https always goes, whatever the option.
        assertNull(HomeHttp.plainRefusal(off, URL("https://example.org/x")))
        // Plain http on the home network: only with the option on.
        assertEquals(false, HomeHttp.plainRefusal(off, URL("http://192.168.1.20:8123/api"))?.outsideHome)
        assertNull(HomeHttp.plainRefusal(on, URL("http://192.168.1.20:8123/api")))
        assertNull(HomeHttp.plainRefusal(on, URL("http://shelly.local/relay/0")))
        // Plain http anywhere else: refused, option or not.
        assertEquals(true, HomeHttp.plainRefusal(on, URL("http://example.org/hook"))?.outsideHome)
        assertEquals(true, HomeHttp.plainRefusal(off, URL("http://8.8.8.8/hook"))?.outsideHome)
    }

    @Test
    fun aLocalNameMustResolveToAHomeAddress() {
        val on = HttpServer(allowLanHttp = true)
        val url = URL("http://ha.local:8123/api")
        val home = InetAddress.getByName("192.168.1.20")
        assertEquals(home, HomeHttp.homeAddress(on, url) { arrayOf(InetAddress.getByName("8.8.8.8"), home) })
        try {
            HomeHttp.homeAddress(on, url) { arrayOf(InetAddress.getByName("8.8.8.8")) }
            throw AssertionError("a .local name resolving to a public address passed")
        } catch (e: HomeHttp.PlainHttpRefused) {
            assertTrue(e.outsideHome)
        }
        try {
            HomeHttp.homeAddress(HttpServer(), url) { arrayOf(home) }
            throw AssertionError("plain http passed with the option off")
        } catch (e: HomeHttp.PlainHttpRefused) {
            assertFalse(e.outsideHome)
        }
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

    @Test
    fun headersAreTypedOnePerLine() {
        val h = Header.parseLines("Accept: application/json\n\nX-Token:  a:b \nno colon\nBad Name: x\n: nameless")
        assertEquals(listOf(Header("Accept", "application/json"), Header("X-Token", "a:b")), h)
        assertEquals(h, Header.parseLines(Header.toLines(h)))
    }

    @Test
    fun theBearerTokenIsReadAndReplaced() {
        assertEquals("t", server.token)
        assertEquals(listOf(Header("Accept", "*/*")), server.otherHeaders)
        val replaced = server.withToken("Bearer new")
        assertEquals("new", replaced.token)
        assertEquals(listOf(Header("Accept", "*/*"), Header("Authorization", "Bearer new")), replaced.headers)
        assertEquals(listOf(Header("Accept", "*/*")), server.withToken(" ").headers)
        // A non-bearer Authorization (Basic…) is no token: kept as an ordinary header.
        val basic = HttpServer("https://x", listOf(Header("Authorization", "Basic abc")))
        assertEquals("", basic.token)
        assertEquals(basic.headers, basic.otherHeaders)
        assertEquals(basic.headers, basic.withToken("").headers)
    }
}
