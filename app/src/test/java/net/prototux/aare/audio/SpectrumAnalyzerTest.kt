package net.prototux.aare.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

class SpectrumAnalyzerTest {

    private val rates = listOf(48_000, 44_100)
    private val bandCounts = listOf(32, 48)

    private fun sineWave(rate: Int, freqHz: Double, dbFs: Double, size: Int = Spectrum.FFT_SIZE): FloatArray {
        val amp = 10.0.pow(dbFs / 20.0)
        return FloatArray(size) { n ->
            (amp * sin(2.0 * PI * freqHz * n / rate)).toFloat()
        }
    }

    private fun bandForBin(analyzer: SpectrumAnalyzer, bin: Int): Int {
        for (b in 0 until analyzer.bandCount) {
            if (bin in analyzer.bandStartBin[b]..analyzer.bandEndBin[b]) return b
        }
        return -1
    }

    @Test fun oneKhzSineAtMinus6DbfsPeaksInRightBandAndIsIsolated() {
        for (rate in rates) {
            for (bands in bandCounts) {
                val analyzer = SpectrumAnalyzer(sampleRate = rate, bandCount = bands)
                val out = FloatArray(bands)
                val samples = sineWave(rate, 1000.0, -6.0)
                analyzer.analyze(samples, out)

                val expectedBin = Math.round(1000.0 * Spectrum.FFT_SIZE / rate).toInt()
                val targetBand = bandForBin(analyzer, expectedBin)
                assertTrue("targetBand found for $rate Hz, $bands bands", targetBand >= 0)

                val peakLevel = out[targetBand]
                assertEquals("1 kHz -6 dBFS at $rate Hz, $bands bands", -6f, peakLevel, 1f)

                for (b in 0 until bands) {
                    if (abs(b - targetBand) >= 4) {
                        assertTrue(
                            "band $b (${out[b]} dB) should be >=30 dB below peak ($peakLevel dB) at $rate/$bands",
                            out[b] <= peakLevel - 30f
                        )
                    }
                }
            }
        }
    }

    @Test fun offBinSineAtMinus6DbfsWithinOneDb() {
        for (rate in rates) {
            for (bands in bandCounts) {
                val analyzer = SpectrumAnalyzer(sampleRate = rate, bandCount = bands)
                val out = FloatArray(bands)
                // Test multiple off-bin frequencies across low, mid, and high ranges
                for (k in listOf(5, 21, 42, 120, 400)) {
                    val freq = (k + 0.5) * rate.toDouble() / Spectrum.FFT_SIZE
                    val samples = sineWave(rate, freq, -6.0)
                    analyzer.analyze(samples, out)
                    val maxBandLevel = out.maxOrNull() ?: Spectrum.FLOOR_DB
                    assertEquals(
                        "Off-bin sine at k=$k+0.5 ($freq Hz) for $rate Hz, $bands bands",
                        -6f,
                        maxBandLevel,
                        1f
                    )
                }
            }
        }
    }

    @Test fun fullScaleSineReadsZeroDbfs() {
        for (rate in rates) {
            for (bands in bandCounts) {
                val analyzer = SpectrumAnalyzer(sampleRate = rate, bandCount = bands)
                val out = FloatArray(bands)
                val samples = sineWave(rate, 1000.0, 0.0)
                analyzer.analyze(samples, out)
                val peak = out.maxOrNull() ?: Spectrum.FLOOR_DB
                assertEquals("Full-scale sine at $rate Hz, $bands bands", 0f, peak, 1f)
            }
        }
    }

    @Test fun silenceReadsFloorDb() {
        for (rate in rates) {
            for (bands in bandCounts) {
                val analyzer = SpectrumAnalyzer(sampleRate = rate, bandCount = bands)
                val out = FloatArray(bands) { 99f }
                val silence = FloatArray(Spectrum.FFT_SIZE)
                analyzer.analyze(silence, out)
                for (b in 0 until bands) {
                    assertEquals("Band $b on silence ($rate/$bands)", Spectrum.FLOOR_DB, out[b], 0f)
                }
            }
        }
    }

    @Test fun dcOnlyIsRejected() {
        for (rate in rates) {
            for (bands in bandCounts) {
                val analyzer = SpectrumAnalyzer(sampleRate = rate, bandCount = bands)
                val out = FloatArray(bands)
                val dc = FloatArray(Spectrum.FFT_SIZE) { 0.5f }
                analyzer.analyze(dc, out)
                for (b in 0 until bands) {
                    assertTrue(
                        "Band $b with DC=0.5 should be < -40 dB, got ${out[b]} ($rate/$bands)",
                        out[b] < -40f
                    )
                }
            }
        }
    }

    @Test fun lowSinesLightLowBandsAndNoBandHasZeroBins() {
        for (rate in rates) {
            for (bands in bandCounts) {
                val analyzer = SpectrumAnalyzer(sampleRate = rate, bandCount = bands)
                for (b in 0 until bands) {
                    val start = analyzer.bandStartBin[b]
                    val end = analyzer.bandEndBin[b]
                    assertTrue("Band $b startBin ($start) >= 1", start >= 1)
                    assertTrue("Band $b has >= 1 bin ($start..$end) at $rate/$bands", end >= start)
                    if (b > 0) {
                        assertEquals("Contiguous bin coverage at band $b", analyzer.bandEndBin[b - 1] + 1, start)
                    }
                }

                val out = FloatArray(bands)
                for (freq in listOf(30.0, 50.0, 80.0)) {
                    val samples = sineWave(rate, freq, -6.0)
                    analyzer.analyze(samples, out)
                    var bestBand = 0
                    for (b in 1 until bands) {
                        if (out[b] > out[bestBand]) bestBand = b
                    }
                    assertTrue(
                        "$freq Hz should light a low band (< ${bands / 3}), got band $bestBand at $rate/$bands",
                        bestBand < bands / 3
                    )
                    assertEquals(
                        "$freq Hz at -6 dBFS should peak near -6 dB at $rate/$bands",
                        -6f,
                        out[bestBand],
                        1.2f
                    )
                }
            }
        }
    }

