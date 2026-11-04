package net.prototux.aare.headunit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class Pkcs8KeyDerTest {
    private val der = ByteArray(300) { it.toByte() }
    private val key = "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END PRIVATE KEY-----\n"

    @Test
    fun readsAPlainKey() = assertArrayEquals(der, pkcs8KeyDer(key))

    @Test
    fun skipsTheCertificateAndBagAttributesAroundIt() {
        val bundle = "Bag Attributes\n    localKeyID: 01 02 03\nsubject=CN = AARE\n" +
            "-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIU\n-----END CERTIFICATE-----\n" +
            "Bag Attributes\n    friendlyName: hu\n" + key
        assertArrayEquals(der, pkcs8KeyDer(bundle))
    }

    @Test
    fun refusesAPkcs1Key() {
        assertThrows(IllegalArgumentException::class.java) {
            pkcs8KeyDer("-----BEGIN RSA PRIVATE KEY-----\nMIIEow==\n-----END RSA PRIVATE KEY-----\n")
        }
    }
}
