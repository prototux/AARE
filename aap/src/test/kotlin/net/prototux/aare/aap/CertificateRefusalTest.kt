package net.prototux.aare.aap

import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.sim.HeadUnitSimulator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/** How the phone reads the ways a head unit can turn down its certificate. */
class CertificateRefusalTest {
    private lateinit var hu: HeadUnitSimulator
    private lateinit var phone: AapSession
    private val running = CountDownLatch(1)
    private val closed = CountDownLatch(1)

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
            PhoneDescription(deviceName = "Test phone", label = "AARE", instanceId = "test-1"),
            callbacks = object : AapSession.Callbacks {
                override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse) = emptyList<ServiceChannel>()
                override fun onRunning(session: AapSession) = running.countDown()
                override fun onClosed(session: AapSession, reason: String, error: Throwable?) = closed.countDown()
            },
        )
        phone.start()
        hu.start()
    }

    @After
    fun tearDown() { if (::hu.isInitialized) hu.stop() }

    @Test
    fun aFailedAuthCompleteIsAnExplicitRefusalWithItsStatus() {
        connect { authStatus = MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED_VALUE }
        assertTrue("session should end", closed.await(10, TimeUnit.SECONDS))
        val refusal = requireNotNull(phone.certificateRefusal) { "no refusal recorded" }
        assertTrue(refusal.explicit)
        assertTrue(refusal.expired)
        assertFalse(refusal.notYetValid)
    }

    @Test
    fun aClockBehindIsReportedAsNotYetValid() {
        connect { authStatus = MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_NOT_YET_VALID_VALUE }
        assertTrue(closed.await(10, TimeUnit.SECONDS))
        assertTrue(phone.certificateRefusal!!.notYetValid)
    }

    @Test
    fun aLinkLostMidHandshakeIsNotAnExplicitRefusal() {
        connect { hangUpInHandshake = true }
        assertTrue("session should end", closed.await(10, TimeUnit.SECONDS))
        val refusal = requireNotNull(phone.certificateRefusal) { "no refusal recorded" }
        assertFalse(refusal.explicit)
        assertEquals(0, refusal.authStatus)
    }

    @Test
    fun anAcceptedCertificateIsNoRefusal() {
        connect { }
        assertTrue("session should run", running.await(10, TimeUnit.SECONDS))
        assertNull(phone.certificateRefusal)
    }

    @Test
    fun aTlsAlertOrCloseCountsAsTheHeadUnitsAnswer() {
        assertTrue(CertificateRefusal.saidByHeadUnit(AapException("read failed", SSLHandshakeException("Received fatal alert: bad_certificate"))))
        assertTrue(CertificateRefusal.saidByHeadUnit(AapException("TLS closed during handshake")))
        assertFalse(CertificateRefusal.saidByHeadUnit(java.io.IOException("read failed: EIO (I/O error)")))
        assertFalse(CertificateRefusal.saidByHeadUnit(null))
    }
}
