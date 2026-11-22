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

class UpdateDueTest {
    private val hour = 60 * 60 * 1000L

    @Test fun notDueRightAfterACheck() = assertFalse(UpdateChecker.due(nowMs = 10 * hour, lastCheckMs = 9 * hour))

    /** Every 6 hours: a release published in the morning shows the same day. */
    @Test fun dueSixHoursLater() = assertTrue(UpdateChecker.due(nowMs = 16 * hour, lastCheckMs = 10 * hour))

    @Test fun dueWhenNeverChecked() = assertTrue(UpdateChecker.due(nowMs = 10 * hour, lastCheckMs = 0))

    /** A clock set back would otherwise stop the checks until it caught up. */
    @Test fun dueWhenTheClockWentBack() = assertTrue(UpdateChecker.due(nowMs = 10 * hour, lastCheckMs = 12 * hour))
}
