package net.prototux.aare.player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class JellyfinClientTest {
    private class Recorded(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    /** Answers by URL prefix, recording each request. */
    private class FakeHttp(private val answers: List<Pair<String, Pair<Int, String>>>) : JellyfinHttp {
        val requests = ArrayList<Recorded>()
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, ByteArray> {
            requests += Recorded(method, url, headers, body)
            val (code, text) = answers.firstOrNull { url.startsWith(it.first) }?.second ?: (404 to "")
            return code to text.toByteArray()
        }
    }

    private val account = JellyfinAccount("https://jf.example:8096", "user1", "Jeff", "tok123", "dev9")

    @Test fun signInDefaultsToHttpsPostsCredentialsAndKeepsTheToken() {
        val http = FakeHttp(listOf("https://jf.example:8096/Users/AuthenticateByName" to (200 to
            """{"AccessToken":"tok123","ServerId":"s1","User":{"Id":"user1","Name":"Jeff"}}""")))
        val a = JellyfinClient.signIn(" jf.example:8096/ ", "jeff", "secret", "dev9", http = http)
        assertEquals(JellyfinAccount("https://jf.example:8096", "user1", "Jeff", "tok123", "dev9", allowLanHttp = false), a)
        val r = http.requests.single()
        assertEquals("POST", r.method)
        val body = JSONObject(r.body!!)
        assertEquals("jeff", body.getString("Username")); assertEquals("secret", body.getString("Pw"))
        val auth = r.headers.getValue("Authorization")
        assertTrue(auth.startsWith("MediaBrowser Client=\"AARE\""))
        assertTrue(auth.contains("DeviceId=\"dev9\""))
        assertFalse(auth.contains("Token=")) // none yet
    }

    @Test fun wrongPasswordSaysSoWithoutLeakingIt() {
        val http = FakeHttp(listOf("https://jf.example:8096/Users/AuthenticateByName" to (401 to "")))
        try {
            JellyfinClient.signIn("https://jf.example:8096", "jeff", "secret", "dev9", http = http)
            fail()
        } catch (e: JellyfinException) {
            assertEquals("Wrong username or password", e.message)
            assertFalse(e.message!!.contains("secret"))
        }
    }

    @Test fun unreachableServerIsAJellyfinError() {
        val http = JellyfinHttp { _, _, _, _ -> throw IOException("refused") }
        try {
            JellyfinClient.signIn("https://jf.example", "u", "p", "d", http = http)
            fail()
        } catch (e: JellyfinException) {
            assertTrue(e.message!!.startsWith("Can't reach the server"))
        }
    }

    @Test fun serverAddressesDefaultToHttps() {
        assertEquals("https://10.0.0.5:8096", JellyfinClient.normalizeServer("10.0.0.5:8096"))
        assertEquals("https://jf.local:8096", JellyfinClient.normalizeServer(" jf.local:8096/ "))
        assertEquals("http://192.168.1.5:8096", JellyfinClient.normalizeServer("HTTP://192.168.1.5:8096/"))
        assertEquals("https://jf.example/jellyfin", JellyfinClient.normalizeServer("https://jf.example/jellyfin/"))
        for (bad in listOf("", "  ", "https://", "http://", "ftp://x")) {
            try { JellyfinClient.normalizeServer(bad); fail("accepted $bad") } catch (_: JellyfinException) {}
        }
    }

    @Test fun plainHttpSignInRequiresSwitchAndHomeNetwork() {
        val http = FakeHttp(listOf("http://jf.local:8096/Users/AuthenticateByName" to (200 to
            """{"AccessToken":"tok123","ServerId":"s1","User":{"Id":"user1","Name":"Jeff"}}""")))

        // Home network with switch off -> refused before sending a request.
        try {
            JellyfinClient.signIn("http://192.168.1.5:8096", "jeff", "secret", "dev9", allowLanHttp = false, http = http)
            fail()
        } catch (e: PlainHttpRefused) {
            assertFalse(e.outsideHome)
        }
        assertTrue(http.requests.isEmpty())

        // Public host even with switch on -> refused before sending a request.
        try {
            JellyfinClient.signIn("http://example.org:8096", "jeff", "secret", "dev9", allowLanHttp = true, http = http)
            fail()
        } catch (e: PlainHttpRefused) {
            assertTrue(e.outsideHome)
        }
        assertTrue(http.requests.isEmpty())

        // Home host with switch on -> succeeds and preserves allowLanHttp.
        val lanAccount = JellyfinClient.signIn("http://jf.local:8096", "jeff", "secret", "dev9", allowLanHttp = true, http = http)
        assertEquals(JellyfinAccount("http://jf.local:8096", "user1", "Jeff", "tok123", "dev9", allowLanHttp = true), lanAccount)
        assertEquals(1, http.requests.size)
    }

    @Test fun clientMethodsRefuseDisallowedPlainHttp() {
        val http = FakeHttp(emptyList())

        val lanOff = JellyfinClient(JellyfinAccount("http://192.168.1.5:8096", "user1", "Jeff", "tok123", "dev9", allowLanHttp = false), http)
        for (call in listOf<() -> Unit>(
            { lanOff.views() },
            { lanOff.items("lib1") },
            { lanOff.imageBytes("item1", 180) },
            { lanOff.stopEncoding("sess1") },
        )) {
            try { call(); fail() } catch (e: PlainHttpRefused) { assertFalse(e.outsideHome) }
        }

        val publicOn = JellyfinClient(JellyfinAccount("http://example.org:8096", "user1", "Jeff", "tok123", "dev9", allowLanHttp = true), http)
        for (call in listOf<() -> Unit>(
            { publicOn.views() },
            { publicOn.items("lib1") },
            { publicOn.imageBytes("item1", 180) },
            { publicOn.stopEncoding("sess1") },
        )) {
            try { call(); fail() } catch (e: PlainHttpRefused) { assertTrue(e.outsideHome) }
        }

        assertTrue(http.requests.isEmpty())
    }

    @Test fun plainHttpRefusalHelperMatchesServerAndSwitch() {
        assertNull(JellyfinClient.plainHttpRefusal("https://", allowLanHttp = false))
        assertNull(JellyfinClient.plainHttpRefusal("jf.example:8096", allowLanHttp = false))
        assertEquals(false, JellyfinClient.plainHttpRefusal("http://192.168.1.5:8096", allowLanHttp = false)?.outsideHome)
        assertNull(JellyfinClient.plainHttpRefusal("http://192.168.1.5:8096", allowLanHttp = true))
        assertEquals(true, JellyfinClient.plainHttpRefusal("http://example.org:8096", allowLanHttp = true)?.outsideHome)
    }

    @Test fun viewsSkipMusicAndUseTheToken() {
        val http = FakeHttp(listOf("https://jf.example:8096/UserViews" to (200 to """{"Items":[
            {"Id":"m","Name":"Movies","CollectionType":"movies","IsFolder":true,"ImageTags":{"Primary":"x"}},
            {"Id":"a","Name":"Music","CollectionType":"music","IsFolder":true},
            {"Id":"t","Name":"Shows","CollectionType":"tvshows"}
        ],"TotalRecordCount":3}""")))
        val views = JellyfinClient(account, http).views()
        assertEquals(listOf("Movies", "Shows"), views.map { it.name })
        assertTrue(views.all { it.isFolder })
        assertTrue(views[0].hasImage); assertFalse(views[1].hasImage)
        assertTrue(http.requests.single().url.contains("userId=user1"))
        assertTrue(http.requests.single().headers.getValue("Authorization").contains("Token=\"tok123\""))
    }

    @Test fun oldServersGetTheOlderViewsRoute() {
        val http = FakeHttp(listOf(
            "https://jf.example:8096/UserViews" to (404 to ""),
            "https://jf.example:8096/Users/user1/Views" to (200 to """{"Items":[{"Id":"m","Name":"Movies","CollectionType":"movies"}]}"""),
        ))
        assertEquals(listOf("Movies"), JellyfinClient(account, http).views().map { it.name })
        assertEquals(2, http.requests.size)
    }

    @Test fun itemsKeepFoldersAndVideos() {
        val http = FakeHttp(listOf("https://jf.example:8096/Items" to (200 to """{"Items":[
            {"Id":"s","Name":"Some Show","Type":"Series","IsFolder":true},
            {"Id":"e","Name":"Pilot","Type":"Episode","IsFolder":false,"RunTimeTicks":26400000000,"SeriesName":"Some Show","ProductionYear":2021},
            {"Id":"b","Name":"A Book","Type":"Book","IsFolder":false},
            {"Id":"v","Name":"Clip","Type":"Other","MediaType":"Video","IsFolder":false}
        ]}""")))
        val items = JellyfinClient(account, http).items("lib1")
        assertEquals(listOf("Some Show", "Pilot", "Clip"), items.map { it.name })
        val ep = items[1]
        assertEquals(2_640_000L, ep.runtimeMs) // 44 minutes
        assertEquals(2021, ep.year); assertEquals("Some Show", ep.seriesName)
        assertNull(items[0].runtimeMs)
        assertTrue(http.requests.single().url.contains("ParentId=lib1"))
    }

    @Test fun errorsAndOddAnswers() {
        val client = JellyfinClient(account, FakeHttp(listOf("https://jf.example:8096/Items" to (500 to "oops"))))
        try { client.items("x"); fail() } catch (e: JellyfinException) { assertEquals(500, e.code) }
        val notJson = JellyfinClient(account, FakeHttp(listOf("https://jf.example:8096/Items" to (200 to "<html>"))))
        try { notJson.items("x"); fail() } catch (e: JellyfinException) { assertTrue(e.message!!.contains("isn't Jellyfin")) }
        assertEquals(emptyList<JellyfinItem>(), JellyfinClient.parseItems(JSONObject("{}"), views = false))
    }

    @Test fun streamUrlIsTheDirectFile() {
        assertEquals("https://jf.example:8096/Videos/e%201/stream?static=true&ApiKey=tok123", JellyfinClient(account).streamUrl("e 1"))
    }

    @Test fun urlHttpEnforcesHomeNetworkAndSameHostRedirects() {
        val lanOff = UrlJellyfinHttp(allowLanHttp = false)
        try {
            lanOff.request("GET", "http://192.168.1.5:8096/System/Info", emptyMap(), null)
            fail()
        } catch (e: PlainHttpRefused) {
            assertFalse(e.outsideHome)
        }

        val dnsPublic = UrlJellyfinHttp(allowLanHttp = true) { arrayOf(InetAddress.getByName("8.8.8.8")) }
        try {
            dnsPublic.request("GET", "http://jf.local:8096/System/Info", emptyMap(), null)
            fail()
        } catch (e: PlainHttpRefused) {
            assertTrue(e.outsideHome)
        }

        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { srv ->
            val t = thread {
                srv.accept().use { sock ->
                    sock.getInputStream().read(ByteArray(1024))
                    val reply = "HTTP/1.1 302 Found\r\nLocation: http://evil.org/steal\r\nContent-Length: 0\r\n\r\n"
                    sock.getOutputStream().write(reply.toByteArray())
                }
            }
            try {
                UrlJellyfinHttp(allowLanHttp = true).request("GET", "http://127.0.0.1:${srv.localPort}/a", emptyMap(), null)
                fail("cross-host redirect should be refused")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("redirect"))
            }
            t.join()
        }
    }
}
