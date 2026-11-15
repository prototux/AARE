package net.prototux.aare.headunit

import net.prototux.aare.aap.channels.Aac
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Plays an AAC-LC stream from the phone (--aac): the head unit's music sink asks for AAC like the
 * units in #82, and this turns what the phone sends back into PCM for the speakers through ffmpeg,
 * which must be on the PATH. ffmpeg also says on the console if a frame doesn't decode, which is
 * the point: a stream it plays cleanly is one a real AAC head unit can decode.
 *
 * Raw frames ([adts] false) get the ADTS header written here, from the AudioSpecificConfig the
 * phone sent with CODEC_CONFIG; ADTS frames go through as they are. With [dump], the ADTS stream
 * is also saved there (one file per stream, numbered), to listen to or check with ffprobe.
 */
class AacPlayback(
    private val rate: Int,
    private val channels: Int,
    private val adts: Boolean,
    private val dump: File?,
    /** Decoded 16-bit PCM, on ffmpeg's reader thread. */
    private val pcm: (ByteArray, Int, Int) -> Unit,
) {
    private var ffmpeg: Process? = null
    private var input: OutputStream? = null
    private var file: OutputStream? = null
    private var asc: ByteArray? = null
    private var frames = 0
    private var streams = 0

    @Synchronized fun start() {
        stop()
        frames = 0
        asc = null
        streams++
        val p = try {
            ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-fflags", "nobuffer", "-probesize", "32",
                "-analyzeduration", "0", "-f", "aac", "-i", "pipe:0", "-f", "s16le", "-ar", "$rate", "-ac", "$channels", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        } catch (e: Exception) {
            System.err.println("aac: can't start ffmpeg (${e.message}); is it on the PATH?")
            return
        }
        ffmpeg = p
        input = p.outputStream
        file = dump?.let { d -> runCatching { FileOutputStream(File(d.parentFile, "${d.nameWithoutExtension}-$streams.aac")) }.getOrNull() }
        Thread({
            val buf = ByteArray(4096)
            val out = p.inputStream
            while (true) {
                val n = runCatching { out.read(buf) }.getOrDefault(-1)
                if (n < 0) break
                pcm(buf, 0, n)
            }
        }, "aac-pcm").apply { isDaemon = true; start() }
        println("aac: stream $streams started (${if (adts) "ADTS" else "raw AAC-LC"}, $rate Hz, $channels ch)")
    }

    /** The AudioSpecificConfig the phone sends after START (raw AAC-LC only). */
    @Synchronized fun codecConfig(data: ByteArray, offset: Int, length: Int) {
        asc = data.copyOfRange(offset, offset + length)
        val hex = asc!!.joinToString("") { "%02x".format(it) }
        val want = Aac.audioSpecificConfig(rate, channels).joinToString("") { "%02x".format(it) }
        println("aac: AudioSpecificConfig $hex" + if (hex == want) " (AAC-LC, $rate Hz, $channels ch)" else " (expected $want!)")
    }

    @Synchronized fun frame(data: ByteArray, offset: Int, length: Int) {
        val sink = input ?: return
        frames++
        val out: ByteArray
        if (adts) {
            if (frames == 1) println("aac: first frame starts ${"%02x%02x".format(data[offset], data[offset + 1])}" +
                if (data[offset] == 0xFF.toByte() && (data[offset + 1].toInt() and 0xF6) == 0xF0) " (ADTS sync)" else " (no ADTS sync!)")
            out = data.copyOfRange(offset, offset + length)
        } else {
            if (frames == 1 && asc == null) println("aac: first frame came before any AudioSpecificConfig!")
            out = ByteArray(Aac.ADTS_HEADER + length)
            Aac.writeAdtsHeader(out, 0, rate, channels, length)
            System.arraycopy(data, offset, out, Aac.ADTS_HEADER, length)
        }
        runCatching { sink.write(out); sink.flush() }.onFailure { System.err.println("aac: ffmpeg went away: ${it.message}"); input = null }
        runCatching { file?.write(out) }
    }

    @Synchronized fun stop() {
        if (ffmpeg != null) println("aac: stream $streams stopped after $frames frames")
        runCatching { input?.close() }
        runCatching { file?.close() }
        ffmpeg?.let { p -> Thread { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroy() }.start() }
        ffmpeg = null; input = null; file = null
    }
}
