package net.prototux.aare.aap

import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import net.prototux.aare.aap.cert.SelfSignedCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the phone tells a head unit whose framing breaks, with a hand-driven head unit. */
class FramingErrorTest {

    @Test
    fun aBrokenFrameGetsFramingErrorInTheClearBeforeTheSessionEnds() {
        val server = ServerSocket(0)
        val huSocket = Socket("127.0.0.1", server.localPort)
        val phoneSocket = server.accept()
        server.close()
        val closed = CountDownLatch(1)
        val id = SelfSignedCertificate.generate("phone")
        val phone = AapSession(
            SocketTransport(phoneSocket),
            PhoneIdentity(id.certificate, id.keyPair.private),
            PhoneDescription(deviceName = "Test phone", label = "AARE"),
            callbacks = object : AapSession.Callbacks {
                override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse) = emptyList<ServiceChannel>()
                override fun onClosed(session: AapSession, reason: String, error: Throwable?) = closed.countDown()
            },
        )
        try {
            phone.start()
            // A continuation frame (LAST only) with no first frame before it.
            huSocket.getOutputStream().apply { write(byteArrayOf(0, Frame.FLAG_LAST.toByte(), 0, 2, 0, 1)); flush() }
            val reply = FrameReader(huSocket.getInputStream()) { null }.read()
            assertEquals(0, reply.channel)
            assertFalse(reply.control)
            assertFalse(reply.encrypted)
            assertEquals(ControlMsg.FRAMING_ERROR, reply.id)
            assertEquals(0, reply.bodyLength)
            assertTrue("session should end", closed.await(5, TimeUnit.SECONDS))
        } finally {
            phone.stop()
            huSocket.close()
        }
    }
}
