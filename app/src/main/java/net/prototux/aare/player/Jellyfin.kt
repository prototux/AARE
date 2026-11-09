package net.prototux.aare.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import net.prototux.aare.BuildConfig
import net.prototux.aare.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** A signed-in Jellyfin account (kept in the app's private settings; the password never is). */
data class JellyfinAccount(val server: String, val userId: String, val userName: String, val token: String, val deviceId: String) {
    /** The server for display, without its scheme. */
    val serverLabel: String get() = server.substringAfter("://")

    fun save(settings: Settings) {
        settings.jellyfinServer = server; settings.jellyfinUserId = userId; settings.jellyfinUserName = userName; settings.jellyfinToken = token
    }

    companion object {
        fun load(settings: Settings): JellyfinAccount? {
            val server = settings.jellyfinServer ?: return null
            val token = settings.jellyfinToken ?: return null
            val user = settings.jellyfinUserId ?: return null
            return JellyfinAccount(server, user, settings.jellyfinUserName ?: "", token, settings.instanceId)
        }

        fun clear(settings: Settings) {
            settings.jellyfinToken = null; settings.jellyfinUserId = null; settings.jellyfinUserName = null
        }
    }
}

/** One entry of a Jellyfin library list: a folder (library, series, season, collection) or a video. */
data class JellyfinItem(
    val id: String,
    val name: String,
    val type: String,
    val isFolder: Boolean,
    val runtimeMs: Long?,
    val year: Int?,
    val seriesName: String?,
    val hasImage: Boolean,
)

/** What a Jellyfin request failed with, in words for the car screen or the phone. */
class JellyfinException(message: String, val code: Int = 0) : IOException(message)

/** The HTTP the client needs, replaceable in tests. */
fun interface JellyfinHttp {
    /** Returns the status code and body. [body] is JSON, or null for a GET. */
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, ByteArray>
}

/** Plain HttpURLConnection, with timeouts that suit a car (a slow server shouldn't hang a screen). */
object UrlJellyfinHttp : JellyfinHttp {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8_000
            c.readTimeout = 15_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            return code to bytes
        } finally {
            c.disconnect()
        }
    }
}

/**
 * The few Jellyfin server calls the car needs: sign in, the user's libraries, a folder's items,
 * a video's direct stream, and pictures. Errors become [JellyfinException]s whose messages never
 * contain the password or the access token.
 */
class JellyfinClient(private val account: JellyfinAccount, private val http: JellyfinHttp = UrlJellyfinHttp) {
    private val base = account.server

    /** The libraries of the user (movies, shows, home videos...), music and books left out. */
    fun views(): List<JellyfinItem> {
        val json = try {
            get("/UserViews?userId=${enc(account.userId)}")
        } catch (e: JellyfinException) {
            // Servers before 10.9 only have the older route.
            if (e.code != 404) throw e
            get("/Users/${enc(account.userId)}/Views")
        }
        return parseItems(json, views = true)
    }

    /** The items in [parentId], folders first then by name; only folders and videos. */
    fun items(parentId: String): List<JellyfinItem> = parseItems(get(
        "/Items?userId=${enc(account.userId)}&ParentId=${enc(parentId)}&SortBy=IsFolder,SortName&SortOrder=Descending,Ascending" +
            "&Fields=ProductionYear,RunTimeTicks&EnableImageTypes=Primary&ImageTypeLimit=1&Limit=$PAGE",
    ), views = false)

    /** The video file as it is on the server (ExoPlayer plays MP4, MKV, WebM...), authorised by the URL. */
    fun streamUrl(itemId: String): String = "$base/Videos/${enc(itemId)}/stream?static=true&api_key=${enc(account.token)}"

    fun imageBytes(itemId: String, maxHeight: Int): ByteArray? {
        val (code, body) = http.request("GET", "$base/Items/${enc(itemId)}/Images/Primary?maxHeight=$maxHeight&quality=80", authHeaders(account.token), null)
        return body.takeIf { code in 200..299 && it.isNotEmpty() }
    }

    /** The item's picture, cached; null if it has none (Android only: decodes a bitmap). */
    fun image(itemId: String, maxHeight: Int): Bitmap? {
        val key = "$base|$itemId|$maxHeight"
        images.get(key)?.let { return it }
        val bytes = imageBytes(itemId, maxHeight) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { images.put(key, it) }
    }

    private fun get(path: String): JSONObject = call("GET", base + path, null, authHeaders(account.token))

    private fun call(method: String, url: String, body: String?, headers: Map<String, String>): JSONObject =
        Companion.call(http, method, url, body, headers)

