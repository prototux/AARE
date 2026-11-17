package net.prototux.aare.host

import org.junit.Assert.assertEquals
import org.junit.Test

class RetriesAfterCrashTest {
    @Test
    fun aCrashRightAfterStartKeepsCounting() {
        assertEquals(2, retriesAfterCrash(2, 3_000))
    }

    @Test
    fun aCrashAfterAMinuteUpStartsAfresh() {
        assertEquals(0, retriesAfterCrash(3, 60_000))
    }
}
