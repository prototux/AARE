package net.prototux.aare.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextRestrictionTest {
    @Test
    fun blocksTheListedCountryRegardlessOfCase() {
        assertTrue(isMessageTextBlocked("hk"))
        assertTrue(isMessageTextBlocked("HK"))
    }

    @Test
    fun doesNotBlockOtherOrUnknownCountries() {
        assertFalse(isMessageTextBlocked("us"))
        assertFalse(isMessageTextBlocked(null))
        assertFalse(isMessageTextBlocked(""))
    }
}
