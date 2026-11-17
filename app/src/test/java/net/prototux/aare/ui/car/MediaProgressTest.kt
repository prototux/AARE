package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaProgressTest {
    @Test
    fun pausedStaysAtTheReportedPosition() {
        assertEquals(0.25f, mediaProgress(60_000, 240_000, false, 1_000, 31_000), 0.0001f)
    }

    @Test
    fun playingAdvancesWithTheTimeSinceTheSample() {
        assertEquals(0.375f, mediaProgress(60_000, 240_000, true, 1_000, 31_000), 0.0001f)
    }

    @Test
    fun stopsAtTheEndOfTheTrack() {
        assertEquals(1f, mediaProgress(230_000, 240_000, true, 0, 60_000), 0.0001f)
    }

    @Test
    fun unknownLengthGivesZero() {
        assertEquals(0f, mediaProgress(5_000, 0, true, 0, 1_000), 0.0001f)
    }

    @Test
    fun sampleLaterThanNowStaysAtTheReportedPosition() {
        assertEquals(0.25f, mediaProgress(60_000, 240_000, true, 10_000, 5_000), 0.0001f)
    }
}
