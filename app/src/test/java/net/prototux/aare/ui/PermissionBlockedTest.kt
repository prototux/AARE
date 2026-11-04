package net.prototux.aare.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionBlockedTest {
    @Test
    fun aDismissedFirstDialogIsNotARefusal() {
        // Backed out of the very first request: no rationale before or after, never asked before.
        assertFalse(permissionBlocked(rationaleBefore = false, rationaleAfter = false, askedBefore = false))
    }

    @Test
    fun aFirstRefusalCanStillBeAskedAgain() {
        assertFalse(permissionBlocked(rationaleBefore = false, rationaleAfter = true, askedBefore = false))
        assertFalse(permissionBlocked(rationaleBefore = true, rationaleAfter = true, askedBefore = true))
    }

    @Test
    fun aSecondRefusalBlocks() {
        assertTrue(permissionBlocked(rationaleBefore = true, rationaleAfter = false, askedBefore = true))
    }

    @Test
    fun aPermissionBlockedInAnEarlierRequestStaysBlocked() {
        assertTrue(permissionBlocked(rationaleBefore = false, rationaleAfter = false, askedBefore = true))
    }
}
