package net.prototux.aare.aap

import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.sim.HeadUnitSimulator
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The phone's own pings, against head units that answer them or not. */
class PingTest {
    private lateinit var hu: HeadUnitSimulator
    private lateinit var phone: AapSession
    private val running = CountDownLatch(1)

    private fun connect(configure: HeadUnitSimulator.() -> Unit) {
        val server = ServerSocket(0)
        val huSocket = Socket("127.0.0.1", server.localPort)
        val phoneSocket = server.accept()
        server.close()
        hu = HeadUnitSimulator(SocketTransport(huSocket)).apply(configure)
        val id = SelfSignedCertificate.generate("phone")
        phone = AapSession(
            SocketTransport(phoneSocket),
            PhoneIdentity(id.certificate, id.keyPair.private),
            PhoneDescription(deviceName = "Test phone", label = "AARE"),
            callbacks = object : AapSession.Callbacks {
                override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse) = emptyList<ServiceChannel>()
                override fun onRunning(session: AapSession) = running.countDown()
                override fun onClosed(session: AapSession, reason: String, error: Throwable?) {}
            },
        )
        phone.start()
        hu.start()
        assertTrue(running.await(10, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() { if (::hu.isInitialized) hu.stop() }

    @Test
    fun pingsKeepGoingToAHeadUnitThatNeverAnswers() {
        connect { answerPings = false; pingIntervalMs = 250 }
        // More than the in-flight cap: it only applies once the head unit has answered one.
        for (i in 1..12) assertTrue("ping $i", hu.pingRequests.poll(3, TimeUnit.SECONDS) != null)
    }

    @Test
    fun pingsFollowTheHeadUnitsInterval() {
        connect { pingIntervalMs = 250 }
        hu.pingRequests.poll(3, TimeUnit.SECONDS)!!
        val start = System.nanoTime()
        for (i in 1..4) hu.pingRequests.poll(3, TimeUnit.SECONDS)!!
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("4 pings took $ms ms", ms < 2_500)
        assertTrue(hu.pingsAnswered.get() >= 5)
    }
}
