package net.prototux.aare.mirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsksForAccessibilityTest {
    @Test
    fun withAHelperTheServiceIsNotAskedFor() {
        // Settings says it isn't needed then: the paused screen mustn't ask for it.
        assertFalse(asksForAccessibility(helper = true, service = false))
    }

    @Test
    fun withoutAHelperOnlyAMissingServiceIsAskedFor() {
        assertTrue(asksForAccessibility(helper = false, service = false))
        assertFalse(asksForAccessibility(helper = false, service = true))
    }
}
