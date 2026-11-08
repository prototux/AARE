package net.prototux.aare.cert

import net.prototux.aare.aap.cert.SelfSignedCertificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The checks on a downloaded certificate. Google's CA can't sign test certificates, so these use
 * self-signed ones (a self-signed certificate is its own CA) and pin their fingerprint instead.
 */
class CertificateDownloadTest {
    private val a = SelfSignedCertificate.generate("A")
    private val b = SelfSignedCertificate.generate("B")
    private val aCert = pem("CERTIFICATE", a.certificate.encoded)
    private val aKey = pem("PRIVATE KEY", a.keyPair.private.encoded)
    private val aPin = CertificateDownload.sha256(a.certificate.encoded)

    @Test fun acceptsPinnedCaAndMatchingKey() = CertificateDownload.check(aCert, aKey, aPin)

    @Test fun rejectsChainWithoutThePinnedCa() {
        assertThrows(IllegalStateException::class.java) { CertificateDownload.check(aCert, aKey) }
    }

    /** The pinned CA is in the chain, but it didn't sign the leaf. */
    @Test fun rejectsLeafNotSignedByThePinnedCa() {
        val chain = aCert + pem("CERTIFICATE", b.certificate.encoded)
        assertThrows(IllegalStateException::class.java) {
            CertificateDownload.check(chain, aKey, CertificateDownload.sha256(b.certificate.encoded))
        }
    }

    @Test fun rejectsKeyOfAnotherCertificate() {
        assertThrows(IllegalArgumentException::class.java) {
            CertificateDownload.check(aCert, pem("PRIVATE KEY", b.keyPair.private.encoded), aPin)
        }
    }

    @Test fun okIsNoError() = assertNull(CertificateDownload.statusError(200))

    /** Deleted, renamed or private (404) and DMCA takedowns (451) say the repository is gone. */
    @Test fun goneRepositoryIsUnavailable() {
        for (code in listOf(404, 410, 451)) assertTrue(CertificateDownload.statusError(code) is CertificateDownload.Unavailable)
    }

    /** Rate limits and server errors may pass: they're reported as a network error, not a takedown. */
    @Test fun otherErrorsAreNotUnavailable() {
        for (code in listOf(403, 429, 500, 503)) assertFalse(CertificateDownload.statusError(code) is CertificateDownload.Unavailable)
    }

    private fun pem(type: String, der: ByteArray) =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"
}
