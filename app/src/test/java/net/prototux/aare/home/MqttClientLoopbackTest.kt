package net.prototux.aare.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.prototux.aare.home.mqtt.MqttClient
import net.prototux.aare.home.mqtt.MqttCodec
import net.prototux.aare.home.mqtt.MqttPacket
import net.prototux.aare.home.mqtt.MqttRefusedException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** [MqttClient] against a tiny broker on a loopback socket, speaking the codec's packets. */
class MqttClientLoopbackTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    /** What the broker received, in order. */
    private val received = LinkedBlockingQueue<MqttPacket>()

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    /** Accepts one client and answers it like a broker: [connAck] to CONNECT, and the rest as MQTT says. */
    private fun broker(connAck: Int = 0, onSubscribe: (MqttPacket.Subscribe, (MqttPacket) -> Unit) -> Unit = { _, _ -> }) = thread(isDaemon = true) {
        runCatching {
            server.accept().use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                fun send(p: MqttPacket) = synchronized(out) { out.write(MqttCodec.encode(p)); out.flush() }
                while (true) {
                    val p = MqttCodec.read(input, 1 shl 20)
                    received += p
                    when (p) {
                        is MqttPacket.Connect -> send(MqttPacket.ConnAck(false, connAck))
                        is MqttPacket.Subscribe -> { send(MqttPacket.SubAck(p.packetId, p.filters.map { it.second })); onSubscribe(p, ::send) }
                        is MqttPacket.Publish -> if (p.qos == 1) send(MqttPacket.PubAck(p.packetId))
                        MqttPacket.PingReq -> send(MqttPacket.PingResp)
                        MqttPacket.Disconnect -> return@use
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun options(keepAliveS: Int = 30) =
        MqttClient.Options("127.0.0.1", server.localPort, tls = false, clientId = "aare-test", username = "jo", password = "pw", keepAliveS = keepAliveS, timeoutMs = 5_000)

    private inline fun <reified T : MqttPacket> next(): T {
        val p = received.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("broker received nothing")
        assertTrue("expected ${T::class.simpleName}, got $p", p is T)
        return p as T
    }

    @Test
    fun connectsSubscribesPublishesAndDisconnects() = runBlocking {
        val messages = CopyOnWriteArrayList<Pair<String, String>>()
        val got = LinkedBlockingQueue<Unit>()
        broker { sub, send -> send(MqttPacket.Publish(sub.filters[0].first.replace("+", "garage"), "open".encodeToByteArray(), qos = 1, packetId = 9)) }
        val client = MqttClient(scope, options()) { topic, payload -> messages += topic to payload.decodeToString(); got += Unit }
        withTimeout(10_000) { client.connect() }
        val connect = next<MqttPacket.Connect>()
        assertEquals("aare-test", connect.clientId)
        assertEquals("jo", connect.username)
        assertEquals("pw", connect.password)

        assertEquals(listOf(0), withTimeout(10_000) { client.subscribe(listOf("home/+/state" to 0)) })
        next<MqttPacket.Subscribe>()
        got.poll(5, TimeUnit.SECONDS)
        assertEquals(listOf("home/garage/state" to "open"), messages.toList())
        // The broker sent it at QoS 1: the client acknowledges it.
        assertEquals(9, next<MqttPacket.PubAck>().packetId)

        withTimeout(10_000) { client.publish("home/garage/set", "OPEN".encodeToByteArray(), qos = 1, retain = false) }
        val pub = next<MqttPacket.Publish>()
        assertEquals("home/garage/set", pub.topic)
        assertEquals("OPEN", pub.payload.decodeToString())
        assertEquals(1, pub.qos)

        client.publish("home/light/set", "ON".encodeToByteArray(), qos = 0, retain = true)
        assertTrue(next<MqttPacket.Publish>().retain)

        client.close()
        next<MqttPacket.Disconnect>()
        assertTrue(client.closed.isCompleted)
    }

    @Test
    fun keepsTheLinkAliveWithPings() = runBlocking {
        broker()
        val client = MqttClient(scope, options(keepAliveS = 1)) { _, _ -> }
        withTimeout(10_000) { client.connect() }
        next<MqttPacket.Connect>()
        // Every 0.75 s with a one-second keep-alive.
        next<MqttPacket.PingReq>()
        next<MqttPacket.PingReq>()
        assertTrue(!client.closed.isCompleted)
        client.close()
    }

    @Test
    fun aRefusalIsReportedWithItsCode() = runBlocking {
        broker(connAck = 4)
        val client = MqttClient(scope, options()) { _, _ -> }
        try {
            withTimeout(10_000) { client.connect() }
            fail("connected despite the refusal")
        } catch (e: MqttRefusedException) {
            assertEquals(4, e.code)
        }
        assertTrue(client.closed.isCompleted)
    }

    @Test
    fun theBrokerGoingAwayEndsTheConnection() = runBlocking {
        thread(isDaemon = true) {
            server.accept().use { s ->
                val input = BufferedInputStream(s.getInputStream())
                MqttCodec.read(input, 1 shl 20)
                s.getOutputStream().write(MqttCodec.encode(MqttPacket.ConnAck(false, 0)))
                s.getOutputStream().flush()
            } // and closes
        }
        val client = MqttClient(scope, options()) { _, _ -> }
        withTimeout(10_000) { client.connect() }
        val ended = runCatching { withTimeout(10_000) { client.closed.await() } }
        assertTrue("expected the drop to be an error, got $ended", ended.isFailure && ended.exceptionOrNull() !is kotlinx.coroutines.TimeoutCancellationException)
    }
}
