package net.prototux.aare.aap.pcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class PacedPcmQueueTest {
    private fun shorts(vararg s: Int): ByteArray =
        ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { s.forEach { putShort(it.toShort()) } }.array()

    private fun toShorts(b: ByteArray): List<Int> {
        val sb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return List(sb.remaining()) { sb.get(it).toInt() }
    }

    /** A fake clock: sleeping advances it. */
    private class Clock { var now = 0L; val sleeps = ArrayList<Long>() }

    private fun queue(clock: Clock, lead: Long = 0) = PacedPcmQueue(PcmFormat(16000, 1), lead, { clock.now }, { clock.sleeps += it; clock.now += it })

    @Test fun readsQueuedThenSilence() {
        val c = Clock()
        val q = queue(c)
        q.push(shorts(1, 2, 3))
        val buf = ByteArray(10)
        assertEquals(10, q.read(buf, 0, 10))
        assertEquals(listOf(1, 2, 3, 0, 0), toShorts(buf))
        assertEquals(0, q.pendingBytes)
    }

    @Test fun pacesReadsToRealTime() {
        val c = Clock()
        val q = queue(c)
        val chunk = PcmFormat(16000, 1).bytesFor(20) // 20 ms
        q.push(ByteArray(chunk * 3))
        val buf = ByteArray(chunk)
        q.read(buf, 0, chunk) // first read: due now, no sleep
        q.read(buf, 0, chunk) // second: 20 ms later
        q.read(buf, 0, chunk)
        assertEquals(listOf(20_000_000L, 20_000_000L), c.sleeps)
    }

    @Test fun leadLetsReadsRunAhead() {
        val c = Clock()
        val q = queue(c, lead = 40_000_000L)
        val chunk = PcmFormat(16000, 1).bytesFor(20)
        val buf = ByteArray(chunk)
        repeat(3) { q.read(buf, 0, chunk) } // 0, 20 and 40 ms due: all within the 40 ms lead
        assertTrue(c.sleeps.isEmpty())
        q.read(buf, 0, chunk) // 60 ms due: sleeps 20 ms
        assertEquals(listOf(20_000_000L), c.sleeps)
    }

    @Test fun stalledReaderRestartsClock() {
        val c = Clock()
        val q = queue(c)
        val chunk = PcmFormat(16000, 1).bytesFor(20)
        val buf = ByteArray(chunk)
        q.read(buf, 0, chunk)
        c.now += 5_000_000_000L // the reader stopped for 5 s
        q.read(buf, 0, chunk) // must not race to catch up, nor sleep
        q.read(buf, 0, chunk)
        assertEquals(listOf(20_000_000L), c.sleeps)
    }

    @Test fun keepsWholeFramesOnly() {
        val q = PacedPcmQueue(PcmFormat(16000, 2), 0, { 0L }, {})
        q.push(ByteArray(7)) // one 4-byte frame and 3 stray bytes
        assertEquals(4, q.pendingBytes)
    }

    @Test fun awaitDrainedWaitsForTheReader() {
        val q = PacedPcmQueue(PcmFormat(16000, 1), leadNanos = Long.MAX_VALUE / 4)
        q.push(ByteArray(640))
        assertFalse(q.awaitDrained(10_000_000L))
        val reader = thread { q.read(ByteArray(640), 0, 640) }
        assertTrue(q.awaitDrained(2_000_000_000L))
        reader.join()
    }

    @Test fun flushAndCloseEndWaiting() {
        val q = PacedPcmQueue(PcmFormat(16000, 1), 0, { 0L }, {})
        q.push(ByteArray(640))
        q.flush()
        assertTrue(q.awaitDrained(1_000_000L))
        q.close()
        assertEquals(-1, q.read(ByteArray(32), 0, 32))
    }

    @Test fun cancelStopsWaiting() {
        val q = PacedPcmQueue(PcmFormat(16000, 1))
        q.push(ByteArray(640))
        assertFalse(q.awaitDrained(5_000_000_000L) { true })
    }
}
