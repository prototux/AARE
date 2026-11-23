package net.prototux.aare.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReplaceTest {
    @Test
    fun aNewUsbLinkReplacesAUsbRetryThatNeverReachedTheCar() {
        assertTrue(replacesSession(newWireless = false, oldWireless = false, oldReachedHeadUnit = false, oldAgeMs = 4_000))
    }

    @Test
    fun aRunningUsbSessionStays() {
        assertFalse(replacesSession(newWireless = false, oldWireless = false, oldReachedHeadUnit = true, oldAgeMs = 4_000))
    }

    @Test
    fun theSecondAnnouncementOfAReturningHeadUnitDoesNotReplaceTheFirst() {
        assertFalse(replacesSession(newWireless = false, oldWireless = false, oldReachedHeadUnit = false, oldAgeMs = 100))
    }

    @Test
    fun wirelessNeverReplacesOrIsReplaced() {
        assertFalse(replacesSession(newWireless = true, oldWireless = false, oldReachedHeadUnit = false, oldAgeMs = 4_000))
        assertFalse(replacesSession(newWireless = false, oldWireless = true, oldReachedHeadUnit = false, oldAgeMs = 4_000))
    }
}
