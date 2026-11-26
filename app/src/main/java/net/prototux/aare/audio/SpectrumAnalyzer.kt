package net.prototux.aare.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

object Spectrum {
    const val FLOOR_DB = -60f
    const val FFT_SIZE = 2048
}

/** Not thread-safe; one per visual, used from one coroutine. analyze() must not allocate. */
class SpectrumAnalyzer(
    val sampleRate: Int,
    val bandCount: Int,
    val fftSize: Int = Spectrum.FFT_SIZE,
    minHz: Float = 20f,
    maxHz: Float = 20_000f,          // clamp to sampleRate / 2
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(bandCount > 0) { "bandCount must be positive" }
        require(fftSize >= 4 && (fftSize and (fftSize - 1)) == 0) { "fftSize must be a power of 2 >= 4" }
    }

    private val nyquistHz: Float = sampleRate * 0.5f
    private val effectiveMaxHz: Float = minOf(maxHz, nyquistHz).coerceAtLeast(minHz + 1f)
    private val effectiveMinHz: Float = minHz.coerceAtLeast(1f).coerceAtMost(effectiveMaxHz - 1f)

    /** bandCount + 1 edges in Hz, strictly increasing. */
    val bandEdgesHz: FloatArray = FloatArray(bandCount + 1)

    internal val bandStartBin: IntArray = IntArray(bandCount)
    internal val bandEndBin: IntArray = IntArray(bandCount)

    private val halfN: Int = fftSize / 2
    private val window: FloatArray = FloatArray(fftSize)
    private val bitRev: IntArray = IntArray(fftSize)
    private val cosTable: FloatArray = FloatArray(halfN)
    private val sinTable: FloatArray = FloatArray(halfN)
    private val re: FloatArray = FloatArray(fftSize)
    private val im: FloatArray = FloatArray(fftSize)
    /** Unnormalized magnitude squared (re^2 + im^2) for bins 0..N/2. */
    private val magSq: FloatArray = FloatArray(halfN + 1)
    private val normSq: Float = (4f / fftSize.toFloat()) * (4f / fftSize.toFloat())
    private val minMagSq: Float = 10f.pow(MIN_DB_COMPUTE / 10f)

    init {
        // Log-spaced band edges from effectiveMinHz to effectiveMaxHz
        val logMin = ln(effectiveMinHz.toDouble())
        val logMax = ln(effectiveMaxHz.toDouble())
        for (i in 0..bandCount) {
            val t = i.toDouble() / bandCount.toDouble()
            bandEdgesHz[i] = kotlin.math.exp(logMin + t * (logMax - logMin)).toFloat()
        }
        bandEdgesHz[0] = effectiveMinHz
        bandEdgesHz[bandCount] = effectiveMaxHz
        // Ensure strictly increasing even in float precision
        for (i in 1..bandCount) {
            if (bandEdgesHz[i] <= bandEdgesHz[i - 1]) {
                bandEdgesHz[i] = Math.nextUp(bandEdgesHz[i - 1])
            }
        }

        // Map bands to non-DC DFT bins [1 .. halfN - 1] such that every band has >= 1 bin
        // and low bands start at bin 1, stepping by at least 1 bin per band on the low end.
        val maxBin = maxOf(1, halfN - 1)
        val df = sampleRate.toFloat() / fftSize.toFloat()
        val idealBoundaryBins = IntArray(bandCount + 1)
        idealBoundaryBins[0] = 1
        idealBoundaryBins[bandCount] = maxBin + 1
        for (b in 1 until bandCount) {
            val rawBin = (bandEdgesHz[b] / df).toInt()
            idealBoundaryBins[b] = rawBin.coerceIn(1, maxBin)
        }

        // Forward pass: enforce at least 1 bin per band when total bins >= bandCount
        if (maxBin >= bandCount) {
            val boundary = IntArray(bandCount + 1)
            boundary[0] = 1
            boundary[bandCount] = maxBin + 1
            for (b in 1 until bandCount) {
                val minAllowed = boundary[b - 1] + 1
                val maxAllowed = (maxBin + 1) - (bandCount - b)
                boundary[b] = idealBoundaryBins[b].coerceIn(minAllowed, maxAllowed)
            }
            for (b in 0 until bandCount) {
                bandStartBin[b] = boundary[b]
                bandEndBin[b] = boundary[b + 1] - 1
            }
        } else {
            // Fallback if someone configures a tiny fftSize where maxBin < bandCount
            for (b in 0 until bandCount) {
                val k = (1 + b * maxBin / bandCount).coerceIn(1, maxBin)
                bandStartBin[b] = k
                bandEndBin[b] = k
            }
        }

        // Precompute periodic Hann window: w[n] = 0.5 * (1 - cos(2*pi*n / N)), coherent gain = 0.5
        val twoPiOverN = 2.0 * PI / fftSize.toDouble()
        for (n in 0 until fftSize) {
            window[n] = (0.5 * (1.0 - cos(twoPiOverN * n))).toFloat()
        }

        // Precompute bit-reversal permutation
        val bits = Integer.numberOfTrailingZeros(fftSize)
        for (i in 0 until fftSize) {
            bitRev[i] = Integer.reverse(i) ushr (32 - bits)
        }

        // Precompute twiddle factors exp(-j * 2 * pi * k / N) for k in 0 until N/2
        for (k in 0 until halfN) {
            val angle = -twoPiOverN * k
            cosTable[k] = cos(angle).toFloat()
            sinTable[k] = sin(angle).toFloat()
        }
    }

    private fun dbAt(k: Int): Float {
        val m2 = magSq[k] * normSq
        return if (m2 <= minMagSq) MIN_DB_COMPUTE else 10f * log10(m2)
    }

    /**
     * [samples]: fftSize values in -1..1, oldest first (one channel, already mixed).
     * Writes dBFS per band into [out] (size bandCount, low band first), clamped to [FLOOR_DB, 0].
     */
    fun analyze(samples: FloatArray, out: FloatArray) {
        val n = fftSize
        require(samples.size >= n) { "samples.size (${samples.size}) < fftSize ($n)" }
        require(out.size >= bandCount) { "out.size (${out.size}) < bandCount ($bandCount)" }

        // 1. Remove mean (DC offset) so a DC constant never leaks into low bands via window sidelobes,
        // and apply Hann window into bit-reversed positions.
        var sum = 0f
        for (i in 0 until n) {
            sum += samples[i]
        }
        val mean = sum / n.toFloat()

        for (i in 0 until n) {
            val j = bitRev[i]
            re[j] = (samples[i] - mean) * window[i]
            im[j] = 0f
        }

        // 2. In-place iterative radix-2 Cooley-Tukey FFT
        var len = 2
        while (len <= n) {
            val halfLen = len ushr 1
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (j in 0 until halfLen) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val uIdx = i + j
                    val vIdx = uIdx + halfLen
                    val vr = re[vIdx]
                    val vi = im[vIdx]
                    val tr = wr * vr - wi * vi
                    val ti = wr * vi + wi * vr
                    re[vIdx] = re[uIdx] - tr
                    im[vIdx] = im[uIdx] - ti
                    re[uIdx] = re[uIdx] + tr
                    im[uIdx] = im[uIdx] + ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }

        // 3. Compute magnitude squared for bins 1..N/2 (no sqrt or log10 across all 1024 bins)
        magSq[0] = 0f
        for (k in 1..halfN) {
            val r = re[k]
            val m = im[k]
            magSq[k] = r * r + m * m
        }

        // 4. Per band, find max bin by magnitude squared and compute log10 only for bestK and its 2 neighbors
        val maxNonNyquist = halfN - 1
        for (b in 0 until bandCount) {
            val kStart = bandStartBin[b]
            val kEnd = bandEndBin[b]
            var bestK = kStart
            var bestMagSq = magSq[kStart]
            for (k in (kStart + 1)..kEnd) {
                val v = magSq[k]
                if (v > bestMagSq) {
                    bestMagSq = v
                    bestK = k
                }
            }

            val bestVal = dbAt(bestK)
            // Parabolic peak interpolation on log-magnitudes (dB) with neighbor bins (excluding DC bin 0)
            var peakDb = bestVal
            if (bestK in 2 until maxNonNyquist) {
                val alpha = dbAt(bestK - 1)
                val beta = bestVal
                val gamma = dbAt(bestK + 1)
                if (beta >= alpha && beta >= gamma) {
                    val denom = alpha - 2f * beta + gamma
                    if (denom < -1e-5f) {
                        val p = (0.5f * (alpha - gamma) / denom).coerceIn(-0.5f, 0.5f)
                        peakDb = beta - 0.25f * (alpha - gamma) * p
                    }
                }
            } else if (bestK == 1 && maxNonNyquist >= 2) {
                // For bin 1, avoid using DC bin 0 as left neighbor; if bin 2 is strong (between bin 1 and 2),
                // estimate scalloping correction from the two-bin Hann mainlobe drop.
                val beta = bestVal
                val gamma = dbAt(2)
                if (beta >= gamma && beta - gamma <= 6f) {
                    // Hann mainlobe at offset p in [0, 0.5] drops by up to ~1.42 dB when beta == gamma
                    val ratio = 1f - (beta - gamma) / 6f
                    peakDb = beta + 1.42f * ratio * ratio
                }
            }

            out[b] = peakDb.coerceIn(Spectrum.FLOOR_DB, 0f)
        }
    }

    private companion object {
        private const val MIN_DB_COMPUTE = -120f
    }
}

