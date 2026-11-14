package net.prototux.aare.home

import org.json.JSONArray
import org.json.JSONObject

/**
 * [HomeConfig] as versioned JSON, in two parts: the settings, kept with AARE's other settings, and
 * the secrets (the broker's password and every header value), kept in a file of their own so they
 * never leave the phone with the settings. Both carry `"version"`; a newer version is read as far
 * as it is understood.
 */
object HomeJson {
    const val VERSION = 1

    /** The settings part and the secrets part of [c]. */
    fun encode(c: HomeConfig): Pair<String, String> {
        val headers = JSONObject()
        fun putHeaders(key: String, list: List<Header>) {
            if (list.isNotEmpty()) headers.put(key, JSONArray().apply { list.forEach { put(JSONArray().put(it.name).put(it.value)) } })
        }
        putHeaders(HTTP_KEY, c.http.headers)
        val pub = JSONObject().put("version", VERSION).put("enabled", c.enabled)
        pub.put("mqtt", JSONObject().put("host", c.mqtt.host).put("port", c.mqtt.port).put("tls", c.mqtt.tls)
            .put("username", c.mqtt.username).put("clientId", c.mqtt.clientId))
        pub.put("http", JSONObject().put("baseUrl", c.http.baseUrl))
        pub.put("actions", JSONArray().apply {
            c.actions.forEach { a ->
                val o = JSONObject().put("id", a.id).put("name", a.name).put("confirm", a.confirm).put("onCard", a.onCard)
                when (val s = a.send) {
                    is Send.Mqtt -> o.put("type", "mqtt").put("topic", s.topic).put("payload", s.payload).put("qos", s.qos).put("retain", s.retain)
                    is Send.Http -> {
                        o.put("type", "http").put("method", s.method).put("url", s.url).put("body", s.body)
                        putHeaders("action:${a.id}", s.headers)
                    }
                }
                a.arrival?.let { r ->
                    o.put("arrival", JSONObject().put("lat", r.latitude).put("lon", r.longitude).put("radius", r.radiusM).put("enabled", r.enabled))
                }
                put(o)
            }
        })
        pub.put("tiles", JSONArray().apply {
            c.tiles.forEach { t ->
                val o = JSONObject().put("id", t.id).put("name", t.name).put("unit", t.unit)
                when (val s = t.source) {
                    is Source.Mqtt -> o.put("type", "mqtt").put("topic", s.topic).put("jsonPath", s.jsonPath)
                    is Source.Http -> {
                        o.put("type", "http").put("url", s.url).put("jsonPath", s.jsonPath).put("interval", s.intervalS)
                        putHeaders("tile:${t.id}", s.headers)
                    }
                }
                put(o)
            }
        })
        val secret = JSONObject().put("version", VERSION).put("mqttPassword", c.mqtt.password).put("headers", headers)
        return pub.toString() to secret.toString()
    }

    /** The config from its two parts; anything missing or unreadable falls back to its default. */
    fun decode(public: String?, secrets: String?): HomeConfig {
        val pub = public?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return HomeConfig()
        val sec = secrets?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val headers = sec.optJSONObject("headers") ?: JSONObject()
        fun headersOf(key: String): List<Header> {
            val arr = headers.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONArray(i)?.let { h -> Header(h.optString(0), h.optString(1)).takeIf { it.name.isNotBlank() } }
            }
        }
        val m = pub.optJSONObject("mqtt") ?: JSONObject()
        val mqtt = MqttBroker(
            host = m.optString("host"), port = m.optInt("port", 1883), tls = m.optBoolean("tls"),
            username = m.optString("username"), password = sec.optString("mqttPassword"), clientId = m.optString("clientId"),
        )
        val http = HttpServer(pub.optJSONObject("http")?.optString("baseUrl").orEmpty(), headersOf(HTTP_KEY))
        val actions = pub.optJSONArray("actions").objects().mapNotNull { o ->
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val send = when (o.optString("type")) {
                "mqtt" -> Send.Mqtt(o.optString("topic"), o.optString("payload"), o.optInt("qos").coerceIn(0, 1), o.optBoolean("retain"))
                "http" -> Send.Http(o.optString("method", "POST"), o.optString("url"), headersOf("action:$id"), o.optString("body"))
                else -> return@mapNotNull null // a kind from a newer version
            }
            val arrival = o.optJSONObject("arrival")?.let { r ->
                Arrival(r.optDouble("lat"), r.optDouble("lon"), r.optInt("radius", 150), r.optBoolean("enabled"))
                    .takeUnless { it.latitude.isNaN() || it.longitude.isNaN() }
            }
            HomeAction(id, o.optString("name"), send, o.optBoolean("confirm"), o.optBoolean("onCard", true), arrival)
        }
        val tiles = pub.optJSONArray("tiles").objects().mapNotNull { o ->
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            val source = when (o.optString("type")) {
                "mqtt" -> Source.Mqtt(o.optString("topic"), o.optString("jsonPath"))
                "http" -> Source.Http(o.optString("url"), headersOf("tile:$id"), o.optString("jsonPath"), o.optInt("interval", 60))
                else -> return@mapNotNull null
            }
            HomeTile(id, o.optString("name"), source, o.optString("unit"))
        }
        return HomeConfig(pub.optBoolean("enabled"), mqtt, http, actions, tiles)
    }

    private const val HTTP_KEY = "http"

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
