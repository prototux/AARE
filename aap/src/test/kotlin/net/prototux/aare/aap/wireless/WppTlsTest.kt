package net.prototux.aare.aap.wireless

import net.prototux.aare.aap.MemoryPinStore
import net.prototux.aare.aap.PhoneIdentity
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.fingerprint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** The phone's TLS server role on the WPP-over-TCP socket, against a TLS client playing the head unit. */
class WppTlsTest {
    private val phoneId = SelfSignedCertificate.generate("phone").let { PhoneIdentity(it.certificate, it.keyPair.private) }
    private val huId = SelfSignedCertificate.generate("head unit")

    /** The head unit's side: TLS client with its own certificate, trusting any phone. */
    private fun huTls(socket: Socket): SSLSocket {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setKeyEntry("hu", huId.keyPair.private, CharArray(0), arrayOf(huId.certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAll), SecureRandom()) }
        return (ctx.socketFactory.createSocket(socket, "127.0.0.1", socket.port, true) as SSLSocket).apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.2")
        }
    }

    /** Connects the phone (TCP client, TLS server) to a head unit (TCP server, TLS client); returns both ends. */
    private fun connect(serve: (Socket) -> SSLSocket): Pair<SSLSocket, SSLSocket> {
        val server = ServerSocket(0)
        val phoneSocket = Socket("127.0.0.1", server.localPort)
        val huSocket = server.accept()
        server.close()
        val hu = CompletableFuture<SSLSocket>()
        thread(name = "hu-tls") {
            try {
                hu.complete(huTls(huSocket).also { it.startHandshake() })
            } catch (e: Exception) {
                hu.completeExceptionally(e)
                runCatching { huSocket.close() }
            }
        }
        val phone = try {
            serve(phoneSocket)
        } catch (e: Exception) {
            runCatching { huSocket.close() }
            throw e
        }
        return phone to hu.get(5, TimeUnit.SECONDS)
    }

    @Test(timeout = 10_000)
    fun wppFramesGoThroughTlsWithATrustedHeadUnit() {
        val pins = MemoryPinStore().apply { add("wpp:AA:BB", fingerprint(huId.certificate)) }
        val (phone, hu) = connect { WppTls.serve(it, phoneId, pins, "wpp:AA:BB") }
        try {
            assertEquals("TLSv1.2", phone.session.protocol)
            assertEquals(phoneId.certificate, hu.session.peerCertificates.first())
            writeWppFrame(hu.outputStream, WppMessageId.VERSION_REQUEST, byteArrayOf(8, 4, 16, 5))
            val frame = readWppFrame(phone.inputStream)!!
            assertEquals(WppMessageId.VERSION_REQUEST, frame.id)
            assertArrayEquals(byteArrayOf(8, 4, 16, 5), frame.payload)
            assertEquals(setOf(fingerprint(huId.certificate)), pins.get("wpp:AA:BB"))
        } finally {
            phone.close()
            hu.close()
        }
    }

    @Test(timeout = 10_000)
    fun aNewHeadUnitIsNotTrustedOnFirstUseButOfferedForTrust() {
        val pins = MemoryPinStore()
        val e = runCatching { connect { WppTls.serve(it, phoneId, pins, "wpp:AA:BB") } }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is WppCertificateRefusedException)
        val refusal = (e as WppCertificateRefusedException).refusal
        assertEquals("wpp:AA:BB", refusal.peerKey)
        assertEquals(fingerprint(huId.certificate), refusal.fingerprint)
        assertFalse("a new head unit, not a changed certificate", refusal.changed)
        assertTrue("nothing trusted before the user says so", pins.get("wpp:AA:BB").isEmpty())
    }

    @Test(timeout = 10_000)
    fun aChangedHeadUnitCertificateFailsTheHandshake() {
        val pins = MemoryPinStore().apply { add("wpp:AA:BB", "00".repeat(32)) }
        val e = runCatching { connect { WppTls.serve(it, phoneId, pins, "wpp:AA:BB") } }.exceptionOrNull()
        assertTrue("expected an IOException, got $e", e is IOException)
        assertTrue("changed", (e as WppCertificateRefusedException).refusal.changed)
        assertEquals("nothing trusted on a refusal", setOf("00".repeat(32)), pins.get("wpp:AA:BB"))
    }
}
