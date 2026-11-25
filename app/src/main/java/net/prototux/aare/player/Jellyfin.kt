package net.prototux.aare.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import net.prototux.aare.BuildConfig
import net.prototux.aare.Settings
import net.prototux.aare.home.HomeHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** A signed-in Jellyfin account (kept in the app's private settings; the password never is). */
data class JellyfinAccount(
    val server: String,
    val userId: String,
    val userName: String,
    val token: String,
    val deviceId: String,
    val allowLanHttp: Boolean = false,
) {
    /** The server for display, without its scheme. */
    val serverLabel: String get() = server.substringAfter("://")

    fun save(settings: Settings) {
        settings.jellyfinServer = server
        settings.jellyfinAllowLanHttp = allowLanHttp
        settings.jellyfinUserId = userId
        settings.jellyfinUserName = userName
        settings.jellyfinToken = token
    }

    companion object {
        fun load(settings: Settings): JellyfinAccount? {
            val server = settings.jellyfinServer ?: return null
            val token = settings.jellyfinToken ?: return null
            val user = settings.jellyfinUserId ?: return null
            return JellyfinAccount(server, user, settings.jellyfinUserName ?: "", token, settings.instanceId, settings.jellyfinAllowLanHttp)
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

/**
 * How to play one video: [url] is the file as it is, or (with [playSessionId]) an HLS stream the
 * server re-encodes to fit the bitrate cap, which [JellyfinClient.stopEncoding] ends. [note] says
 * which, for the log.
 */
data class JellyfinStream(val url: String, val playSessionId: String?, val note: String)

/** What a Jellyfin request failed with, in words for the car screen or the phone. */
open class JellyfinException(message: String, val code: Int = 0) : IOException(message)

/**
 * Plain http:// refused: to an address outside the home network ([outsideHome]), or to one on
 * it while "Allow plain http on my home network" is off.
 */
class PlainHttpRefused(val outsideHome: Boolean) : JellyfinException(
    if (outsideHome) "Plain http:// only works on your home network: use https://"
    else "Plain http:// is off: turn on \"Allow plain http on my home network\"",
) {
    fun userMessage(context: android.content.Context): String = context.getString(
        if (outsideHome) net.prototux.aare.R.string.jellyfin_error_http_outside_home
        else net.prototux.aare.R.string.jellyfin_error_http_lan_off,
    )
}

/** The HTTP the client needs, replaceable in tests. */
fun interface JellyfinHttp {
    /** Returns the status code and body. [body] is JSON, or null for a GET. */
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, ByteArray>
}

/**
 * Plain HttpURLConnection, with timeouts that suit a car (a slow server shouldn't hang a screen).
 * Enforces [HomeNetwork.requireHomeAddress] before connecting, and follows redirects only on the
 * same host and never from https down to http.
 */
class UrlJellyfinHttp(
    private val allowLanHttp: Boolean = false,
    private val lookup: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : JellyfinHttp {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, ByteArray> {
        var current = URL(url)
        var m = method
        var b = body
        for (hop in 0..MAX_REDIRECTS) {
            HomeNetwork.requireHomeAddress(current, allowLanHttp, lookup)
            val c = current.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.requestMethod = m
                c.connectTimeout = 8_000
                c.readTimeout = 15_000
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val payload = b
                if (payload != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(payload.toByteArray()) }
                }
                val code = c.responseCode
                if (code in 300..399 && code != 304) {
                    val loc = c.getHeaderField("Location") ?: return code to ByteArray(0)
                    val next = runCatching { URL(current, loc) }.getOrNull()
                    if (next == null || !HomeHttp.redirectAllowed(current, next)) throw IOException("redirect to another host or http refused")
                    if (code != 307 && code != 308) { m = "GET"; b = null }
                    current = next
                    continue
                }
                val bytes = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                return code to bytes
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    companion object {
        private const val MAX_REDIRECTS = 3
    }
}

/**
 * The few Jellyfin server calls the car needs: sign in, the user's libraries, a folder's items,
 * a video's direct stream, and pictures. Errors become [JellyfinException]s whose messages never
 * contain the password or the access token.
 */
class JellyfinClient(private val account: JellyfinAccount, private val http: JellyfinHttp = UrlJellyfinHttp(account.allowLanHttp)) {
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

    /**
     * How to play [itemId] within [maxKbps] (0: no cap): the file as it is when it fits (or its
     * bitrate is unknown), else an HLS stream the server re-encodes (H.264, stereo AAC) to fit.
     * [audioLanguage] (ISO 639-2, "eng") picks the soundtrack of a re-encode; a file played as it
     * is leaves that to the player.
     */
    fun stream(itemId: String, maxKbps: Int, audioLanguage: String?): JellyfinStream {
        val source = get("/Items/${enc(itemId)}?userId=${enc(account.userId)}&Fields=MediaSources").optJSONArray("MediaSources")?.optJSONObject(0)
        val kbps = (source?.optLong("Bitrate", 0L) ?: 0L) / 1000
        // A little over the cap still plays as it is: re-encoding loses more than the extra costs.
        if (maxKbps <= 0 || kbps <= 0 || kbps <= maxKbps * CAP_MARGIN) {
            return JellyfinStream(streamUrl(itemId), null, if (kbps > 0) "the file as it is ($kbps kbps)" else "the file as it is")
        }
        val sourceId = source?.optString("Id")?.takeIf { it.isNotEmpty() } ?: itemId
        val audioIndex = source?.optJSONArray("MediaStreams")?.let { streams ->
            (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }
                .firstOrNull { it.optString("Type") == "Audio" && audioLanguage != null && it.optString("Language").equals(audioLanguage, ignoreCase = true) }
                ?.optInt("Index", -1)?.takeIf { it >= 0 }
        }
        val session = UUID.randomUUID().toString().replace("-", "")
        val totalBps = maxKbps * 1000L
        val url = "$base/Videos/${enc(itemId)}/master.m3u8?MediaSourceId=${enc(sourceId)}&DeviceId=${enc(account.deviceId)}&PlaySessionId=$session" +
            "&VideoCodec=h264&AudioCodec=aac&MaxAudioChannels=2&AudioBitrate=$AUDIO_BPS&VideoBitrate=${(totalBps - AUDIO_BPS).coerceAtLeast(500_000L)}" +
            "&SegmentContainer=ts&BreakOnNonKeyFrames=false" + (audioIndex?.let { "&AudioStreamIndex=$it" } ?: "") + "&ApiKey=${enc(account.token)}"
        return JellyfinStream(url, session, "re-encoded by the server: $kbps kbps file, at most $maxKbps kbps" + (audioIndex?.let { ", soundtrack $audioLanguage" } ?: ""))
    }

    /**
     * The headers for the player's requests to the server. Jellyfin 12 refuses a re-encode's
     * playlists and segments with only the key in the URL's old `api_key` form, so the sign-in
     * goes as a header too.
     */
    fun playbackHeaders(): Map<String, String> = authHeaders(account.token) - "Accept"

    /** Ends the server's re-encode of a [stream] (best effort; the server also ends idle ones). */
    fun stopEncoding(playSessionId: String) {
        val url = "$base/Videos/ActiveEncodings?DeviceId=${enc(account.deviceId)}&PlaySessionId=${enc(playSessionId)}"
        HomeNetwork.plainHttpRefusal(URL(url), account.allowLanHttp)?.let { throw it }
        http.request("DELETE", url, authHeaders(account.token), null)
    }

    /** The video file as it is on the server (ExoPlayer plays MP4, MKV, WebM...), authorised by the URL. */
    fun streamUrl(itemId: String): String = "$base/Videos/${enc(itemId)}/stream?static=true&ApiKey=${enc(account.token)}"

    fun imageBytes(itemId: String, maxHeight: Int): ByteArray? {
        val url = "$base/Items/${enc(itemId)}/Images/Primary?maxHeight=$maxHeight&quality=80"
        HomeNetwork.plainHttpRefusal(URL(url), account.allowLanHttp)?.let { throw it }
        val (code, body) = http.request("GET", url, authHeaders(account.token), null)
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

    private fun call(method: String, url: String, body: String?, headers: Map<String, String>): JSONObject {
        HomeNetwork.plainHttpRefusal(URL(url), account.allowLanHttp)?.let { throw it }
        return Companion.call(http, method, url, body, headers)
    }

    private fun authHeaders(token: String?) = authHeaders(account.deviceId, token)

    companion object {
        const val DEFAULT_SERVER = "https://"
        private const val PAGE = 500
        private const val AUDIO_BPS = 192_000L
        private const val CAP_MARGIN = 1.15
        private val images = LruCache<String, Bitmap>(48)
        private val VIDEO_TYPES = setOf("Movie", "Episode", "Video", "MusicVideo", "Trailer")
        /** Library kinds without videos. */
        private val SKIPPED_VIEWS = setOf("music", "books", "photos", "playlists", "livetv", "audiobooks")

        /**
         * Signs in with [user] and [password]; returns the account to keep. The password is sent
         * once, in the request body, and not kept or logged.
         */
        fun signIn(
            server: String,
            user: String,
            password: String,
            deviceId: String,
            allowLanHttp: Boolean = false,
            http: JellyfinHttp = UrlJellyfinHttp(allowLanHttp),
        ): JellyfinAccount {
            val base = normalizeServer(server)
            HomeNetwork.plainHttpRefusal(URL(base), allowLanHttp)?.let { throw it }
            val body = JSONObject().put("Username", user).put("Pw", password).toString()
            val json = try {
                call(http, "POST", "$base/Users/AuthenticateByName", body, authHeaders(deviceId, null))
            } catch (e: JellyfinException) {
                throw if (e.code == 401) JellyfinException("Wrong username or password", 401) else e
            }
            val token = json.optString("AccessToken").takeIf { it.isNotEmpty() } ?: throw JellyfinException("The server didn't sign in (no access token)")
            val u = json.optJSONObject("User")
            val userId = u?.optString("Id")?.takeIf { it.isNotEmpty() } ?: throw JellyfinException("The server didn't say who signed in")
            return JellyfinAccount(base, userId, u.optString("Name", user), token, deviceId, allowLanHttp)
        }

        /** "jelly.example:8096/" → "https://jelly.example:8096"; an http(s) scheme given is kept. */
        fun normalizeServer(server: String): String {
            val trimmed = server.trim()
            if (trimmed.isEmpty()) throw JellyfinException("No server address")
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val normalized = withScheme.trimEnd('/')
            val uri = runCatching { URI(normalized) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            if (uri == null || uri.host.isNullOrEmpty() || (scheme != "http" && scheme != "https")) {
                throw JellyfinException("Not a server address: $server")
            }
            return scheme + normalized.substring(uri.scheme.length)
        }

        /** Why plain http for [server] is refused, or null (also when [server] isn't a valid URL yet). */
        fun plainHttpRefusal(server: String, allowLanHttp: Boolean): PlainHttpRefused? {
            val normalized = runCatching { normalizeServer(server) }.getOrNull() ?: return null
            return runCatching { HomeNetwork.plainHttpRefusal(URL(normalized), allowLanHttp) }.getOrNull()
        }

        fun hasValidServer(server: String): Boolean = runCatching { normalizeServer(server) }.isSuccess

        /** Jellyfin's client identification, with the access token once signed in. */
        fun authHeaders(deviceId: String, token: String?): Map<String, String> {
            val device = "${Build.MANUFACTURER ?: ""} ${Build.MODEL ?: ""}".trim().ifEmpty { "Android" }
            val value = "MediaBrowser Client=\"AARE\", Device=\"${quote(device)}\", DeviceId=\"${quote(deviceId)}\", Version=\"${quote(BuildConfig.VERSION_NAME)}\"" +
                (token?.let { ", Token=\"${quote(it)}\"" } ?: "")
            return mapOf("Authorization" to value, "Accept" to "application/json")
        }

        private fun quote(s: String) = s.replace("\"", "'")

        private fun call(http: JellyfinHttp, method: String, url: String, body: String?, headers: Map<String, String>): JSONObject {
            val (code, bytes) = try {
                http.request(method, url, headers, body)
            } catch (e: JellyfinException) {
                throw e
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
