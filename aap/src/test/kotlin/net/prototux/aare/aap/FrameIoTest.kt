package net.prototux.aare.aap

import net.prototux.aare.aap.cert.SelfSignedCertificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class FrameIoTest {

    @Test
    fun plainSingleFrameRoundTrip() {
        val out = ByteArrayOutputStream()
        FrameWriter(out) { null }.write(0, false, false, AapMessage.plaintext(0x0001, byteArrayOf(0, 1, 0, 7)))
        // ch 0, FIRST|LAST, size 6, id 0x0001, body
        assertArrayEquals(byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 7), out.toByteArray())
        val msg = FrameReader(ByteArrayInputStream(out.toByteArray())) { null }.read()
        assertEquals(0, msg.channel)
        assertEquals(1, msg.id)
        assertArrayEquals(byteArrayOf(0, 1, 0, 7), msg.bodyBytes())
    }

    @Test
    fun fragmentedMessageCarriesTotalSizeAndReassembles() {
        val body = Random(1).nextBytes(Frame.MAX_PAYLOAD * 2 + 100)
        val out = ByteArrayOutputStream()
        FrameWriter(out) { null }.write(3, false, false, AapMessage.plaintext(0x0000, body))
        val bytes = out.toByteArray()
        assertEquals(Frame.FLAG_FIRST, bytes[1].toInt())
        val total = ((bytes[4].toInt() and 0xff) shl 24) or ((bytes[5].toInt() and 0xff) shl 16) or
            ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        assertEquals(body.size + 2, total)
        val msg = FrameReader(ByteArrayInputStream(bytes)) { null }.read()
        assertEquals(3, msg.channel)
        assertArrayEquals(body, msg.bodyBytes())
    }

    @Test
    fun interleavedChannelsReassembleSeparately() {
        // Hand-built: ch1 FIRST, ch2 BULK, ch1 LAST
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(1, 1, 0, 3, 0, 0, 0, 5, 0, 9, 1))
        out.write(byteArrayOf(2, 3, 0, 3, 0, 4, 2))
        out.write(byteArrayOf(1, 2, 0, 2, 3, 4))
        val reader = FrameReader(ByteArrayInputStream(out.toByteArray())) { null }
        val a = reader.read()
        assertEquals(2, a.channel); assertEquals(4, a.id); assertArrayEquals(byteArrayOf(2), a.bodyBytes())
        val b = reader.read()
        assertEquals(1, b.channel); assertEquals(9, b.id); assertArrayEquals(byteArrayOf(1, 3, 4), b.bodyBytes())
    }

    @Test
    fun tlsHandshakeAndEncryptedFrames() {
        val phoneCert = SelfSignedCertificate.generate("phone")
        val huCert = SelfSignedCertificate.generate("head unit")
        val phone = Cryptor(phoneCert.certificate, phoneCert.keyPair.private)
        val hu = Cryptor(huCert.certificate, huCert.keyPair.private, client = true)
        phone.beginHandshake(); hu.beginHandshake()

        var toPhone = hu.handshake(ByteArray(0))
        var rounds = 0
        while (!(phone.handshakeDone && hu.handshakeDone)) {
            val toHu = phone.handshake(toPhone)
            toPhone = hu.handshake(toHu)
            assertTrue("handshake did not converge", ++rounds < 10)
        }
        assertEquals("TLSv1.2", phone.protocol)
        assertEquals("head unit", hu.peerCertificates.let { phone.peerCertificates.first().subjectX500Principal.name.removePrefix("CN=") })

        val body = Random(2).nextBytes(40_000)
        val wire = ByteArrayOutputStream()
        FrameWriter(wire) { phone }.write(4, false, true, AapMessage.plaintext(0x8001, body))
        val msg = FrameReader(ByteArrayInputStream(wire.toByteArray())) { hu }.read()
        assertTrue(msg.encrypted)
        assertEquals(0x8001, msg.id)
        assertArrayEquals(body, msg.bodyBytes())
    }

    @Test
    fun encryptedMessageBeforeTlsIsSkipped() {
        val out = ByteArrayOutputStream()
        // ch 3 ENCRYPTED|FIRST|LAST with junk, then ch 3 fragmented: ENCRYPTED|FIRST, plain LAST,
        // then a plain message that must come through.
        out.write(byteArrayOf(3, 0x0b, 0, 3, 1, 2, 3))
        out.write(byteArrayOf(3, 0x09, 0, 2, 0, 0, 0, 4, 9, 9))
        out.write(byteArrayOf(3, 0x02, 0, 2, 7, 7))
        out.write(byteArrayOf(3, 0x03, 0, 3, 0, 5, 1))
        val msg = FrameReader(ByteArrayInputStream(out.toByteArray())) { null }.read()
        assertEquals(3, msg.channel)
        assertEquals(5, msg.id)
        assertArrayEquals(byteArrayOf(1), msg.bodyBytes())
    }

    @Test
    fun continuationWithoutFirstFrameIsAFramingError() {
        val reader = FrameReader(ByteArrayInputStream(byteArrayOf(0, 0x02, 0, 2, 0, 1))) { null }
        assertThrows(FramingException::class.java) { reader.read() }
    }
}
