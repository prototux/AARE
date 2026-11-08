package net.prototux.aare.audio

import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * Entry point run as root by [SuSource], the way scrcpy and Shizuku run a helper: AARE launches
 *   `su -c "CLASSPATH=<apk> app_process / net.prototux.aare.audio.SuAudioMain <rate> <channels>"`
 * and reads the phone's audio as raw 16-bit PCM from this process's stdout.
 *
 * It reuses [ShellAudioService] (the remote-submix capture, which drops the root process to the
 * shell uid so CAPTURE_AUDIO_OUTPUT is granted), so there is no separate root capture to maintain.
 * No minification in release (optimization disabled), so the class survives under this exact name.
 */
object SuAudioMain {
    @JvmStatic
    fun main(args: Array<String>) {
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
}
