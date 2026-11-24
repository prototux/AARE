package net.prototux.aare.audio

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import net.prototux.aare.BuildConfig
import net.prototux.aare.DiagLog

/**
 * The one root audio helper for this process ([SuAudioMain] in `mux` mode, started with `su`),
 * shared by the music and navigation channels: each has its own stream in it, and the helper
 * stops with the last. The root counterpart of [ShellAudio]; the wire is [SuAudioWire].
 */
internal object SuAudio {
    private val lock = Any()
    private var helper: Helper? = null

    /** One helper process: its command line (stdin) and the thread reading its frames (stdout). */
    class Helper(private val process: Process) {
        val streams = SuStreams()
        private val commands: OutputStream = process.outputStream
        @Volatile var alive = true
            private set
        /** Users holding this helper (guarded by [lock]). */
        var users = 0
        /** The helper's last line on stderr: su's own refusal, say, for the connection log. */
        @Volatile private var lastError: String? = null

        init {
            Thread({ readFrames() }, "aare-su-audio").apply { isDaemon = true; start() }
            // Drain stderr so a chatty app_process can't fill its pipe and stall the helper.
            Thread({
                runCatching {
                    process.errorStream.bufferedReader().forEachLine {
                        lastError = it
                        if (BuildConfig.DEBUG) Log.d(TAG, "su: $it")
                    }
                }
            }, "aare-su-audio-err").apply { isDaemon = true; start() }
        }

        private fun readFrames() {
            // The helper's pipe holds ~64 KiB: a late wake-up here delays both streams.
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
            val why = try {
                val reader = FrameReader(BufferedInputStream(process.inputStream, 64 * 1024))
                while (true) streams.dispatch(reader.next() ?: break)
                "the root helper ended"
            } catch (e: IOException) {
                "the root helper's output broke off (${e.message})"
            }
            alive = false
            streams.endAll(lastError?.let { "$why: $it" } ?: why)
            close()
        }

        /** Sends [command]; false if the helper is gone. Called from the audio threads. */
        fun send(command: Command): Boolean = synchronized(commands) {
            runCatching {
                commands.write((command.line() + "\n").toByteArray(Charsets.US_ASCII))
                commands.flush()
            }.isSuccess
        }

        /**
         * Ends the helper. Closing its stdin is what tells it (su may hand the pipes to a daemon,
         * so killing the su process alone could leave the helper running); destroy() then ends
         * the su shell, and closing stdout makes any write of the helper fail.
         */
        fun close() {
            alive = false
            synchronized(commands) { runCatching { commands.close() } }
            runCatching { process.destroy() }
            runCatching { process.inputStream.close() }
        }
    }

    /** The helper, started if needed; null if su couldn't be started. Pair with [release]. */
    fun acquire(context: Context): Helper? = synchronized(lock) {
        val h = helper?.takeIf { it.alive } ?: start(context)?.also { helper = it } ?: return null
        h.users++
        h
    }

    fun release(h: Helper) = synchronized(lock) {
        if (--h.users > 0) return@synchronized
        h.close()
        if (helper === h) helper = null
    }

    private fun start(context: Context): Helper? {
        val apk = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH='$apk' app_process / ${SuAudioMain::class.java.name} ${SuAudioMain.MUX}"
        val p = runCatching { ProcessBuilder("su", "-c", cmd).start() }
            .onFailure { Log.w(TAG, "su launch", it) }.getOrNull() ?: return null
        return Helper(p)
    }

    private const val TAG = "AareSuAudio"
}

/**
 * [usages] only, through the same audio policy as [ShizukuPolicySource], registered by the root
 * helper ([SuAudioMain], dropped to the shell uid): music and navigation voice to their own car
 * channels on a rooted phone, with neither the Shizuku app nor the Magisk module.
 */
class SuPolicySource(
    private val context: Context,
    private val usages: IntArray,
    private val rate: Int,
    private val channels: Int,
    private val volume: Int = AudioPolicyCapture.VOLUME_AS_IS,
) : PcmSource {
    private var helper: SuAudio.Helper? = null
    private var stream: SuStreams.Stream? = null

    override fun start(): Boolean {
        val h = SuAudio.acquire(context) ?: run {
            DiagLog.add("car audio: root audio routing: su could not be started")
            return false
        }
        helper = h
        // A second of sound in hand: plenty for a late car audio thread, little to replay.
        val s = h.streams.open(frameBytes = channels * 2, capacityBytes = rate * channels * 2)
        if (s == null) { release(); return false }
        stream = s
        val why = if (h.send(Command.Policy(s.id, rate, channels, usages.toList(), volume))) s.awaitStart(START_TIMEOUT_MS)
            else "the root helper is gone"
        if (why != null) {
            DiagLog.add("car audio: root audio routing refused ($why)")
            release()
            return false
        }
        return true
    }

    // Wakes every 100 ms without sound, so the car audio thread can notice it's being stopped.
    override fun read(buf: ByteArray, offset: Int, length: Int): Int = stream?.pcm?.read(buf, offset, length, 100) ?: -1

    override fun release() {
        val h = helper ?: return
        helper = null
        stream?.let { s ->
            h.send(Command.Stop(s.id))
            h.streams.remove(s.id)
            if (s.pcm.droppedBytes > 0) Log.w(TAG, "root stream ${s.id}: ${s.pcm.droppedBytes} bytes dropped (read too late)")
        }
        stream = null
        SuAudio.release(h)
    }

    private companion object {
        const val TAG = "AareSuAudio"
        /** Covers starting su and app_process, and the policy itself. */
        const val START_TIMEOUT_MS = 15_000L
    }
}
