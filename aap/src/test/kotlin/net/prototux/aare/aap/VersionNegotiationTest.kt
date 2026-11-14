package net.prototux.aare.aap

import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.sim.HeadUnitSimulator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** The version the phone answers, and that gating keeps following the head unit's own request. */
class VersionNegotiationTest {

    /** Connects a head unit asking for [major].[minor] and returns the phone's version response body. */
    private fun negotiate(major: Int, minor: Int): ByteArray {
        val server = ServerSocket(0)
        val huSocket = Socket("127.0.0.1", server.localPort)
        val phoneSocket = server.accept()
        server.close()
        val hu = HeadUnitSimulator(SocketTransport(huSocket), protocolMinor = minor, protocolMajor = major)
        val id = SelfSignedCertificate.generate("phone")
        val phone = AapSession(
            SocketTransport(phoneSocket),
            PhoneIdentity(id.certificate, id.keyPair.private),
            PhoneDescription(deviceName = "Test phone", label = "AARE"),
            callbacks = object : AapSession.Callbacks {
                override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse) = emptyList<ServiceChannel>()
                override fun onClosed(session: AapSession, reason: String, error: Throwable?) {}
            },
        )
        try {
            phone.start()
            hu.start()
            assertTrue("no version response to $major.$minor", hu.versionAnswered.await(10, TimeUnit.SECONDS))
            assertEquals("gating must use the head unit's version", major to minor, phone.headUnitVersion)
            assertEquals(major > 1 || minor >= 6, phone.headUnitAtLeast(1, 6))
            return requireNotNull(hu.versionResponse)
        } finally {
            phone.stop()
            hu.stop()
        }
    }

    private fun reply(major: Int, minor: Int) = byteArrayOf(0, major.toByte(), 0, minor.toByte(), 0, 0)

    @Test
    fun answers17UpTo17And62Above() {
        // 1.7 to head units up to 1.7, 6.2 to anything newer, always success.
        val expected = mapOf(
            (1 to 5) to reply(1, 7),
            (1 to 7) to reply(1, 7),
            (1 to 8) to reply(6, 2),
            (6 to 0) to reply(6, 2),
            (6 to 1) to reply(6, 2),
            (6 to 2) to reply(6, 2),
            (7 to 0) to reply(6, 2),
        )
        for ((asked, answer) in expected) {
            assertArrayEquals("reply to ${asked.first}.${asked.second}", answer, negotiate(asked.first, asked.second))
        }
    }
}
