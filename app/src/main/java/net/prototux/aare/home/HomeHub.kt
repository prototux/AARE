package net.prototux.aare.home

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.home.mqtt.MqttClient
import net.prototux.aare.home.mqtt.MqttCodec
import net.prototux.aare.home.mqtt.MqttRefusedException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import kotlin.math.max

/**
 * Runs home actions and keeps the tiles' values while a car screen is up: one MQTT connection
 * (reconnected with backoff), the HTTP tiles' polls, and, for a car session, the arrival triggers.
 * Shared by the car session and the on-phone preview, so they don't open two connections with the
 * same client id; the last [stop] disconnects everything.
 *
 * [start] and [stop] on the main thread; [run] and the tests from any coroutine.
 */
object HomeHub {
    /** A tile's value: [text] null when there is none yet (or the path found nothing). */
    data class TileValue(val text: String?, val failed: Boolean = false)

    enum class Link { OFF, CONNECTING, CONNECTED, RETRYING }

    private val _config = MutableStateFlow(HomeConfig())
    val config: StateFlow<HomeConfig> = _config
    private val _values = MutableStateFlow<Map<String, TileValue>>(emptyMap())
    val values: StateFlow<Map<String, TileValue>> = _values
    /** Actions being sent now (ids), for the buttons' progress. */
    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running: StateFlow<Set<String>> = _running
    private val _link = MutableStateFlow(Link.OFF)
    val link: StateFlow<Link> = _link

    private class Owner(val primary: Boolean, val location: () -> Location?, val onArrival: (HomeAction) -> Unit)

    private val owners = LinkedHashMap<Any, Owner>()
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var store: HomeStore? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var linksJob: Job? = null
    private var arrivalJob: Job? = null
    @Volatile private var client: MqttClient? = null
    // Saving writes two files: one reload for both.
    private val reload = Runnable { reload() }

    /**
     * A car screen is up. [primary]: a real car session, whose [location] (the car's, else the
     * phone's) drives the arrival triggers and whose [onArrival] offers the action (it never runs without a tap).
     */
    fun start(context: Context, owner: Any, primary: Boolean, location: () -> Location?, onArrival: (HomeAction) -> Unit) {
        owners[owner] = Owner(primary, location, onArrival)
        if (scope != null) { restartArrivals(); return }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val s = HomeStore(context)
        store = s
        listener = s.listen { main.removeCallbacks(reload); main.postDelayed(reload, 300) }
        reload()
    }

    fun stop(owner: Any) {
        if (owners.remove(owner) == null) return
        if (owners.isNotEmpty()) { restartArrivals(); return }
        main.removeCallbacks(reload)
        listener?.let { store?.unlisten(it) }
        listener = null
        store = null
        scope?.cancel()
        scope = null
        linksJob = null
        arrivalJob = null
        client?.close()
        client = null
        _link.value = Link.OFF
        _values.value = emptyMap()
        _running.value = emptySet()
    }

    private fun reload() {
        val s = scope ?: return
        val c = store?.load() ?: return
        if (c == _config.value && linksJob != null) return
        _config.value = c
        val tileIds = c.tiles.mapTo(HashSet()) { it.id }
        _values.update { v -> v.filterKeys { it in tileIds } }
        linksJob?.cancel()
        linksJob = s.launch { links(c) }
        restartArrivals()
    }

    private suspend fun links(c: HomeConfig) = coroutineScope {
        if (!c.enabled) { _link.value = Link.OFF; return@coroutineScope }
        if (c.usesMqtt) launch { mqttLoop(c) } else _link.value = Link.OFF
        for (t in c.tiles) {
            val src = t.source as? Source.Http ?: continue
            launch { poll(c, t, src) }
        }
    }

    // ---------------------------------------------------------------- MQTT

