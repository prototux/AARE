package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoReportTest {
    @Test
    fun reportsComeSoonAfterTheCarGivesItsScreenThenRarely() {
        assertEquals(listOf(1_000L, 2_000L, 7_000L, 10_000L, 30_000L, 30_000L), (0..5).map(::videoReportDelayMs))
    }

    @Test
    fun bytesInHex() {
        assertEquals("67420a", hex(byteArrayOf(0, 0x67, 0x42, 0x0a), 1, 3))
    }
}
