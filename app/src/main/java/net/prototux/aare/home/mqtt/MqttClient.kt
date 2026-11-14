package net.prototux.aare.home.mqtt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A broker refused the connection (CONNACK with a non-zero [code]). */
class MqttRefusedException(val code: Int) : IOException("broker refused: ${MqttCodec.connAckReason(code)}")

/**
 * A minimal MQTT 3.1.1 client: connect, publish at QoS 0 or 1, subscribe, keep alive, disconnect.
 * One connection per instance: after [closed] completes, make a new one to reconnect.
 *
 * Reads run on [scope]'s IO thread; [onMessage] is called from there, for every PUBLISH the
 * broker sends. Writes from any coroutine are serialised.
 */
class MqttClient(
    private val scope: CoroutineScope,
    private val options: Options,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
) {
    data class Options(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val clientId: String,
        val username: String? = null,
        val password: String? = null,
        val keepAliveS: Int = 30,
        val timeoutMs: Int = 10_000,
        /** Largest message accepted from the broker: tiles only need small values. */
        val maxPacket: Int = 256 * 1024,
    ) {
        /** Without the password: for the connection log. */
        override fun toString() = "${if (tls) "mqtts" else "mqtt"}://${username?.let { "$it@" } ?: ""}$host:$port"
    }

    @Volatile private var socket: Socket? = null
    private var output: OutputStream? = null
    private val writeLock = Any()
    private val nextId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<MqttPacket>>()
    private var jobs: List<Job> = emptyList()

    /** Completes when the connection ends, with the error that ended it, or normally after [close]. */
    val closed = CompletableDeferred<Unit>()

    /** Opens the socket and logs in. Throws on a network error, a refusal ([MqttRefusedException]) or a timeout. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        val s = openSocket(options.host, options.port, options.tls, options.timeoutMs)
        socket = s
        try {
            output = s.getOutputStream()
            val input = BufferedInputStream(s.getInputStream())
            write(MqttPacket.Connect(options.clientId, options.username, options.password, options.keepAliveS))
            val ack = MqttCodec.read(input, options.maxPacket) as? MqttPacket.ConnAck
                ?: throw MqttProtocolException("expected CONNACK")
            if (ack.returnCode != 0) throw MqttRefusedException(ack.returnCode)
            // The broker answers every PINGREQ: no packet for 1.5 keep-alives means the link is gone.
            s.soTimeout = if (options.keepAliveS > 0) options.keepAliveS * 1_500 else 0
            jobs = listOf(scope.launch(Dispatchers.IO) { readLoop(input) }, scope.launch(Dispatchers.IO) { pingLoop() })
        } catch (e: Throwable) {
            shutDown(e)
            throw e
        }
    }

    /** Publishes; at QoS 1, returns once the broker acknowledged it. */
    suspend fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
        require(MqttCodec.validPublishTopic(topic)) { "invalid topic" }
        if (qos == 0) {
            withContext(Dispatchers.IO) { write(MqttPacket.Publish(topic, payload, 0, retain)) }
            return
        }
        val id = packetId()
        request(id) { write(MqttPacket.Publish(topic, payload, 1, retain, packetId = id)) }
    }

    /** Subscribes to [filters] (topic filter, QoS); returns the granted QoS or [MqttCodec.SUBACK_FAILURE] for each. */
    suspend fun subscribe(filters: List<Pair<String, Int>>): List<Int> {
        val id = packetId()
        val ack = request(id) { write(MqttPacket.Subscribe(id, filters)) } as? MqttPacket.SubAck
            ?: throw MqttProtocolException("expected SUBACK")
        return ack.returnCodes
    }

    /** Says goodbye (DISCONNECT) and closes. Safe to call more than once, from any thread. */
    fun close() {
        if (closed.isCompleted) return
        runCatching { write(MqttPacket.Disconnect) }
        shutDown(null)
    }

    private suspend fun request(id: Int, send: () -> Unit): MqttPacket {
        val reply = CompletableDeferred<MqttPacket>()
        pending[id] = reply
        try {
            withContext(Dispatchers.IO) { send() }
            return withTimeout(options.timeoutMs.toLong()) { reply.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun readLoop(input: java.io.InputStream) {
        try {
            while (true) {
                when (val p = MqttCodec.read(input, options.maxPacket)) {
                    is MqttPacket.Publish -> {
                        if (p.qos == 1) write(MqttPacket.PubAck(p.packetId))
                        runCatching { onMessage(p.topic, p.payload) }
                    }
                    is MqttPacket.PubAck -> pending[p.packetId]?.complete(p)
                    is MqttPacket.SubAck -> pending[p.packetId]?.complete(p)
                    else -> Unit // PINGRESP and anything else only prove the link is alive
                }
            }
        } catch (e: SocketTimeoutException) {
            shutDown(IOException("no answer from the broker for ${options.keepAliveS * 3 / 2} s"))
        } catch (e: Throwable) {
            shutDown(e)
        }
    }

    private suspend fun pingLoop() {
        if (options.keepAliveS <= 0) return
        while (scope.isActive && !closed.isCompleted) {
            delay(options.keepAliveS * 750L)
            runCatching { write(MqttPacket.PingReq) }.onFailure { shutDown(it) }
        }
    }

    private fun write(p: MqttPacket) {
        val bytes = MqttCodec.encode(p)
        synchronized(writeLock) {
            val out = output ?: throw IOException("not connected")
            out.write(bytes)
            out.flush()
        }
    }

    private fun packetId(): Int {
        // 1..65535: 0 isn't a valid packet id.
        while (true) {
            val id = nextId.incrementAndGet() and 0xFFFF
            if (id != 0) return id
        }
    }

    @Synchronized
    private fun shutDown(cause: Throwable?) {
        runCatching { socket?.close() }
        output = null
        val error = cause ?: IOException("closed")
        pending.values.forEach { it.completeExceptionally(error) }
        jobs.forEach { it.cancel() }
        if (cause == null) closed.complete(Unit) else closed.completeExceptionally(cause)
    }

    companion object {
        /**
         * A plain or TLS socket. TLS uses the system's trust store and checks the certificate is for
         * [host] (an SSLSocket alone doesn't): there is deliberately no way to accept any certificate.
         */
        fun openSocket(host: String, port: Int, tls: Boolean, timeoutMs: Int): Socket {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(host, port), timeoutMs)
                plain.soTimeout = timeoutMs
                if (!tls) return plain
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                    ssl.close()
                    throw SSLPeerUnverifiedException("the broker's certificate isn't for $host")
                }
                return ssl
            } catch (e: Throwable) {
                runCatching { plain.close() }
                throw e
            }
        }
    }
}