    private suspend fun mqttLoop(c: HomeConfig) {
        var backoffMs = 2_000L
        var failures = 0
        val filters = c.tiles.mapNotNull { (it.source as? Source.Mqtt)?.topic?.trim()?.takeIf(MqttCodec::validFilter) }.distinct()
        while (true) {
            _link.value = if (failures == 0) Link.CONNECTING else Link.RETRYING
            val s = scope ?: return
            val m = MqttClient(s, options(c.mqtt)) { topic, payload -> mqttMessage(c, topic, payload) }
            try {
                m.connect()
                client = m
                _link.value = Link.CONNECTED
                DiagLog.add("Home: connected to ${options(c.mqtt)}")
                failures = 0
                backoffMs = 2_000L
                if (filters.isNotEmpty()) m.subscribe(filters.map { it to 0 })
                try {
                    m.closed.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagLog.add("Home: MQTT connection lost: ${describe(e, c)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                // Not every attempt: the backoff grows to five minutes, the log stays readable.
                if (failures == 1 || failures % 10 == 0) DiagLog.add("Home: can't connect to ${options(c.mqtt)}: ${describe(e, c)}")
            } finally {
                if (client === m) client = null
                m.close()
            }
            _link.value = Link.RETRYING
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(300_000L)
        }
    }

    private fun options(b: MqttBroker) = MqttClient.Options(
        host = b.host.trim(), port = b.port, tls = b.tls,
        clientId = b.clientId.trim().ifBlank { "aare-" + HomeConfig.newId() },
        username = b.username.ifBlank { null }, password = b.password.ifBlank { null },
    )

    /** Reader thread. */
    private fun mqttMessage(c: HomeConfig, topic: String, payload: ByteArray) {
        val text = payload.decodeToString()
        for (t in c.tiles) {
            val src = t.source as? Source.Mqtt ?: continue
            if (!MqttCodec.topicMatches(src.topic.trim(), topic)) continue
            _values.update { it + (t.id to TileValue(JsonPath.extract(text, src.jsonPath))) }
        }
    }

    /** A connection just for [block], when the session's own isn't up (a test from the phone). */
    private suspend fun <T> oneShot(b: MqttBroker, block: suspend (MqttClient) -> T): T {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val m = MqttClient(s, options(b)) { _, _ -> }
        try {
            m.connect()
            return block(m)
        } finally {
            m.close()
            s.cancel()
        }
    }

    // ---------------------------------------------------------------- HTTP

    private suspend fun poll(c: HomeConfig, t: HomeTile, src: Source.Http) {
        var failures = 0
        while (true) {
            try {
                val body = withContext(Dispatchers.IO) { get(c, src) }
                _values.update { it + (t.id to TileValue(JsonPath.extract(body, src.jsonPath))) }
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                _values.update { it + (t.id to TileValue(it[t.id]?.text, failed = true)) }
                if (failures == 1) DiagLog.add("Home: tile \"${t.name}\" (${Redact.url(src.url)}) failed: ${describe(e, c)}")
            }
            delay(max(src.intervalS, HomeConfig.MIN_POLL_S) * 1_000L)
        }
    }

    private fun get(c: HomeConfig, src: Source.Http): String {
        val url = HomeHttp.resolve(c.http.baseUrl, src.url) ?: throw IOException("invalid URL")
        val r = HomeHttp.request("GET", url, src.headers, "", c.http)
        if (!r.ok) throw HomeHttp.HttpStatusException(r.code)
        return r.body
    }

    // ---------------------------------------------------------------- actions and tests

    /** Sends [action] with [c]'s connections. Returns null on success, else the error. */
    suspend fun run(action: HomeAction, c: HomeConfig = _config.value): Throwable? {
        _running.update { it + action.id }
        return try {
            withContext(Dispatchers.IO) { send(action.send, c) }
            DiagLog.add("Home: \"${action.name}\" sent (${describeSend(action.send)})")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.add("Home: \"${action.name}\" failed (${describeSend(action.send)}): ${describe(e, c)}")
            e
        } finally {
            _running.update { it - action.id }
        }
    }

    private suspend fun send(s: Send, c: HomeConfig) {
        when (s) {
            is Send.Http -> {
                val url = HomeHttp.resolve(c.http.baseUrl, s.url) ?: throw IOException("invalid URL")
                val r = HomeHttp.request(s.method, url, s.headers, s.body, c.http)
                if (!r.ok) throw HomeHttp.HttpStatusException(r.code)
            }
            is Send.Mqtt -> {
                val topic = s.topic.trim()
                if (!MqttCodec.validPublishTopic(topic)) throw IOException("invalid topic")
                if (!c.mqtt.configured) throw IOException("no MQTT broker set up")
                val payload = s.payload.encodeToByteArray()
                val live = client?.takeIf { !it.closed.isCompleted && c.mqtt == _config.value.mqtt }
                if (live != null) live.publish(topic, payload, s.qos, s.retain)
                else oneShot(c.mqtt) { it.publish(topic, payload, s.qos, s.retain) }
            }
        }
    }

    /** Connects to [b] once and disconnects: null if that worked, else the error. */
    suspend fun testBroker(b: MqttBroker, c: HomeConfig): Throwable? = try {
        withContext(Dispatchers.IO) { oneShot(b) { } }
        DiagLog.add("Home: test connection to ${options(b)} worked")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DiagLog.add("Home: test connection to ${options(b)} failed: ${describe(e, c.copy(mqtt = b))}")
        e
    }

    /** The tile's current value, fetched once (an MQTT tile waits a few seconds for a message, e.g. a retained one). */
    suspend fun test(tile: HomeTile, c: HomeConfig): Result<String?> = runCatching {
        withContext(Dispatchers.IO) {
            when (val src = tile.source) {
                is Source.Http -> JsonPath.extract(get(c, src), src.jsonPath)
                is Source.Mqtt -> {
                    val filter = src.topic.trim()
                    if (!MqttCodec.validFilter(filter)) throw IOException("invalid topic")
                    val first = CompletableDeferred<ByteArray>()
                    val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val m = MqttClient(s, options(c.mqtt)) { topic, payload -> if (MqttCodec.topicMatches(filter, topic)) first.complete(payload) }
                    try {
                        m.connect()
                        m.subscribe(listOf(filter to 0))
                        val payload = withTimeoutOrNull(5_000) { first.await() } ?: throw SocketTimeoutException("no message on $filter")
                        JsonPath.extract(payload.decodeToString(), src.jsonPath)
                    } finally {
                        m.close()
                        s.cancel()
                    }
                }
            }
        }
    }.onFailure { if (it is CancellationException) throw it }

    // ---------------------------------------------------------------- arrivals

    private fun restartArrivals() {
        arrivalJob?.cancel()
        arrivalJob = null
        val s = scope ?: return
        val c = _config.value
        val owner = owners.values.firstOrNull { it.primary } ?: return
        val places = c.actions.mapNotNull { a -> a.arrival?.takeIf { it.enabled }?.let { a.id to it } }.toMap()
        if (!c.enabled || places.isEmpty()) return
        arrivalJob = s.launch {
            val watch = ArrivalWatch()
            while (true) {
                // A fix from more than two minutes ago says nothing about where the car is now.
                val fix = owner.location()?.takeIf { System.currentTimeMillis() - it.time < 120_000 }
                if (fix != null) {
                    val arrived = watch.update(fix.latitude, fix.longitude, if (fix.hasAccuracy()) fix.accuracy else null, places)
                    for (id in arrived) {
                        val a = c.actions.firstOrNull { it.id == id } ?: continue
                        DiagLog.add("Home: arrived at the place of \"${a.name}\"")
                        owner.onArrival(a)
                    }
                }
                delay(5_000)
            }
        }
    }

    // ---------------------------------------------------------------- texts

    /** For the log: no secrets, whatever the exception's message quotes. */
    private fun describe(e: Throwable, c: HomeConfig): String {
        val secrets = buildList {
            add(c.mqtt.password)
            (c.http.headers + c.actions.flatMap { (it.send as? Send.Http)?.headers.orEmpty() } +
                c.tiles.flatMap { (it.source as? Source.Http)?.headers.orEmpty() }).forEach { add(it.value) }
        }
        return Redact.webhooks(Redact.text("${e.javaClass.simpleName}: ${e.message.orEmpty()}", secrets))
    }

    private fun describeSend(s: Send): String = when (s) {
        is Send.Http -> "${s.method} ${Redact.url(s.url)}"
        is Send.Mqtt -> "MQTT ${s.topic}"
    }

    /** Why an action or test failed, in a few words for the car screen or the phone. */
    fun reason(context: Context, e: Throwable): String = when (e) {
        is HomeHttp.HttpStatusException -> context.getString(R.string.home_error_http, e.code)
        is MqttRefusedException -> context.getString(if (e.code == 4 || e.code == 5) R.string.home_error_mqtt_auth else R.string.home_error_mqtt_refused)
        is UnknownHostException -> context.getString(R.string.home_error_unknown_host)
        is SocketTimeoutException -> context.getString(R.string.home_error_timeout)
        // Android refuses plain http:// unless the app opts in.
        is UnknownServiceException -> context.getString(R.string.home_error_cleartext)
        is SSLException -> context.getString(R.string.home_error_tls)
        else -> e.message?.takeIf { it.isNotBlank() && it.length < 80 } ?: context.getString(R.string.home_error_generic)
    }
}
