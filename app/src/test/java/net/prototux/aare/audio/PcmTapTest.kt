package net.prototux.aare.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PcmTapTest {
    private val stream = Any()

    @After fun tearDown() {
        repeat(4) { PcmTap.release() }
        PcmTap.close(stream)
    }

    @Test fun noListenerMeansNoRing() {
        PcmTap.open(stream, PcmTap.Format(48_000, 2))
        assertNull(PcmTap.ring)
    }

    @Test fun listenBeforeTheStreamGetsARingWhenItOpens() {
        PcmTap.listen()
        assertNull(PcmTap.ring)
        PcmTap.open(stream, PcmTap.Format(48_000, 2))
        val r = PcmTap.ring!!
        assertEquals(48_000, r.sampleRate)
        assertEquals(2, r.channels)
    }

    @Test fun lastListenerLeavingDropsTheRing() {
        PcmTap.open(stream, PcmTap.Format(48_000, 2))
        PcmTap.listen()
        PcmTap.listen()
        PcmTap.release()
        assertNotNull(PcmTap.ring)
        PcmTap.release()
        assertNull(PcmTap.ring)
    }

    @Test fun aNewFormatGetsANewRingTheSameFormatKeepsIt() {
        PcmTap.listen()
        PcmTap.open(stream, PcmTap.Format(48_000, 2))
        val first = PcmTap.ring
        PcmTap.open(stream, PcmTap.Format(48_000, 2))
        assertSame(first, PcmTap.ring)
        PcmTap.open(stream, PcmTap.Format(44_100, 2))
        assertNotSame(first, PcmTap.ring)
        assertEquals(44_100, PcmTap.ring!!.sampleRate)
    }

    @Test fun anOldStreamClosingLateDoesNotHideTheNewOne() {
        val newer = Any()
        PcmTap.open(stream, PcmTap.Format(48_000, 2))
        PcmTap.open(newer, PcmTap.Format(48_000, 2))
        PcmTap.close(stream)
        assertEquals(PcmTap.Format(48_000, 2), PcmTap.format.value)
        PcmTap.close(newer)
        assertNull(PcmTap.format.value)
    }
}
