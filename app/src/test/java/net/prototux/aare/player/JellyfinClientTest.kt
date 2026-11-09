package net.prototux.aare.player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

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

    private val account = JellyfinAccount("http://jf.local:8096", "user1", "Jeff", "tok123", "dev9")

    @Test fun signInPostsCredentialsAndKeepsTheToken() {
        val http = FakeHttp(listOf("http://jf.local:8096/Users/AuthenticateByName" to (200 to
            """{"AccessToken":"tok123","ServerId":"s1","User":{"Id":"user1","Name":"Jeff"}}""")))
        val a = JellyfinClient.signIn(" jf.local:8096/ ", "jeff", "secret", "dev9", http)
        assertEquals(JellyfinAccount("http://jf.local:8096", "user1", "Jeff", "tok123", "dev9"), a)
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
        val http = FakeHttp(listOf("http://jf.local:8096/Users/AuthenticateByName" to (401 to "")))
        try {
            JellyfinClient.signIn("http://jf.local:8096", "jeff", "secret", "dev9", http)
            fail()
        } catch (e: JellyfinException) {
            assertEquals("Wrong username or password", e.message)
            assertFalse(e.message!!.contains("secret"))
        }
    }

    @Test fun unreachableServerIsAJellyfinError() {
        val http = JellyfinHttp { _, _, _, _ -> throw IOException("refused") }
        try {
            JellyfinClient.signIn("https://jf.example", "u", "p", "d", http)
            fail()
        } catch (e: JellyfinException) {
            assertTrue(e.message!!.startsWith("Can't reach the server"))
        }
    }

    @Test fun serverAddresses() {
        assertEquals("http://10.0.0.5:8096", JellyfinClient.normalizeServer("10.0.0.5:8096"))
        assertEquals("https://jf.example/jellyfin", JellyfinClient.normalizeServer("https://jf.example/jellyfin/"))
        try { JellyfinClient.normalizeServer("ftp://x"); fail() } catch (_: JellyfinException) {}
    }

    @Test fun viewsSkipMusicAndUseTheToken() {
        val http = FakeHttp(listOf("http://jf.local:8096/UserViews" to (200 to """{"Items":[
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
            "http://jf.local:8096/UserViews" to (404 to ""),
            "http://jf.local:8096/Users/user1/Views" to (200 to """{"Items":[{"Id":"m","Name":"Movies","CollectionType":"movies"}]}"""),
        ))
        assertEquals(listOf("Movies"), JellyfinClient(account, http).views().map { it.name })
        assertEquals(2, http.requests.size)
    }

    @Test fun itemsKeepFoldersAndVideos() {
        val http = FakeHttp(listOf("http://jf.local:8096/Items" to (200 to """{"Items":[
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
        val client = JellyfinClient(account, FakeHttp(listOf("http://jf.local:8096/Items" to (500 to "oops"))))
        try { client.items("x"); fail() } catch (e: JellyfinException) { assertEquals(500, e.code) }
        val notJson = JellyfinClient(account, FakeHttp(listOf("http://jf.local:8096/Items" to (200 to "<html>"))))
        try { notJson.items("x"); fail() } catch (e: JellyfinException) { assertTrue(e.message!!.contains("isn't Jellyfin")) }
        assertEquals(emptyList<JellyfinItem>(), JellyfinClient.parseItems(JSONObject("{}"), views = false))
    }

    @Test fun streamUrlIsTheDirectFile() {
        assertEquals("http://jf.local:8096/Videos/e%201/stream?static=true&api_key=tok123", JellyfinClient(account).streamUrl("e 1"))
    }
}
