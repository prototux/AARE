package net.prototux.aare.player

import net.prototux.aare.aap.pcm.PcmFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayerAudioTest {
    private fun column(m: FloatArray, inCh: Int, outCh: Int, out: Int) = (0 until inCh).map { m[it * outCh + out] }

    @Test fun sameCountIsIdentity() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), downmixCoefficients(2, 2), 0f)
    }

    @Test fun monoGoesToBothSides() {
        assertArrayEquals(floatArrayOf(1f, 1f), downmixCoefficients(1, 2), 0f)
    }

    @Test fun stereoToMonoAverages() {
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), downmixCoefficients(2, 1), 0f)
    }

    @Test fun surroundDownmixDropsLfeAndNeverClips() {
        for (inCh in listOf(3, 4, 5, 6, 7, 8)) {
            val m = downmixCoefficients(inCh, 2)
            for (out in 0..1) assertTrue("$inCh ch, out $out", column(m, inCh, 2, out).sum() <= 1.0001f)
        }
        val m = downmixCoefficients(6, 2)
        assertEquals(0f, m[3 * 2], 0f); assertEquals(0f, m[3 * 2 + 1], 0f) // LFE
        assertTrue(m[0] > m[2 * 2]) // front left louder than centre in the left
        assertEquals(0f, m[1], 0f) // front left not in the right
    }

    @Test fun sourceRoutesPushedSoundAndLetsGo() {
        val src = CarPlayerAudio.Source(PcmFormat(8000, 1))
        assertFalse(CarPlayerAudio.toCar.value)
        src.start()
        assertTrue(CarPlayerAudio.toCar.value)
        CarPlayerAudio.push(byteArrayOf(5, 0, 6, 0))
        val buf = ByteArray(4)
        assertEquals(4, src.read(buf, 0, 4))
        assertArrayEquals(byteArrayOf(5, 0, 6, 0), buf)
        src.release()
        assertFalse(CarPlayerAudio.toCar.value)
        CarPlayerAudio.push(byteArrayOf(1, 0)) // nobody listening: dropped, no crash
    }

    @Test fun activeChangesNotifyTheSession() {
        var calls = 0
        CarPlayerAudio.listener = { calls++ }
        CarPlayerAudio.setActive(true); CarPlayerAudio.setActive(true)
        CarPlayerAudio.setActive(false)
        CarPlayerAudio.listener = null
        assertEquals(2, calls)
    }
}
