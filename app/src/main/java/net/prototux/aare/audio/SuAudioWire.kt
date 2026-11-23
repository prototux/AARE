package net.prototux.aare.audio

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * How AARE and its root audio helper ([SuAudioMain] in `mux` mode) talk, over the helper's
 * stdin and stdout: several PCM streams (music and navigation voice, each with its own rate and
 * channels) on the one stdout pipe that `su` gives us, plus a command channel the other way.
 *
 * **AARE to the helper (stdin):** one ASCII command per line ([Command]):
 * - `policy <id> <rate> <channels> <usage>[,<usage>…]`: start stream `id`, an audio policy taking
 *   these usages away from the phone's speaker;
 * - `stop <id>`: stop it.
 *
 * End of stdin (AARE closed it, or died) stops every stream and ends the helper.
 *
 * **The helper to AARE (stdout):** frames ([FrameWriter], [FrameReader]):
 * `[stream id: 1 byte][payload length: 4 bytes, big-endian][payload]`.
 * - ids 1..255: 16-bit little-endian PCM of that stream, in the rate and channels it was asked
 *   for. Payloads needn't hold whole sample frames: a stream is the concatenation of its payloads.
 * - id 0 ([CONTROL]): a [Reply] in UTF-8 text: `started <id>`, `failed <id> <reason>`, `ended <id>`.
 *
 * A write that fails means AARE stopped reading: the helper then ends too, so no root process is
 * left recording (and keeping the phone silent) with nobody listening. Payloads above
 * [MAX_PAYLOAD] are refused on both sides: a length that big means the stream is out of step.
 */
internal object SuAudioWire {
    const val CONTROL = 0
    const val MAX_ID = 255
    const val MAX_PAYLOAD = 1 shl 20
    const val HEADER = 5
}

/** A frame as read: [data] is [SuAudioWire.CONTROL] text or PCM of stream [id]. */
internal class Frame(val id: Int, val data: ByteArray)

/** Writes frames to [out]; safe from several threads (each frame goes out whole). */
internal class FrameWriter(private val out: OutputStream) {
    @Synchronized
    fun write(id: Int, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        require(id in 0..SuAudioWire.MAX_ID) { "stream id $id" }
        require(length in 0..SuAudioWire.MAX_PAYLOAD) { "payload of $length bytes" }
        // Header and payload in one write: fewer system calls on the audio path.
        val frame = ByteArray(SuAudioWire.HEADER + length)
        frame[0] = id.toByte()
        frame[1] = (length ushr 24).toByte()
        frame[2] = (length ushr 16).toByte()
        frame[3] = (length ushr 8).toByte()
        frame[4] = length.toByte()
        System.arraycopy(data, offset, frame, SuAudioWire.HEADER, length)
        out.write(frame)
        out.flush()
    }

    fun reply(reply: Reply) = write(SuAudioWire.CONTROL, reply.encode())
}

/** Reads frames from [input], however the pipe splits them. Not thread-safe: one reader. */
internal class FrameReader(private val input: InputStream) {
    /**
     * The next frame, or null when the stream ended cleanly (between two frames). Throws
     * [EOFException] if it ended inside a frame, [IOException] on a length out of range.
     */
    fun next(): Frame? {
        val id = input.read()
        if (id < 0) return null
        val header = ByteArray(4)
        readFully(header)
        val length = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (length < 0 || length > SuAudioWire.MAX_PAYLOAD) throw IOException("frame of $length bytes: out of step")
        val data = ByteArray(length)
        readFully(data)
        return Frame(id, data)
    }

    private fun readFully(buf: ByteArray) {
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n < 0) throw EOFException("stream ended inside a frame")
            got += n
        }
    }
}

/** A command line from AARE to the helper (see [SuAudioWire]). */
internal sealed class Command {
    abstract val id: Int
    abstract fun line(): String

    data class Policy(override val id: Int, val rate: Int, val channels: Int, val usages: List<Int>) : Command() {
        override fun line() = "policy $id $rate $channels ${usages.joinToString(",")}"
    }

    data class Stop(override val id: Int) : Command() {
        override fun line() = "stop $id"
    }

    companion object {
        /** The command on [line], or null if it isn't a well-formed one. */
        fun parse(line: String): Command? {
            val f = line.trim().split(' ').filter { it.isNotEmpty() }
            val id = f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..SuAudioWire.MAX_ID } ?: return null
            return when (f[0]) {
                "policy" -> {
                    if (f.size != 5) return null
                    val rate = f[2].toIntOrNull()?.takeIf { it in 8_000..192_000 } ?: return null
                    val channels = f[3].toIntOrNull()?.takeIf { it == 1 || it == 2 } ?: return null
                    val usages = f[4].split(',').map { it.toIntOrNull() ?: return null }
                    Policy(id, rate, channels, usages)
                }
                "stop" -> if (f.size == 2) Stop(id) else null
                else -> null
            }
        }
    }
}

/** A control message from the helper to AARE (see [SuAudioWire]). */
internal sealed class Reply {
    abstract val id: Int
    abstract fun text(): String
    fun encode(): ByteArray = text().toByteArray(Charsets.UTF_8)

    data class Started(override val id: Int) : Reply() {
        override fun text() = "started $id"
    }

    /** The stream couldn't start: [reason] is for the connection log. */
    data class Failed(override val id: Int, val reason: String) : Reply() {
        override fun text() = "failed $id $reason"
    }

    /** The stream ended by itself (its recorder stopped). */
    data class Ended(override val id: Int) : Reply() {
        override fun text() = "ended $id"
    }

