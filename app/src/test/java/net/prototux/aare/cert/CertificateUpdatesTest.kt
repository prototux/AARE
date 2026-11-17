package net.prototux.aare.cert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class CertificateUpdatesTest {
    private val now = Date(1_800_000_000_000L)
    private fun inDays(days: Double) = Date(now.time + (days * 24 * 60 * 60 * 1000).toLong())

    @Test
    fun noGoogleCertificateNoWarning() = assertNull(CertificateUpdates.expiryWarning(emptyMap(), now))

    @Test
    fun aCertificateValidForLongerThanTwoWeeksIsNoWarning() =
        assertNull(CertificateUpdates.expiryWarning(mapOf("Android Auto 17.7" to inDays(15.0)), now))

    @Test
    fun twoWeeksBeforeTheExpiryItWarns() {
        val w = CertificateUpdates.expiryWarning(mapOf("Android Auto 17.7" to inDays(13.5)), now)!!
        assertEquals("Android Auto 17.7", w.name)
        assertFalse(w.expired)
    }

    @Test
    fun anExpiredCertificateIsReportedAsExpired() =
        assertTrue(CertificateUpdates.expiryWarning(mapOf("Android Auto 16.2" to inDays(-1.0)), now)!!.expired)

    @Test
    fun theLongestLastingCertificateDecides() {
        // An old download kept around doesn't warn while a newer one is there.
        assertNull(CertificateUpdates.expiryWarning(mapOf("old" to inDays(-30.0), "new" to inDays(90.0)), now))
        assertEquals("new", CertificateUpdates.expiryWarning(mapOf("old" to inDays(-30.0), "new" to inDays(3.0)), now)!!.name)
    }

    @Test
    fun notifiedOnceBeforeAndOnceAfterTheExpiry() {
        val before = CertificateUpdates.expiryWarning(mapOf("c" to inDays(2.0)), now)!!
        val after = CertificateUpdates.expiryWarning(mapOf("c" to inDays(2.0)), inDays(3.0))!!
        assertFalse(before.key == after.key)
    }

    @Test
    fun onlyALongerLastingDownloadReplacesTheCertificate() {
        assertTrue(CertificateUpdates.replaces(inDays(90.0), null))
        assertTrue(CertificateUpdates.replaces(inDays(90.0), inDays(10.0)))
        assertFalse(CertificateUpdates.replaces(inDays(10.0), inDays(10.0)))
        assertFalse(CertificateUpdates.replaces(inDays(5.0), inDays(10.0)))
    }
}
