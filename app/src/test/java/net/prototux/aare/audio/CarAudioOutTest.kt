package net.prototux.aare.audio

import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone-to-car audio pump, against a fake car channel and a scripted capture. 16 kHz mono:
 * one message is 1024 samples, 64 ms, so the lead before sending (200 ms) is 4 messages, the live
 * backlog for music (240 ms) 4, the sound kept while waiting for focus (1 s) 16.
 */
class CarAudioOutTest {
    private val chunk = CarAudioOut.chunkBytes(16_000, 1)
    private val chunkMs = 64L

    /** One scripted read: sound marked with its index, silence, the capture's end, or nothing more yet. */
    private sealed interface Step
    private data class Sound(val index: Int) : Step
    private data object Quiet : Step
    private data object End : Step

    private class FakeFocus(granted: Boolean) : CarAudioOut.Focus {
        @Volatile override var granted = granted
        val requests: MutableList<Boolean> = java.util.Collections.synchronizedList(mutableListOf())
        override fun request(playing: Boolean) { requests += playing }
    }

    /** The car's channel: records what reaches it, always with room. */
    private class FakeSink(override val streamType: AudioStreamType) : CarAudioSink {
        override val config: AudioConfiguration = AudioConfiguration.newBuilder()
            .setSamplingRate(16_000).setNumberOfBits(16).setNumberOfChannels(1).build()
        @Volatile override var started = false
        /** A stalled link: the car's ack window stays full, and what's sent meanwhile is lost. */
        @Volatile var stalled = false
        override val hasRoom get() = started && !stalled
        /** "start", "stop", or the index of the sound sent ("-" for silence), in order. */
        val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val timestamps: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())
        override fun startPlayback() { started = true; events += "start" }
        override fun stopPlayback() { started = false; events += "stop" }
        override fun sendPcm(buf: ByteArray, offset: Int, length: Int, timestampUs: Long): Boolean {
            if (stalled) return false
            events +=  if (buf[offset] == 0.toByte()) "-" else (buf[offset + 2].toInt() and 0xff).toString()
            timestamps += timestampUs
            return true
        }
        val sent: List<Int> get() = synchronized(events) { events.mapNotNull { it.toIntOrNull() } }
        override val ackless = false
        override val dropsOverflow = false
        override val underflows = 0
        override val droppedChunks = 0L
        override val streamSent = 0
        override val streamAcks = 0
        override val maxUnacked = 1
        override val maxAckGapMs = 0L
    }

    /** Plays [steps], one whole message per read, the clock moving one message on each. [before] runs ahead of step i. */
    private inner class ScriptedSource(
        private val steps: List<Step>,
        private val clock: AtomicLong,
        private val starts: Boolean = true,
        private val before: (Int) -> Unit = {},
    ) : PcmSource {
        private var at = 0
        val played = CountDownLatch(1)
        val released = AtomicInteger()
        override fun start() = starts
        override fun read(buf: ByteArray, offset: Int, length: Int): Int {
            if (at == steps.size) { played.countDown(); Thread.sleep(1); return 0 }
            before(at)
            val step = steps[at++]
            if (step == End) { played.countDown(); return -1 }
            clock.addAndGet(chunkMs)
            val n = minOf(length, chunk)
            when (step) {
                // Every sample loud; the third byte tells the chunks apart (isSilent reads every 4th sample).
                is Sound -> { buf.fill(0x40, offset, offset + n); buf[offset + 2] = step.index.toByte() }
                else -> buf.fill(0, offset, offset + n)
            }
            return n
        }
        override fun release() { released.incrementAndGet() }
    }

    private class Run(val sink: FakeSink, val focus: FakeFocus, val failures: AtomicInteger, val out: CarAudioOut)

    private fun run(
        steps: List<Step>,
        stream: AudioStreamType = AudioStreamType.AUDIO_STREAM_MEDIA,
        granted: Boolean = true,
        starts: Boolean = true,
        sink: FakeSink = FakeSink(stream),
        before: (FakeFocus, Int) -> Unit = { _, _ -> },
    ): Pair<Run, ScriptedSource> {
        val clock = AtomicLong(100_000)
        val focus = FakeFocus(granted)
        val failures = AtomicInteger()
        val failed = CountDownLatch(1)
        val source = ScriptedSource(steps, clock, starts) { before(focus, it) }
        val out = CarAudioOut(source, sink, focus, onFailed = { failures.incrementAndGet(); failed.countDown() },
            clock = { clock.get() }, nanoClock = { clock.get() * 1_000_000 })
        out.start()
        assertTrue("the capture was read to its end", source.played.await(5, TimeUnit.SECONDS) || failed.await(5, TimeUnit.SECONDS))
        if (steps.lastOrNull() == End || !starts) failed.await(5, TimeUnit.SECONDS)
        out.stop()
        return Run(sink, focus, failures, out) to source
    }

    private fun sounds(range: IntRange) = range.map { Sound(it) }

    @Test fun buildsALeadThenSendsEverythingInOrder() {
        val (r, _) = run(sounds(0..9))
        assertEquals((0..9).toList(), r.sink.sent)
        // Nothing reaches the car before the lead (4 messages) is there.
        assertEquals(listOf("start", "0", "1", "2", "3"), r.sink.events.take(5))
        val ts = r.sink.timestamps
        assertTrue("one message apart", ts.zipWithNext().all { (a, b) -> b - a == chunk / 2 * 1_000_000L / 16_000 })
        // Stopped while playing: the car's stream closes and the focus goes back.
        assertEquals("stop", r.sink.events.last())
        assertEquals(listOf(true, false), r.focus.requests)
        assertEquals(0, r.failures.get())
    }

    @Test fun musicWaitingForFocusKeepsOnlyARecentStart() {
        // The car grants focus at message 10: music keeps the live backlog (4), not 10 messages behind.
        val (r, _) = run(sounds(0..19), granted = false) { f, i -> if (i == 10) f.granted = true }
        assertEquals((6..19).toList(), r.sink.sent)
    }

    @Test fun aPromptWaitingForFocusKeepsItsWholeStart() {
        val (r, _) = run(sounds(0..19), stream = AudioStreamType.AUDIO_STREAM_GUIDANCE, granted = false) { f, i -> if (i == 10) f.granted = true }
        assertEquals((0..19).toList(), r.sink.sent)
    }

    @Test fun musicThroughAStalledLinkComesLateButWhole() {
        // The link stalls from message 6 to 15 (640 ms), then catches up: without the bigger hold
        // the live backlog (4) would lose 6 of them.
        val sink = FakeSink(AudioStreamType.AUDIO_STREAM_MEDIA)
        val (r, _) = run(sounds(0..29), sink = sink) { _, i -> sink.stalled = i in 6..15 }
        assertEquals((0..29).toList(), r.sink.sent)
    }

    @Test fun threeSecondsOfSilenceCloseTheStream() {
        // 50 quiet messages are 3.2 s.
        val (r, _) = run(sounds(0..4) + List(50) { Quiet })
        assertEquals(listOf(true, false), r.focus.requests)
        assertEquals(1, r.sink.events.count { it == "stop" })
        assertFalse(r.sink.started)
    }

    @Test fun losingFocusMidStreamClosesItAndOpensANewOneWithAFreshLead() {
        // Focus lost at message 6, back at 9.
        val (r, _) = run(sounds(0..14)) { f, i -> if (i == 6) f.granted = false; if (i == 9) f.granted = true }
        assertEquals(2, r.sink.events.count { it == "start" })
        assertFalse("the message that found focus gone isn't sent", 6 in r.sink.sent)
        assertEquals(listOf(7, 8, 9, 10), r.sink.sent.dropWhile { it != 7 }.take(4))
    }

    @Test fun aCaptureThatEndsByItselfReportsAFailure() {
        val (r, source) = run(sounds(0..5) + End)
        assertEquals(1, r.failures.get())
        assertEquals(1, source.released.get())
        assertEquals(listOf(true, false), r.focus.requests)
    }

    @Test fun stoppingIsNotAFailure() {
        val (r, source) = run(sounds(0..5))
        assertEquals(0, r.failures.get())
        assertEquals(1, source.released.get())
    }

    @Test fun aCaptureThatCannotStartReportsAFailure() {
        val (r, source) = run(sounds(0..5), starts = false)
        assertEquals(1, r.failures.get())
        assertEquals(1, source.released.get())
        assertTrue(r.sink.events.isEmpty())
    }
}