    private fun authHeaders(token: String?) = authHeaders(account.deviceId, token)

    companion object {
        private const val PAGE = 500
        private val images = LruCache<String, Bitmap>(48)
        private val VIDEO_TYPES = setOf("Movie", "Episode", "Video", "MusicVideo", "Trailer")
        /** Library kinds without videos. */
        private val SKIPPED_VIEWS = setOf("music", "books", "photos", "playlists", "livetv", "audiobooks")

        /**
         * Signs in with [user] and [password]; returns the account to keep. The password is sent
         * once, in the request body, and not kept or logged.
         */
        fun signIn(server: String, user: String, password: String, deviceId: String, http: JellyfinHttp = UrlJellyfinHttp): JellyfinAccount {
            val base = normalizeServer(server)
            val body = JSONObject().put("Username", user).put("Pw", password).toString()
            val json = try {
                call(http, "POST", "$base/Users/AuthenticateByName", body, authHeaders(deviceId, null))
            } catch (e: JellyfinException) {
                throw if (e.code == 401) JellyfinException("Wrong username or password", 401) else e
            }
            val token = json.optString("AccessToken").takeIf { it.isNotEmpty() } ?: throw JellyfinException("The server didn't sign in (no access token)")
            val u = json.optJSONObject("User")
            val userId = u?.optString("Id")?.takeIf { it.isNotEmpty() } ?: throw JellyfinException("The server didn't say who signed in")
            return JellyfinAccount(base, userId, u.optString("Name", user), token, deviceId)
        }

        /** "jelly.local:8096/" → "http://jelly.local:8096"; a scheme given is kept. */
        fun normalizeServer(server: String): String {
            val s = server.trim().trimEnd('/')
            require(s.isNotEmpty()) { "No server address" }
            val withScheme = if ("://" in s) s else "http://$s"
            val uri = runCatching { URI(withScheme) }.getOrNull()
            if (uri?.host.isNullOrEmpty() || (uri!!.scheme != "http" && uri.scheme != "https")) throw JellyfinException("Not a server address: $server")
            return withScheme
        }

        /** Jellyfin's client identification, with the access token once signed in. */
        fun authHeaders(deviceId: String, token: String?): Map<String, String> {
            val device = "${Build.MANUFACTURER ?: ""} ${Build.MODEL ?: ""}".trim().ifEmpty { "Android" }
            val value = "MediaBrowser Client=\"AARE\", Device=\"${quote(device)}\", DeviceId=\"${quote(deviceId)}\", Version=\"${quote(BuildConfig.VERSION_NAME ?: "")}\"" +
                (token?.let { ", Token=\"${quote(it)}\"" } ?: "")
            return mapOf("Authorization" to value, "Accept" to "application/json")
        }

        private fun quote(s: String) = s.replace("\"", "'")

        private fun call(http: JellyfinHttp, method: String, url: String, body: String?, headers: Map<String, String>): JSONObject {
            val (code, bytes) = try {
                http.request(method, url, headers, body)
            } catch (e: IOException) {
                throw JellyfinException("Can't reach the server (${e.javaClass.simpleName})")
            }
            if (code !in 200..299) throw JellyfinException("The server answered $code", code)
            return try {
                JSONObject(String(bytes))
            } catch (e: Exception) {
                throw JellyfinException("The server's answer isn't Jellyfin's")
            }
        }

        /** Items of a list answer, keeping folders and videos (and, for [views], libraries that have videos). */
        fun parseItems(json: JSONObject, views: Boolean): List<JellyfinItem> {
            val arr: JSONArray = json.optJSONArray("Items") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("Id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val type = o.optString("Type")
                val folder = o.optBoolean("IsFolder", views)
                if (views && o.optString("CollectionType").lowercase() in SKIPPED_VIEWS) return@mapNotNull null
                if (!views && !folder && type !in VIDEO_TYPES && o.optString("MediaType") != "Video") return@mapNotNull null
                JellyfinItem(
                    id = id,
                    name = o.optString("Name").ifEmpty { id },
                    type = type,
                    isFolder = folder,
                    runtimeMs = o.optLong("RunTimeTicks", 0L).takeIf { it > 0 }?.div(10_000),
                    year = o.optInt("ProductionYear", 0).takeIf { it > 0 },
                    seriesName = o.optString("SeriesName").takeIf { it.isNotEmpty() },
                    hasImage = o.optJSONObject("ImageTags")?.has("Primary") == true,
                )
            }
        }

        /** Percent-encodes for a path segment or a query value (a space is %20, not the form encoding's +). */
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
