package net.prototux.aare.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class TapRingTest {

    private fun packStereo(frames: Int, leftFn: (Int) -> Short, rightFn: (Int) -> Short): ByteArray {
        val buf = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val l = leftFn(i).toInt()
            val r = rightFn(i).toInt()
            val base = i * 4
            buf[base] = (l and 0xff).toByte()
            buf[base + 1] = ((l ushr 8) and 0xff).toByte()
            buf[base + 2] = (r and 0xff).toByte()
            buf[base + 3] = ((r ushr 8) and 0xff).toByte()
        }
        return buf
    }

    private fun packMono(frames: Int, sampleFn: (Int) -> Short): ByteArray {
        val buf = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val s = sampleFn(i).toInt()
            val base = i * 2
            buf[base] = (s and 0xff).toByte()
            buf[base + 1] = ((s ushr 8) and 0xff).toByte()
        }
        return buf
    }

    @Test fun leftOnlyAndRightOnlyChannelIsolation() {
        val rate = 1000
        val ring = TapRing(sampleRate = rate, channels = 2, capacityMs = 2000)
        // Left-only: left = 16384 (+0.5), right = 0
        val leftOnly = packStereo(100, { 16384 }, { 0 })
        ring.write(leftOnly, 0, leftOnly.size, nowMs = 1000L)

        val dstL = FloatArray(100)
        val dstR = FloatArray(100)
        val dstM = FloatArray(100)
        assertTrue(ring.read(nowMs = 1100L, delayMs = 0, mix = Mix.LEFT, dst = dstL))
        assertTrue(ring.read(nowMs = 1100L, delayMs = 0, mix = Mix.RIGHT, dst = dstR))
        assertTrue(ring.read(nowMs = 1100L, delayMs = 0, mix = Mix.MONO, dst = dstM))

        for (i in 0 until 100) {
            assertEquals(0.5f, dstL[i], 1e-5f)
            assertEquals(0f, dstR[i], 0f)
            assertEquals(0.25f, dstM[i], 1e-5f)
        }

        // Right-only: left = 0, right = -16384 (-0.5)
        ring.clear()
        val rightOnly = packStereo(100, { 0 }, { -16384 })
        ring.write(rightOnly, 0, rightOnly.size, nowMs = 2000L)

        assertTrue(ring.read(nowMs = 2100L, delayMs = 0, mix = Mix.LEFT, dst = dstL))
        assertTrue(ring.read(nowMs = 2100L, delayMs = 0, mix = Mix.RIGHT, dst = dstR))
        assertTrue(ring.read(nowMs = 2100L, delayMs = 0, mix = Mix.MONO, dst = dstM))

        for (i in 0 until 100) {
            assertEquals(0f, dstL[i], 0f)
            assertEquals(-0.5f, dstR[i], 1e-5f)
            assertEquals(-0.25f, dstM[i], 1e-5f)
        }
    }

    @Test fun fullScaleStereoMonoMixPeaksAtOneNotTwo() {
        val rate = 1000
        val ring = TapRing(sampleRate = rate, channels = 2, capacityMs = 2000)
        val fullNeg = packStereo(64, { Short.MIN_VALUE }, { Short.MIN_VALUE })
        ring.write(fullNeg, 0, fullNeg.size, nowMs = 1000L)

        val dst = FloatArray(64)
        assertTrue(ring.read(nowMs = 1064L, delayMs = 0, mix = Mix.MONO, dst = dst))
        for (v in dst) {
            assertEquals(-1.0f, v, 1e-5f)
        }

        ring.clear()
        val fullPos = packStereo(64, { Short.MAX_VALUE }, { Short.MAX_VALUE })
        ring.write(fullPos, 0, fullPos.size, nowMs = 2000L)
        assertTrue(ring.read(nowMs = 2064L, delayMs = 0, mix = Mix.MONO, dst = dst))
        for (v in dst) {
            assertEquals(32767f / 32768f, v, 1e-5f)
        }
    }

    @Test fun delayReturnsWindowEndingDelayMsBeforePlayhead() {
        val rate = 1000 // 1 frame per ms
        val ring = TapRing(sampleRate = rate, channels = 1, capacityMs = 2000, targetLeadMs = 0)
        // Write 500 frames in real time (10 chunks of 50 ms) with sample value = frame index
        for (c in 0 until 10) {
            val base = c * 50
            val chunk = packMono(50) { i -> (base + i).toShort() }
            ring.write(chunk, 0, chunk.size, nowMs = 1000L + c * 50L)
        }
        // At nowMs = 1500, playhead = 500.
        // With delayMs = 100, window ends at frame 400. A 64-frame window covers frames 336..399.
        val dst = FloatArray(64)
        assertTrue(ring.read(nowMs = 1500L, delayMs = 100, mix = Mix.MONO, dst = dst))
        for (i in 0 until 64) {
            assertEquals((336 + i) / 32768f, dst[i], 1e-6f)
        }
    }

    @Test fun burstWrittenAtOnceAdvancesPlayheadSmoothlyOverTime() {
        val rate = 1000 // 1 frame per ms
        val ring = TapRing(sampleRate = rate, channels = 1, capacityMs = 2000)
        // Write 200 ms (4 chunks of 50 frames) all at the exact same nowMs = 5000L
        for (c in 0 until 4) {
            val base = c * 50
            val chunk = packMono(50) { i -> (base + i).toShort() }
            ring.write(chunk, 0, chunk.size, nowMs = 5000L)
        }
        assertEquals(200L, ring.leadMs(5000L))
        assertEquals(150L, ring.leadMs(5050L))
        assertEquals(100L, ring.leadMs(5100L))
        assertEquals(50L, ring.leadMs(5150L))

        val dst50 = FloatArray(50)
        val dst100 = FloatArray(50)
        val dst150 = FloatArray(50)

        // At +50 ms (5050), playhead is at frame 50 -> window is 0..49
        assertTrue(ring.read(nowMs = 5050L, delayMs = 0, mix = Mix.MONO, dst = dst50))
        // At +100 ms (5100), playhead is at frame 100 -> window is 50..99
        assertTrue(ring.read(nowMs = 5100L, delayMs = 0, mix = Mix.MONO, dst = dst100))
        // At +150 ms (5150), playhead is at frame 150 -> window is 100..149
        assertTrue(ring.read(nowMs = 5150L, delayMs = 0, mix = Mix.MONO, dst = dst150))

        for (i in 0 until 50) {
            assertEquals(i / 32768f, dst50[i], 1e-6f)
            assertEquals((50 + i) / 32768f, dst100[i], 1e-6f)
            assertEquals((100 + i) / 32768f, dst150[i], 1e-6f)
        }
    }

    @Test fun wrapAroundMatchesContiguousRead() {
        val rate = 1000
        // Ring holds 500 frames (500 ms); contiguous ring holds 2000 frames (2000 ms)
        val smallRing = TapRing(sampleRate = rate, channels = 2, capacityMs = 500)
        val bigRing = TapRing(sampleRate = rate, channels = 2, capacityMs = 2000)

        // Write 750 frames in 3 chunks of 250 frames so smallRing wraps past 500
        for (c in 0 until 3) {
            val base = c * 250
            val chunk = packStereo(250, { i -> (base + i + 100).toShort() }, { i -> (-(base + i + 100)).toShort() })
            val t = 1000L + c * 250L
            smallRing.write(chunk, 0, chunk.size, nowMs = t)
            bigRing.write(chunk, 0, chunk.size, nowMs = t)
        }

        // Read the last 300 frames (frames 450..749, crossing the 500 wrap boundary in smallRing)
        for (mix in Mix.values()) {
            val wrapped = FloatArray(300)
            val contiguous = FloatArray(300)
            assertTrue(smallRing.read(nowMs = 1750L, delayMs = 0, mix = mix, dst = wrapped))
            assertTrue(bigRing.read(nowMs = 1750L, delayMs = 0, mix = mix, dst = contiguous))
            assertArrayEquals("Mix $mix wrap-around equals contiguous", contiguous, wrapped, 0f)
        }
    }

    @Test fun tooOldOrNothingWrittenReturnsFalseAndLeavesDstAlone() {
        val rate = 1000
        val ring = TapRing(sampleRate = rate, channels = 1, capacityMs = 500, targetLeadMs = 0)
        val sentinel = FloatArray(100) { 42f }

        // Nothing written yet
        assertFalse(ring.read(nowMs = 1000L, delayMs = 0, mix = Mix.MONO, dst = sentinel))
        assertEquals(42f, sentinel[0], 0f)

        // Write 600 frames (wrapping a 500-frame ring so frames 0..99 are overwritten)
        for (c in 0 until 6) {
            val chunk = packMono(100) { i -> (c * 100 + i).toShort() }
            ring.write(chunk, 0, chunk.size, nowMs = 1000L + c * 100L)
        }

        // At nowMs = 1600, playhead = 600, oldest available = 100.
        // Asking for delayMs = 450 requests window ending at 150 -> frames 50..149 (older than 100) -> false!
        assertFalse(ring.read(nowMs = 1600L, delayMs = 450, mix = Mix.MONO, dst = sentinel))
        assertEquals(42f, sentinel[0], 0f)

        // Asking for delayMs = 550 requests window ending at 50 -> startFrame = -50 -> false!
        assertFalse(ring.read(nowMs = 1600L, delayMs = 550, mix = Mix.MONO, dst = sentinel))
        assertEquals(42f, sentinel[0], 0f)

        // After clear(), read returns false again
        ring.clear()
        assertFalse(ring.read(nowMs = 1600L, delayMs = 0, mix = Mix.MONO, dst = sentinel))
        assertEquals(42f, sentinel[0], 0f)
    }

    @Test fun oneChannelSourceAllThreeMixesReturnChannel() {
        val rate = 1000
        val ring = TapRing(sampleRate = rate, channels = 1, capacityMs = 1000)
        val chunk = packMono(80) { i -> ((i - 40) * 500).toShort() }
        ring.write(chunk, 0, chunk.size, nowMs = 1000L)

        val mono = FloatArray(80)
        val left = FloatArray(80)
        val right = FloatArray(80)
        assertTrue(ring.read(nowMs = 1080L, delayMs = 0, mix = Mix.MONO, dst = mono))
        assertTrue(ring.read(nowMs = 1080L, delayMs = 0, mix = Mix.LEFT, dst = left))
        assertTrue(ring.read(nowMs = 1080L, delayMs = 0, mix = Mix.RIGHT, dst = right))

        assertArrayEquals(mono, left, 0f)
        assertArrayEquals(mono, right, 0f)
        for (i in 0 until 80) {
            assertEquals(((i - 40) * 500) / 32768f, mono[i], 1e-6f)
        }
    }

    @Test fun burstyWritesGiveMonotonicEvenlyAdvancingReadWindow() {
        val rate = 1000 // 1 frame per ms
        val ring = TapRing(sampleRate = rate, channels = 1, capacityMs = 2000) // default 150 ms target lead
        var writtenFrames = 0

        fun writeChunk(frames: Int, nowMs: Long) {
            val base = writtenFrames
            val chunk = packMono(frames) { i -> (base + i).toShort() }
            ring.write(chunk, 0, chunk.size, nowMs = nowMs)
            writtenFrames += frames
        }

        // Prime with 200 ms burst at t=1000 (as CarAudioOut PRIME_MS = 200 does)
        repeat(4) { writeChunk(50, 1000L) }

        // Simulate jittery capture where 40 ms chunks arrive with uneven delays up to +90 ms late
        // (e.g. a 110 ms gap followed by rapid catch-up chunks so average rate == 1.0x real time):
        val jitterMs = listOf(0L, -10L, 70L, 40L, 0L, -15L, 85L, 30L)
        val arrivals = (1..36).map { k ->
            val nominal = k * 40L
            nominal + jitterMs[k % jitterMs.size]
        }.sorted()
        var nextWriteIdx = 0

        val dst = FloatArray(32)
        var prevEndSample = -1
        // Sample the UI every 20 ms from t=1040 to t=2440 with delayMs = 0
        for (t in 1040L..2440L step 20L) {
            while (nextWriteIdx < arrivals.size && 1000L + arrivals[nextWriteIdx] <= t) {
                writeChunk(40, 1000L + arrivals[nextWriteIdx])
                nextWriteIdx++
            }
            assertTrue("Read succeeds at t=$t", ring.read(nowMs = t, delayMs = 0, mix = Mix.MONO, dst = dst))
            val endSample = (dst.last() * 32768f).roundToInt()
            if (prevEndSample >= 0) {
                val step = endSample - prevEndSample
                // Nominal step is 20 frames; with ±5% trim it must always advance by 18..22 frames (never 0 or a big jump!)
                assertTrue(
                    "Read window must advance smoothly at t=$t: prev=$prevEndSample, cur=$endSample (step=$step)",
                    step in 18..22
                )
            }
            prevEndSample = endSample
        }
    }

    @Test fun leadConvergesToTargetLeadMs() {
        val rate = 1000
        val ring = TapRing(sampleRate = rate, channels = 1, capacityMs = 2000, targetLeadMs = 150)
        var writtenFrames = 0

        // Start with zero lead: write 40-frame chunks every 40 ms for 6 seconds (150 chunks)
        for (c in 0..150) {
            val base = writtenFrames
            val chunk = packMono(40) { i -> ((base + i) % 10000).toShort() }
            ring.write(chunk, 0, chunk.size, nowMs = 1000L + c * 40L)
            writtenFrames += 40
        }
        // Right after a write at t=7000, lead is target + half chunk; check mid-chunk (+20 ms)
        val convergedFromLow = ring.leadMs(1000L + 150 * 40L + 20L)
        assertTrue(
            "Lead starting from 0 should converge near 150 ms, got $convergedFromLow ms",
            abs(convergedFromLow - 150L) <= 25L
        )

        // Now start with a huge 400 ms burst lead at t=10000 and stream 40 ms chunks every 40 ms for 10 seconds (250 chunks)
        ring.clear()
        writtenFrames = 0
        repeat(10) {
            val base = writtenFrames
            val chunk = packMono(40) { i -> ((base + i) % 10000).toShort() }
            ring.write(chunk, 0, chunk.size, nowMs = 10_000L)
            writtenFrames += 40
        }
        assertEquals(400L, ring.leadMs(10_000L))
        for (c in 1..250) {
            val base = writtenFrames
            val chunk = packMono(40) { i -> ((base + i) % 10000).toShort() }
            ring.write(chunk, 0, chunk.size, nowMs = 10_000L + c * 40L)
            writtenFrames += 40
        }
        val convergedFromHigh = ring.leadMs(10_000L + 250 * 40L + 20L)
        assertTrue(
            "Lead starting from 400 ms should converge near 150 ms, got $convergedFromHigh ms",
            abs(convergedFromHigh - 150L) <= 25L
        )
    }
}