/** Bar and peak-cap levels over time (dBFS). */
class SpectrumBallistics(
    val bandCount: Int,
    val decayDbPerSec: Float = 45f,
    val holdMs: Long = 300,
    val capFallDbPerSec: Float = 60f,
) {
    init {
        require(bandCount > 0) { "bandCount must be positive" }
    }

    val bars: FloatArray = FloatArray(bandCount) { Spectrum.FLOOR_DB }
    val caps: FloatArray = FloatArray(bandCount) { Spectrum.FLOOR_DB }
    private val holdRemainingMs: LongArray = LongArray(bandCount)

    /**
     * Attack: a bar jumps straight up to a higher level. Decay: linear in dB.
     * Caps: hold holdMs at their max, then fall; never below their bar.
     * Clamp dtMs to at most 100 so a resume doesn't jump.
     */
    fun update(levels: FloatArray, dtMs: Long) {
        require(levels.size >= bandCount) { "levels.size (${levels.size}) < bandCount ($bandCount)" }
        val clampedDt = dtMs.coerceIn(0L, 100L)
        val dtSec = clampedDt / 1000f
        val barDrop = decayDbPerSec * dtSec

        for (i in 0 until bandCount) {
            val input = levels[i].coerceIn(Spectrum.FLOOR_DB, 0f)
            // Bar: instant attack, linear decay in dB
            val prevBar = bars[i]
            val newBar = if (input >= prevBar) {
                input
            } else {
                maxOf(Spectrum.FLOOR_DB, maxOf(input, prevBar - barDrop))
            }
            bars[i] = newBar

            // Cap: rises with bar and resets hold timer; otherwise holds for holdMs then falls at capFallDbPerSec
            val prevCap = caps[i]
            if (newBar >= prevCap) {
                caps[i] = newBar
                holdRemainingMs[i] = holdMs
            } else {
                val rem = holdRemainingMs[i]
                if (clampedDt <= rem) {
                    holdRemainingMs[i] = rem - clampedDt
                    caps[i] = maxOf(newBar, prevCap)
                } else {
                    val fallMs = clampedDt - rem
                    holdRemainingMs[i] = 0L
                    val capDrop = capFallDbPerSec * (fallMs / 1000f)
                    caps[i] = maxOf(Spectrum.FLOOR_DB, maxOf(newBar, prevCap - capDrop))
                }
            }
        }
    }

    fun reset() {
        bars.fill(Spectrum.FLOOR_DB)
        caps.fill(Spectrum.FLOOR_DB)
        holdRemainingMs.fill(0L)
    }
}
