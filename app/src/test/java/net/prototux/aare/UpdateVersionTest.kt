package net.prototux.aare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test fun higherVersionIsNewer() = assertTrue(UpdateChecker.isNewer("1.3.0", "1.2.0"))
    @Test fun sameVersionIsNotNewer() = assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.0"))
    @Test fun olderVersionIsNotNewer() = assertFalse(UpdateChecker.isNewer("1.2.0", "1.3.0"))

    /** Numeric, not lexical: 10 > 9. */
    @Test fun minorIsComparedNumerically() = assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))

    /** A leading "v" on the tag is ignored. */
    @Test fun leadingVIsIgnored() = assertTrue(UpdateChecker.isNewer("v1.2.1", "1.2.0"))

    /** A missing component counts as 0. */
    @Test fun missingPartCountsAsZero() {
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.0"))
        assertTrue(UpdateChecker.isNewer("1.2.1", "1.2"))
    }

    @Test fun majorBumpBeatsEverything() = assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
}
