package net.prototux.aare.aap.pcm

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * PCM that arrives in bursts (a whole synthesized prompt at once) handed out at the pace it plays.
 *
 * The car audio loop reads it like a microphone: [read] blocks until the requested length is due
 * in real time, and fills with silence when nothing is queued. Pacing matters because the loop
 * closes the car's stream after a stretch of silence measured on the wall clock: a prompt
 * delivered faster than real time would look finished, and get cut, while the car still plays it.
 * Reads may run up to [leadNanos] ahead of real time, so the car's buffer stays fed.
 *
 * [nanoTime] and [sleepNanos] are injectable for tests.
 */
class PacedPcmQueue(
    val format: PcmFormat,
    private val leadNanos: Long = 40_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepNanos: (Long) -> Unit = { Thread.sleep(it / 1_000_000, (it % 1_000_000).toInt()) },
) {
    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    /** Read position in the first chunk. */
    private var offset = 0
    private var pending = 0
    /** When the next byte read is due (nanoTime), or null before the first read. */
    private var due: Long? = null
    @Volatile private var closed = false

    /** Bytes queued and not read yet. */
    val pendingBytes: Int get() = lock.withLock { pending }

    /** Queues [pcm] (whole frames of [format]) to be read after what's already queued. */
    fun push(pcm: ByteArray) {
        val whole = pcm.size - pcm.size % format.bytesPerFrame
        if (whole <= 0 || closed) return
        lock.withLock {
            chunks.addLast(if (whole == pcm.size) pcm else pcm.copyOf(whole))
            pending += whole
        }
    }

    /** Drops everything queued (the prompt was stopped). */
    fun flush() = lock.withLock {
        chunks.clear(); offset = 0; pending = 0
        drained.signalAll()
    }

    /** No more reads: [read] returns -1, and waiting in [awaitDrained] ends. */
    fun close() {
        closed = true
        flush()
    }

    /**
     * Waits until everything queued has been read, [timeoutNanos] has passed, or [cancelled] says
     * to stop. True if drained.
     */
    fun awaitDrained(timeoutNanos: Long, cancelled: () -> Boolean = { false }): Boolean {
        val end = nanoTime() + timeoutNanos
        lock.withLock {
            while (pending > 0 && !closed && !cancelled()) {
                val left = end - nanoTime()
                if (left <= 0) return false
                // Wake up now and then to check [cancelled].
                drained.awaitNanos(minOf(left, 50_000_000L))
            }
            return pending == 0
        }
    }

    /**
     * Fills [length] bytes of [buf] from [offset] with queued PCM, then silence, once that much is
     * due in real time. Returns [length], or -1 once closed.
     */
    fun read(buf: ByteArray, offset: Int, length: Int): Int {
        if (closed) return -1
        val len = length - length % format.bytesPerFrame
        if (len <= 0) return 0
        val now = nanoTime()
        // First read, or the reader stalled for a while: restart the clock rather than catch up in a burst.
        val start = due?.takeIf { now - it < MAX_LATE_NANOS } ?: now
        val wait = start - leadNanos - now
        if (wait > 0) sleepNanos(wait)
        if (closed) return -1
        due = start + format.nanosFor(len)
        lock.withLock {
            var at = offset
            var left = len
            while (left > 0 && chunks.isNotEmpty()) {
                val c = chunks.first()
                val n = minOf(left, c.size - this.offset)
                System.arraycopy(c, this.offset, buf, at, n)
                at += n; left -= n; this.offset += n; pending -= n
                if (this.offset == c.size) { chunks.removeFirst(); this.offset = 0 }
            }
            if (left > 0) buf.fill(0, at, at + left)
            if (pending == 0) drained.signalAll()
        }
        return len
    }

    private companion object {
        /** A reader this far behind is restarted on the current time. */
        const val MAX_LATE_NANOS = 200_000_000L
    }
}
