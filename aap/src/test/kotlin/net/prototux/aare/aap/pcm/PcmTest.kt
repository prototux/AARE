package net.prototux.aare.aap.pcm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class PcmTest {
    private fun shorts(vararg s: Int): ByteArray =
        ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { s.forEach { putShort(it.toShort()) } }.array()

    private fun toShorts(b: ByteArray): List<Int> {
        val sb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return List(sb.remaining()) { sb.get(it).toInt() }
    }

    private fun wav(rate: Int, channels: Int, pcm: ByteArray, dataLen: Int = pcm.size, extraChunk: Boolean = false, bits: Int = 16, encoding: Int = 1): ByteArray {
        val b = ByteBuffer.allocate(64 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(0).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(encoding.toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(bits.toShort())
        if (extraChunk) b.put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0)) // odd length + pad byte
        b.put("data".toByteArray()).putInt(dataLen).put(pcm)
        return b.array().copyOf(b.position())
    }

    @Test fun parsesPlainWav() {
        val pcm = shorts(1, -2, 3, -4)
        val w = Wav.parse(wav(22050, 1, pcm))
        assertNotNull(w)
        assertEquals(PcmFormat(22050, 1), w!!.format)
        assertArrayEquals(pcm, w.pcm)
    }

    @Test fun skipsOtherChunksIncludingPadding() {
        val pcm = shorts(7, 8)
        val w = Wav.parse(wav(16000, 2, pcm, extraChunk = true))
        assertEquals(PcmFormat(16000, 2), w!!.format)
        assertArrayEquals(pcm, w.pcm)
    }

    @Test fun unfinishedHeaderReadsToEnd() {
        // An engine stopped mid-way leaves the data length at 0.
        val pcm = shorts(5, 6, 7)
        assertArrayEquals(pcm, Wav.parse(wav(24000, 1, pcm, dataLen = 0))!!.pcm)
    }

    @Test fun rejectsOtherEncodings() {
        assertNull(Wav.parse(wav(16000, 1, shorts(1), bits = 8)))
        assertNull(Wav.parse(wav(16000, 1, shorts(1), encoding = 3))) // float
        assertNull(Wav.parse("not a wav file at all".toByteArray()))
    }

    @Test fun stereoToMonoAverages() {
        val out = PcmConvert.convert(shorts(100, 200, -100, -300), PcmFormat(16000, 2), PcmFormat(16000, 1))
        assertEquals(listOf(150, -200), toShorts(out))
    }

    @Test fun monoToStereoDuplicates() {
        val out = PcmConvert.convert(shorts(10, -20), PcmFormat(16000, 1), PcmFormat(16000, 2))
        assertEquals(listOf(10, 10, -20, -20), toShorts(out))
    }

    @Test fun resamplesToTheRightLength() {
        val oneSecond = ByteArray(24000 * 2)
        val out = PcmConvert.convert(oneSecond, PcmFormat(24000, 1), PcmFormat(16000, 1))
        assertEquals(16000 * 2, out.size)
        val up = PcmConvert.convert(ByteArray(16000 * 2), PcmFormat(16000, 1), PcmFormat(48000, 2))
        assertEquals(48000 * 4, up.size)
    }

    @Test fun resamplingInterpolates() {
        // Doubling the rate puts the midpoint between neighbours.
        val out = toShorts(PcmConvert.convert(shorts(0, 100, 200), PcmFormat(8000, 1), PcmFormat(16000, 1)))
        assertEquals(listOf(0, 50, 100, 150, 200, 200), out)
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