    @Test fun bandEdgesStrictlyIncreasingAndClampedToNyquist() {
        for (rate in rates) {
            for (bands in bandCounts) {
                val analyzer = SpectrumAnalyzer(sampleRate = rate, bandCount = bands, minHz = 20f, maxHz = 25_000f)
                val edges = analyzer.bandEdgesHz
                assertEquals(bands + 1, edges.size)
                assertEquals(20f, edges.first(), 1e-3f)
                assertEquals(minOf(25_000f, rate / 2f), edges.last(), 1e-2f)
                for (i in 1 until edges.size) {
                    assertTrue("Edge $i (${edges[i]}) > edge ${i - 1} (${edges[i - 1]})", edges[i] > edges[i - 1])
                }
            }
        }
    }

    @Test fun ballisticsAttackJumpsImmediately() {
        val bal = SpectrumBallistics(bandCount = 4, decayDbPerSec = 15f, holdMs = 500, capFallDbPerSec = 30f)
        for (i in 0 until 4) {
            assertEquals(Spectrum.FLOOR_DB, bal.bars[i], 0f)
            assertEquals(Spectrum.FLOOR_DB, bal.caps[i], 0f)
        }
        bal.update(floatArrayOf(-12f, -6f, 0f, -40f), dtMs = 33)
        assertEquals(-12f, bal.bars[0], 1e-4f)
        assertEquals(-6f, bal.bars[1], 1e-4f)
        assertEquals(0f, bal.bars[2], 1e-4f)
        assertEquals(-40f, bal.bars[3], 1e-4f)
    }

    @Test fun ballisticsDecayLowersByDecayDbPerSec() {
        val bal = SpectrumBallistics(bandCount = 2, decayDbPerSec = 15f, holdMs = 500, capFallDbPerSec = 30f)
        bal.update(floatArrayOf(-6f, -10f), dtMs = 33)
        val floorInput = floatArrayOf(Spectrum.FLOOR_DB, Spectrum.FLOOR_DB)
        // Step 20 x 50 ms = 1000 ms
        repeat(20) {
            bal.update(floorInput, dtMs = 50)
        }
        assertEquals(-21f, bal.bars[0], 1f)
        assertEquals(-25f, bal.bars[1], 1f)

        // Verify dtMs clamping at 100 ms on a long pause/resume gap
        bal.update(floatArrayOf(0f, 0f), dtMs = 10)
        bal.update(floorInput, dtMs = 5_000) // clamped to 100 ms -> -1.5 dB
        assertEquals(-1.5f, bal.bars[0], 0.1f)
    }

    @Test fun ballisticsCapHoldsThenFallsAndNeverBelowBar() {
        val bal = SpectrumBallistics(bandCount = 1, decayDbPerSec = 15f, holdMs = 500, capFallDbPerSec = 30f)
        bal.update(floatArrayOf(-6f), dtMs = 33)
        assertEquals(-6f, bal.bars[0], 1e-4f)
        assertEquals(-6f, bal.caps[0], 1e-4f)

        val floor = floatArrayOf(Spectrum.FLOOR_DB)
        // During the 500 ms hold, cap stays at -6 dB while bar decays
        repeat(10) {
            bal.update(floor, dtMs = 50)
        }
        assertEquals("Cap still held at 500 ms", -6f, bal.caps[0], 1e-4f)
        assertEquals("Bar decayed 7.5 dB over 500 ms", -13.5f, bal.bars[0], 0.5f)

        // After another 200 ms (4 x 50 ms), cap falls by 30 dB/s * 0.2 s = 6 dB -> -12 dB
        repeat(4) {
            bal.update(floor, dtMs = 50)
        }
        assertEquals("Cap fell by 6 dB over 200 ms", -12f, bal.caps[0], 0.5f)
        assertTrue("Cap >= bar", bal.caps[0] >= bal.bars[0])

        // If bar jumps above cap, cap jumps with it
        bal.update(floatArrayOf(-3f), dtMs = 50)
        assertEquals(-3f, bal.bars[0], 1e-4f)
        assertEquals(-3f, bal.caps[0], 1e-4f)

        // If cap falls faster than bar, cap is clamped to never go below bar
        val fastCap = SpectrumBallistics(bandCount = 1, decayDbPerSec = 5f, holdMs = 100, capFallDbPerSec = 100f)
        fastCap.update(floatArrayOf(-10f), dtMs = 10)
        repeat(10) {
            fastCap.update(floor, dtMs = 50)
            assertTrue("Cap (${fastCap.caps[0]}) never below bar (${fastCap.bars[0]})", fastCap.caps[0] >= fastCap.bars[0])
        }

        fastCap.reset()
        assertEquals(Spectrum.FLOOR_DB, fastCap.bars[0], 0f)
        assertEquals(Spectrum.FLOOR_DB, fastCap.caps[0], 0f)
    }
}
