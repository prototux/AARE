package net.prototux.aare.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The root audio helper's wire: frames, commands, replies, and AARE's side of the streams. */
class SuAudioWireTest {
    private fun frames(vararg f: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val w = FrameWriter(out)
        f.forEach { (id, data) -> w.write(id, data) }
        return out.toByteArray()
    }

    /** At most [chunk] bytes per read, as a pipe may hand them over. */
    private class Trickle(data: ByteArray, private val chunk: Int) : InputStream() {
        private val input = ByteArrayInputStream(data)
        override fun read(): Int = input.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, minOf(len, chunk))
    }

    private fun readAll(input: InputStream): List<Pair<Int, List<Byte>>> {
        val r = FrameReader(input)
        return generateSequence { r.next() }.map { it.id to it.data.toList() }.toList()
    }

    @Test fun framesComeBackAsWritten() {
        val music = ByteArray(3840) { it.toByte() }
        val voice = ByteArray(640) { (it * 7).toByte() }
        val bytes = frames(1 to music, 2 to voice, 0 to "started 3".toByteArray(), 1 to ByteArray(0))
        assertEquals(4 * SuAudioWire.HEADER + music.size + voice.size + 9, bytes.size)
        // Header: id, then the length big-endian.
        assertEquals(listOf<Byte>(1, 0, 0, 0x0f, 0x00), bytes.take(5))
        assertEquals(
            listOf(1 to music.toList(), 2 to voice.toList(), 0 to "started 3".toByteArray().toList(), 1 to emptyList()),
            readAll(ByteArrayInputStream(bytes)),
        )
    }

    @Test fun partialReadsDontMatter() {
        val music = ByteArray(1000) { it.toByte() }
        val bytes = frames(1 to music, 2 to ByteArray(3) { 9 })
        for (chunk in listOf(1, 2, 3, 5, 7, 64)) {
            assertEquals("reads of $chunk", listOf(1 to music.toList(), 2 to listOf<Byte>(9, 9, 9)), readAll(Trickle(bytes, chunk)))
        }
    }

    @Test fun writesFromAnOffset() {
        val out = ByteArrayOutputStream()
        FrameWriter(out).write(5, byteArrayOf(1, 2, 3, 4, 5), offset = 1, length = 3)
        assertEquals(listOf(5 to listOf<Byte>(2, 3, 4)), readAll(ByteArrayInputStream(out.toByteArray())))
    }

    @Test fun aCleanEndIsNull() {
        assertNull(FrameReader(ByteArrayInputStream(ByteArray(0))).next())
        val r = FrameReader(ByteArrayInputStream(frames(1 to byteArrayOf(1))))
        r.next()
        assertNull(r.next())
    }

    @Test fun anEndInsideAFrameThrows() {
        val bytes = frames(1 to ByteArray(100))
        for (cut in listOf(1, 3, SuAudioWire.HEADER, SuAudioWire.HEADER + 50)) {
            try {
                FrameReader(ByteArrayInputStream(bytes.copyOf(cut))).next()
                fail("cut at $cut")
            } catch (_: EOFException) {
            }
        }
    }

    @Test fun anOutOfStepLengthThrows() {
        val bad = byteArrayOf(1, 0x7f, 0, 0, 0) // 2 GiB: not a frame
        try {
            FrameReader(ByteArrayInputStream(bad)).next()
            fail()
        } catch (e: IOException) {
            assertTrue(e !is EOFException)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun noStreamIdAbove255() {
        FrameWriter(ByteArrayOutputStream()).write(256, ByteArray(1))
    }

    @Test fun commandsRoundTrip() {
        val policy = Command.Policy(1, 48_000, 2, listOf(1, 14, 0))
        assertEquals("policy 1 48000 2 1,14,0", policy.line())
        assertEquals(policy, Command.parse(policy.line()))
        assertEquals(Command.Policy(2, 16_000, 1, listOf(12, 16)), Command.parse("policy 2 16000 1 12,16\n"))
        assertEquals(Command.Stop(7), Command.parse(Command.Stop(7).line()))
    }

    @Test fun malformedCommandsAreNull() {
        listOf(
            "", "policy", "policy 1 48000 2", "policy 0 48000 2 1", "policy 256 48000 2 1", "policy 1 48000 3 1",
            "policy 1 0 2 1", "policy 1 48000 2 1,x", "policy 1 48000 2 1 extra", "stop", "stop x", "stop 1 2", "start 1",
        ).forEach { assertNull(it, Command.parse(it)) }
    }

    @Test fun repliesRoundTrip() {
        listOf(Reply.Started(1), Reply.Ended(255), Reply.Failed(2, "SecurityException: no MODIFY_AUDIO_ROUTING for uid 2000"))
            .forEach { assertEquals(it, Reply.parse(it.encode())) }
        assertEquals(Reply.Failed(3, "no reason given"), Reply.parse("failed 3".toByteArray()))
        assertNull(Reply.parse("started".toByteArray()))
        assertNull(Reply.parse("hello 1".toByteArray()))
    }

    @Test fun ringKeepsOrderAcrossItsEnd() {
        val ring = PcmRing(8, frameBytes = 2)
        val out = ByteArray(8)
        ring.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(4, ring.read(out, 0, 4, 0))
        ring.write(byteArrayOf(7, 8, 9, 10, 11, 12)) // wraps round
        assertEquals(8, ring.read(out, 0, 8, 0))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12), out)
        assertEquals(0L, ring.droppedBytes)
    }

    @Test fun aFullRingDropsTheOldestInWholeFrames() {
        val ring = PcmRing(8, frameBytes = 4)
        ring.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        ring.write(byteArrayOf(7, 8, 9)) // one byte over: a whole frame of 4 goes
        assertEquals(4L, ring.droppedBytes)
        val out = ByteArray(8)
        assertEquals(5, ring.read(out, 0, 8, 0))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9), out.copyOf(5))
        // More than the ring at once: the newest of it stays, still in step.
        ring.write(ByteArray(13) { (it + 20).toByte() })
        assertEquals(4L + 8, ring.droppedBytes)
        assertEquals(5, ring.read(out, 0, 8, 0))
        assertArrayEquals(byteArrayOf(28, 29, 30, 31, 32), out.copyOf(5))
    }

    @Test fun anEmptyRingWaitsThenSaysNothingYet() {
        val ring = PcmRing(16, frameBytes = 2)
        val t0 = System.nanoTime()
        assertEquals(0, ring.read(ByteArray(4), 0, 4, 50))
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 45)
    }

    @Test fun aReaderWakesWhenSoundComes() {
        val ring = PcmRing(16, frameBytes = 2)
        Thread { Thread.sleep(50); ring.write(byteArrayOf(1, 2)) }.start()
        assertEquals(2, ring.read(ByteArray(4), 0, 4, 5_000))
    }

    @Test fun aClosedRingDrainsThenEnds() {
        val ring = PcmRing(16, frameBytes = 2)
        ring.write(byteArrayOf(1, 2))
        ring.close()
        ring.write(byteArrayOf(3, 4)) // too late
        val out = ByteArray(4)
        assertEquals(2, ring.read(out, 0, 4, 0))
        assertEquals(-1, ring.read(out, 0, 4, 1_000))
    }

    @Test fun streamsGetTheirOwnFrames() {
        val streams = SuStreams()
        val music = streams.open(frameBytes = 4, capacityBytes = 64)!!
        val voice = streams.open(frameBytes = 2, capacityBytes = 64)!!
        assertNotEquals(music.id, voice.id)
        streams.dispatch(Frame(0, Reply.Started(music.id).encode()))
        streams.dispatch(Frame(0, Reply.Failed(voice.id, "refused").encode()))
        assertNull(music.awaitStart(0))
        assertEquals("refused", voice.awaitStart(0))
        streams.dispatch(Frame(music.id, byteArrayOf(1, 2, 3, 4)))
        streams.dispatch(Frame(voice.id, byteArrayOf(9, 9))) // failed: ended, nothing kept
        streams.dispatch(Frame(99, byteArrayOf(5))) // nobody's: dropped
        val out = ByteArray(8)
        assertEquals(4, music.pcm.read(out, 0, 8, 0))
        assertEquals(-1, voice.pcm.read(out, 0, 8, 0))
        // The helper says the stream ended by itself: its reader sees the end.
        streams.dispatch(Frame(0, Reply.Ended(music.id).encode()))
        assertEquals(-1, music.pcm.read(out, 0, 8, 0))
    }

    @Test fun aStreamStoppedByAareIgnoresLateFrames() {
        val streams = SuStreams()
        val s = streams.open(2, 64)!!
        streams.remove(s.id)
        streams.dispatch(Frame(s.id, byteArrayOf(1, 2)))
        assertEquals(-1, s.pcm.read(ByteArray(2), 0, 2, 0))
        // Its id isn't handed out again straight away (a late reply can't hit the next stream).
        assertNotEquals(s.id, streams.open(2, 64)!!.id)
    }

    @Test fun theHelperEndingEndsEveryStreamAndPendingStart() {
        val streams = SuStreams()
        val a = streams.open(2, 64)!!
        val b = streams.open(2, 64)!!
        streams.dispatch(Frame(0, Reply.Started(a.id).encode()))
        streams.endAll("the root helper ended: su: permission denied")
        assertEquals(-1, a.pcm.read(ByteArray(2), 0, 2, 0))
        assertEquals("the root helper ended: su: permission denied", b.awaitStart(0))
        assertNull(streams.open(2, 64))
    }

    @Test fun noAnswerTimesOut() {
        val s = SuStreams().open(2, 64)!!
        assertTrue(s.awaitStart(10)!!.startsWith("no answer"))
    }

    @Test fun idsWrapRoundAndRunOut() {
        val streams = SuStreams()
        val ids = (1..SuAudioWire.MAX_ID).map { streams.open(2, 8)!!.id }
        assertEquals((1..SuAudioWire.MAX_ID).toList(), ids)
        assertNull(streams.open(2, 8))
        streams.remove(42)
        assertEquals(42, streams.open(2, 8)!!.id)
    }

    /** The whole path: frames of two streams through a pipe-like trickle, into their rings. */
    @Test fun twoStreamsThroughOnePipe() {
        val streams = SuStreams()
        val music = streams.open(4, 1 shl 16)!!
        val voice = streams.open(2, 1 shl 16)!!
        val musicPcm = ByteArray(3840) { it.toByte() }
        val voicePcm = ByteArray(640) { (255 - it).toByte() }
        val bytes = frames(
            0 to Reply.Started(music.id).encode(), 0 to Reply.Started(voice.id).encode(),
            music.id to musicPcm.copyOfRange(0, 1001), voice.id to voicePcm, music.id to musicPcm.copyOfRange(1001, 3840),
            0 to Reply.Ended(voice.id).encode(),
        )
        val reader = FrameReader(Trickle(bytes, 333))
        while (true) streams.dispatch(reader.next() ?: break)
        assertNull(music.awaitStart(0))
        val got = ByteArray(4000)
        assertEquals(3840, music.pcm.read(got, 0, got.size, 0))
        assertArrayEquals(musicPcm, got.copyOf(3840))
        assertEquals(640, voice.pcm.read(got, 0, got.size, 0))
        assertArrayEquals(voicePcm, got.copyOf(640))
        assertEquals(-1, voice.pcm.read(got, 0, got.size, 0))
    }
}
