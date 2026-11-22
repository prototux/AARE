package net.prototux.aare.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import net.prototux.aare.aap.channels.MicrophoneChannel
import net.prototux.aare.host.MicHandle

/**
 * Gives car apps a microphone: the car's own (through the AAP microphone channel) when the head
 * unit has one, the phone's otherwise. Apps always get 16 kHz mono 16-bit PCM, the format the
 * Car App Library promises.
 *
 * The car's mic can also feed the phone's own apps ([CarMicForApps], through [openForPhoneApps]):
 * both share it, so it opens with the first of them and closes with the last.
 */
class MicRouter(
    private val carMic: () -> MicrophoneChannel?,
    private val voiceSession: (Boolean) -> Unit,
) {
    private class Client(val sink: (ByteArray, Int, Int) -> Unit, val onStopped: () -> Unit)

    @Volatile private var client: Client? = null
    /** The car's mic was asked for (answer pending or open): closing must tell the car. */
    @Volatile private var carRequested = false
    private var phoneThread: Thread? = null
    @Volatile private var phoneRunning = false
    private var carRate = 16_000
    private var carChannels = 1
    /** The phone's apps hear the car's mic: its PCM as the car sends it, and the car's answer. */
    private class Tap(val sink: (ByteArray, Int, Int) -> Unit, val onAnswer: (Boolean) -> Unit)
    @Volatile private var tap: Tap? = null

    // Synchronized: the car answers on the reader thread while clients open and close on main.
    @Synchronized
    fun open(sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit): MicHandle {
        // One listener at a time, like a real microphone: a new request takes it over.
        client?.onStopped?.invoke()
        val c = Client(sink, onStopped)
        client = c
        val mic = carMic()
        if (mic != null && mic.opened) {
            mic.config?.let { carRate = it.samplingRate; carChannels = it.numberOfChannels }
            carRequested = true
            // Already asked for (or open) for the phone's apps: its answer or its data comes anyway.
            if (tap == null) mic.openMic()
        } else {
            carRequested = false
            startPhoneMic(c)
        }
        voiceSession(true)
        return MicHandle { close(c) }
    }

    @Synchronized
    private fun close(c: Client) {
        if (client !== c) return
        client = null
        // Even before the car answered: otherwise its mic stays open once the answer comes.
        if (carRequested) { carRequested = false; if (tap == null) carMic()?.closeMic() }
        stopPhoneMic()
        voiceSession(false)
    }

    /**
     * Opens the car's mic for the phone's apps: [sink] gets its PCM as the car sends it (reader
     * thread, must not block), [onAnswer] whether the car opened it. False if the car has no mic.
     */
    @Synchronized
    fun openForPhoneApps(sink: (ByteArray, Int, Int) -> Unit, onAnswer: (Boolean) -> Unit): Boolean {
        val mic = carMic()?.takeIf { it.opened } ?: return false
        val was = tap
        tap = Tap(sink, onAnswer)
        when {
            was != null -> Unit
            !carRequested -> mic.openMic()
            // A car app has it open already: no new request, so no new answer either.
            mic.open -> onAnswer(true)
        }
        return true
    }

    @Synchronized
    fun closeForPhoneApps() {
        if (tap == null) return
        tap = null
        if (!carRequested) carMic()?.closeMic()
    }

    /** PCM from the car (reader thread). */
    fun onCarMic(data: ByteArray, offset: Int, length: Int) {
        tap?.sink?.invoke(data, offset, length)
        val c = client ?: return
        if (carRate == 16_000 && carChannels == 1) {
            c.sink(data, offset, length)
        } else {
            val out = downmixResample(data, offset, length, carRate, carChannels)
            c.sink(out, 0, out.size)
        }
    }

    /** The car answered our request (reader thread); if it refused, the phone's mic takes over. */
    @Synchronized
    fun onCarMicOpened(ok: Boolean) {
        tap?.let { t -> if (!ok) tap = null; t.onAnswer(ok) }
        val c = client
        if (ok || !carRequested || c == null) return
        Log.i(TAG, "car microphone refused, using the phone's")
        carRequested = false
        startPhoneMic(c)
    }

    /** The session ended: whoever listens hears the end of the stream. */
    @Synchronized
    fun shutdown() {
        carRequested = false
        tap = null
        client?.let { it.onStopped(); client = null }
        stopPhoneMic()
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneMic(c: Client) {
        if (phoneRunning) return
        phoneRunning = true
        phoneThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
            } catch (e: Exception) {
                Log.w(TAG, "phone microphone unavailable", e); null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                phoneMicFailed(c)
                return@Thread
            }
            val buf = ByteArray(640) // 20 ms
            rec.startRecording()
            while (phoneRunning) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) client?.sink?.invoke(buf, 0, n)
            }
            rec.stop()
            rec.release()
        }, "aare-phone-mic").apply { start() }
    }

    /**
     * The phone's mic couldn't start (busy in a call, no permission): end the stream for [c] now,
     * or its app would wait for sound forever. Runs on the mic thread, so not through [close],
     * whose join would wait for this very thread.
     */
    @Synchronized
    private fun phoneMicFailed(c: Client) {
        // A newer mic thread took over meanwhile: it isn't this one's to stop.
        if (phoneThread !== Thread.currentThread()) return
        phoneRunning = false
        phoneThread = null
        if (client !== c) return
        client = null
        voiceSession(false)
        c.onStopped()
    }

    private fun stopPhoneMic() {
        phoneRunning = false
        phoneThread?.join(300)
        phoneThread = null
    }

    companion object {
        private const val TAG = "AareMic"

        /** Converts 16-bit PCM at any rate/channel count to 16 kHz mono (linear interpolation). */
        fun downmixResample(data: ByteArray, offset: Int, length: Int, rate: Int, channels: Int): ByteArray {
            val frames = length / (2 * channels)
            val outFrames = (frames.toLong() * 16_000 / rate).toInt()
            val out = ByteArray(outFrames * 2)
            fun sample(frame: Int): Int {
                var sum = 0
                for (ch in 0 until channels) {
                    val i = offset + (frame * channels + ch) * 2
                    sum += ((data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)).toShort().toInt()
                }
                return sum / channels
            }
            for (o in 0 until outFrames) {
                val pos = o.toDouble() * rate / 16_000
                val i0 = pos.toInt().coerceAtMost(frames - 1)
                val i1 = (i0 + 1).coerceAtMost(frames - 1)
                val v = (sample(i0) + (sample(i1) - sample(i0)) * (pos - i0)).toInt()
                out[o * 2] = v.toByte()
                out[o * 2 + 1] = (v shr 8).toByte()
            }
            return out
        }
    }
}
