package net.prototux.aare.aap

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkStatsTest {
    @Test
    fun idleLink() {
        assertEquals(
            "last in 1200 ms ago, last out 300 ms ago, slowest write 4 ms, 0 queued",
            AapSession.linkStats(nowMs = 10_000, lastInMs = 8_800, lastOutMs = 9_700, writingSinceMs = -1, slowestWriteMs = 4, queued = 0),
        )
    }

    @Test
    fun stuckWrite() {
        assertEquals(
            "last in 50 ms ago, last out 2500 ms ago, a write blocked for 2400 ms, slowest write 2400 ms, 37 queued",
            AapSession.linkStats(nowMs = 10_000, lastInMs = 9_950, lastOutMs = 7_500, writingSinceMs = 7_600, slowestWriteMs = 2_400, queued = 37),
        )
    }
}
