package net.prototux.aare.audio

import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import kotlin.system.exitProcess

/**
 * Entry point run as root by [SuSource] and [SuPolicySource], the way scrcpy and Shizuku run a
 * helper: AARE launches `su -c "CLASSPATH=<apk> app_process / net.prototux.aare.audio.SuAudioMain <args>"`.
 * - `<rate> <channels>`: the phone's whole output, as raw 16-bit PCM on stdout ([SuSource]).
 * - [MUX]: audio policy streams, music and navigation voice apart, as [SuPolicySource] asks for
 *   them; framed on stdout, commands on stdin ([SuAudioWire]).
 *
 * Both reuse [ShellAudioService] (the remote-submix capture and the audio policy of the Shizuku
 * path), dropped to the shell uid (see [dropRootToShell]), so there is no separate root capture
 * to maintain. No minification in release (optimization disabled), so the class survives under
 * this exact name.
 */
object SuAudioMain {
    const val MUX = "mux"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == MUX) mux() else wholeOutput(args)
    }

    private fun wholeOutput(args: Array<String>) {
        val rate = args.getOrNull(0)?.toIntOrNull() ?: 48_000
        val channels = args.getOrNull(1)?.toIntOrNull() ?: 2
        val service = ShellAudioService(null)
        val pfd = service.start(rate, channels) ?: exitProcess(1)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val out = FileOutputStream(FileDescriptor.out)
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
        } catch (_: Exception) {
            // AARE stopped reading (closed its end of the pipe): we're done.
        } finally {
            service.stop()
        }
        exitProcess(0)
    }

    /** Serves [SuAudioWire] commands until stdin ends; any failed write to AARE ends it too. */
    private fun mux() {
        // Before anything else: the policy's recorder needs the shell's CAPTURE_AUDIO_OUTPUT.
        val dropFailed = runCatching { dropRootToShell() }.exceptionOrNull()?.let { "could not drop to the shell uid: ${describe(it)}" }
        val service = ShellAudioService(null)
        val out = FrameWriter(FileOutputStream(FileDescriptor.out))
        // AARE stopped reading, or went away: stop recording (the phone gets its sound back) and end.
        fun send(write: () -> Unit) = try { write() } catch (_: IOException) { service.destroy() }
        try {
            System.`in`.bufferedReader(Charsets.US_ASCII).forEachLine { line ->
                when (val c = Command.parse(line)) {
                    is Command.Policy -> {
                        if (dropFailed != null) send { out.reply(Reply.Failed(c.id, dropFailed)) }
                        else startPolicy(service, out, c, ::send)
                    }
                    is Command.Stop -> service.stopPolicy(c.id)
                    null -> System.err.println("unknown command: $line")
                }
            }
        } catch (_: IOException) {
            // stdin broke off: as good as ended
        }
        service.destroy() // exits
    }

    private fun startPolicy(service: ShellAudioService, out: FrameWriter, c: Command.Policy, send: (() -> Unit) -> Unit) {
        val pfd = try {
            service.openPolicy(c.id, c.rate, c.channels, c.usages.toIntArray(), c.volume)
        } catch (e: Throwable) {
            System.err.println("policy ${c.id} refused: ${describe(e)}")
            send { out.reply(Reply.Failed(c.id, describe(e))) }
            return
        }
        send { out.reply(Reply.Started(c.id)) }
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            // 20 ms of sound, what the policy's pump writes at a time.
            val buf = ByteArray(c.rate / 50 * c.channels * 2)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) send { out.write(c.id, buf, 0, n) }
                    }
                }
            } catch (_: IOException) {
                // The policy's pipe broke off: the stream ended.
            }
            // Stopped (by AARE, or by itself): tell AARE, which ignores it for a stream it stopped.
            send { out.reply(Reply.Ended(c.id)) }
        }, "aare-su-policy-${c.id}").start()
    }

    /** The root cause of [e], for the connection log: reflection wraps the system's refusal. */
    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message}"
    }
}
