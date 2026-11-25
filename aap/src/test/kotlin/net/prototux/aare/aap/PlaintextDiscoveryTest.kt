package net.prototux.aare.aap

import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.sim.HeadUnitSimulator
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A head unit that sends its service discovery response in the clear after TLS (#418, a Sony XAV). */
class PlaintextDiscoveryTest {
    private var hu: HeadUnitSimulator? = null

    @After
    fun tearDown() { hu?.stop() }

    @Test
    fun aSessionStartsWhenTheDiscoveryResponseComesInTheClear() {
        val server = ServerSocket(0)
        val huSocket = Socket("127.0.0.1", server.localPort)
        val phoneSocket = server.accept()
        server.close()
        val running = CountDownLatch(1)
        hu = HeadUnitSimulator(SocketTransport(huSocket), protocolMinor = 5, plaintextDiscovery = true)
        val id = SelfSignedCertificate.generate("phone")
        AapSession(
            SocketTransport(phoneSocket),
            PhoneIdentity(id.certificate, id.keyPair.private),
            PhoneDescription(deviceName = "Test phone", label = "AARE"),
            callbacks = object : AapSession.Callbacks {
                override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse) = emptyList<ServiceChannel>()
                override fun onRunning(session: AapSession) = running.countDown()
                override fun onClosed(session: AapSession, reason: String, error: Throwable?) {}
            },
        ).start()
        hu!!.start()
        assertTrue("session should reach RUNNING", running.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun onlyTheAwaitedDiscoveryResponseIsTakenInTheClear() {
        fun msg(id: Int, channel: Int = 0, control: Boolean = false) = AapMessage(channel, control, encrypted = false, id = id, body = ByteArray(0))
        val discovery = ControlMsg.SERVICE_DISCOVERY_RESPONSE
        assertTrue(acceptsPlaintextDiscovery(msg(discovery), AapSession.State.DISCOVERY))
        // Once it's in, a second one isn't.
        assertFalse(acceptsPlaintextDiscovery(msg(discovery), AapSession.State.OPENING))
        assertFalse(acceptsPlaintextDiscovery(msg(discovery), AapSession.State.RUNNING))
        // Nothing else, and not on a service channel.
        assertFalse(acceptsPlaintextDiscovery(msg(ControlMsg.PING_REQUEST), AapSession.State.DISCOVERY))
        assertFalse(acceptsPlaintextDiscovery(msg(ControlMsg.SERVICE_DISCOVERY_UPDATE), AapSession.State.DISCOVERY))
        assertFalse(acceptsPlaintextDiscovery(msg(discovery, channel = 3), AapSession.State.DISCOVERY))
        assertFalse(acceptsPlaintextDiscovery(msg(discovery, control = true), AapSession.State.DISCOVERY))
    }
}