    companion object {
        fun parse(data: ByteArray): Reply? {
            val text = String(data, Charsets.UTF_8)
            val f = text.split(' ', limit = 3)
            val id = f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..SuAudioWire.MAX_ID } ?: return null
            return when (f[0]) {
                "started" -> Started(id)
                "failed" -> Failed(id, f.getOrNull(2).orEmpty().ifEmpty { "no reason given" })
                "ended" -> Ended(id)
                else -> null
            }
        }
    }
}

/**
 * A stream's PCM between the reader of the helper's stdout and the car's audio thread, so a
 * stream whose reader is slow or gone never holds up the other one. Writes never block: when
 * full, the oldest sound goes, in whole sample frames of [frameBytes] so what's left stays in step.
 */
internal class PcmRing(capacityBytes: Int, private val frameBytes: Int) {
    private val buf = ByteArray(maxOf(frameBytes, capacityBytes / frameBytes * frameBytes))
    private val lock = ReentrantLock()
    private val more = lock.newCondition()
    private var head = 0
    private var size = 0
    private var closed = false

    /** Bytes dropped so far because the reader fell behind. */
    @Volatile var droppedBytes = 0L
        private set

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size): Unit = lock.withLock {
        if (closed || length <= 0) return
        var off = offset
        var len = length
        val overflow = size + len - buf.size
        if (overflow > 0) {
            // Drop from the front of the stream (buffered first, then the oldest of the new).
            val drop = (overflow + frameBytes - 1) / frameBytes * frameBytes
            val fromBuffer = minOf(drop, size)
            head = (head + fromBuffer) % buf.size
            size -= fromBuffer
            off += drop - fromBuffer
            len -= drop - fromBuffer
            droppedBytes += drop
        }
        var tail = (head + size) % buf.size
        var left = len
        while (left > 0) {
            val n = minOf(left, buf.size - tail)
            System.arraycopy(data, off, buf, tail, n)
            off += n
            left -= n
            tail = (tail + n) % buf.size
        }
        size += len
        more.signalAll()
    }

    /**
     * Up to [length] bytes, waiting at most [timeoutMs] for some: 0 if none came (so the caller
     * can check whether to go on), -1 once closed and drained.
     */
    fun read(dst: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int = lock.withLock {
        var wait = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (size == 0 && !closed) {
            if (wait <= 0) return 0
            wait = more.awaitNanos(wait)
        }
        if (size == 0) return -1
        val n = minOf(length, size)
        val first = minOf(n, buf.size - head)
        System.arraycopy(buf, head, dst, offset, first)
        if (n > first) System.arraycopy(buf, 0, dst, offset + first, n - first)
        head = (head + n) % buf.size
        size -= n
        n
    }

    /** No more sound: the reader gets what's left, then -1. */
    fun close(): Unit = lock.withLock {
        closed = true
        more.signalAll()
    }
}

/**
 * AARE's side of the helper's streams: which id is whose, and where each frame goes. Pure (no
 * Android), so the routing is unit-tested; [SuAudio] adds the process and the threads.
 */
internal class SuStreams {
    class Stream(val id: Int, frameBytes: Int, capacityBytes: Int) {
        val pcm = PcmRing(capacityBytes, frameBytes)
        private val answered = CountDownLatch(1)
        @Volatile private var failure: String? = null

        fun onReply(reply: Reply) {
            when (reply) {
                is Reply.Started -> Unit
                is Reply.Failed -> end(reply.reason)
                is Reply.Ended -> end("the stream ended")
            }
            answered.countDown()
        }

        /** Ends the stream, telling a pending [awaitStart] [why]. */
        fun end(why: String) {
            if (failure == null) failure = why
            pcm.close()
            answered.countDown()
        }

        /** Null once the helper started the stream; otherwise why it didn't. */
        fun awaitStart(timeoutMs: Long): String? {
            if (!answered.await(timeoutMs, TimeUnit.MILLISECONDS)) return "no answer from the root helper in ${timeoutMs / 1000} s"
            return failure
        }
    }

    private val streams = HashMap<Int, Stream>()
    private var nextId = 1
    private var ended: String? = null

    /** A new stream with a free id; null if all are taken or the helper is gone. */
    @Synchronized
    fun open(frameBytes: Int, capacityBytes: Int): Stream? {
        if (ended != null) return null
        repeat(SuAudioWire.MAX_ID) {
            val id = nextId
            nextId = if (nextId == SuAudioWire.MAX_ID) 1 else nextId + 1
            if (id !in streams) return Stream(id, frameBytes, capacityBytes).also { streams[id] = it }
        }
        return null
    }

    @Synchronized
    fun remove(id: Int) {
        streams.remove(id)?.end("stopped")
    }

    /** Hands [frame] to its stream; frames of streams already stopped are dropped. */
    fun dispatch(frame: Frame) {
        if (frame.id == SuAudioWire.CONTROL) {
            val reply = Reply.parse(frame.data) ?: return
            val s = synchronized(this) { if (reply is Reply.Started) streams[reply.id] else streams.remove(reply.id) }
            s?.onReply(reply)
        } else {
            synchronized(this) { streams[frame.id] }?.pcm?.write(frame.data)
        }
    }

    /** The helper is gone: every stream ends, telling a pending start [why]. */
    fun endAll(why: String) {
        val all = synchronized(this) {
            ended = why
            streams.values.toList().also { streams.clear() }
        }
        all.forEach { it.end(why) }
    }
}
