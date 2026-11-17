package net.prototux.aare.tts

import net.prototux.aare.aap.pcm.PcmFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CarVoiceTest {
    private val fmt = PcmFormat(16000, 1)

    @Test fun sourceIsTheEngineTargetWhileOpen() {
        val s = CarVoice.Source(fmt)
        assertNull(CarVoice.current)
        s.start()
        val q = CarVoice.current!!
        assertEquals(fmt, q.format)
        s.release()
        assertNull(CarVoice.current)
        assertEquals(-1, q.read(ByteArray(32), 0, 32)) // closed: the audio loop stops
    }

    @Test fun olderSessionReleasingDoesNotClearNewerOne() {
        val old = CarVoice.Source(fmt).also { it.start() }
        val new = CarVoice.Source(fmt).also { it.start() }
        val current = CarVoice.current
        old.release()
        assertSame(current, CarVoice.current)
        new.release()
        assertNull(CarVoice.current)
    }

    @Test fun pushedSpeechComesOutOfTheSource() {
        val s = CarVoice.Source(fmt).also { it.start() }
        CarVoice.current!!.push(byteArrayOf(1, 0, 2, 0))
        val buf = ByteArray(8)
        assertEquals(8, s.read(buf, 0, 8))
        assertEquals(listOf<Byte>(1, 0, 2, 0, 0, 0, 0, 0), buf.toList())
        s.release()
    }
}
