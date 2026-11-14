package net.prototux.aare.audio

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import net.prototux.aare.DiagLog

/**
 * The car's microphone for the phone's other apps while connected (Settings.carMicForPhoneApps):
 * voice typing, voice recorders and voice-message buttons hear the car instead of the phone.
 *
 * The audio side runs as the shell in the Shizuku helper ([ShellAudioService], [ShellMicInjector]),
 * which also says when such an app records. This class opens the car's mic then (through
 * [router], which shares it with car apps), pipes its PCM to the helper, and closes it once they
 * stop ([MicTakeover]). [rate] and [channels] are the car mic's format.
 */
class CarMicForApps(
    private val context: Context,
    private val router: MicRouter,
    private val rate: Int,
    private val channels: Int,
) {
    private val thread = HandlerThread("aare-apps-mic").apply { start() }
    // Everything below runs on this thread, binder calls to the helper included (not on main).
    private val handler = Handler(thread.looper)
    private val takeover = MicTakeover()
    private var service: IShellAudio? = null
    /** Car PCM on its way to the helper: the car's reader thread must never wait on the pipe. */
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CHUNKS)
    private var out: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var writer: Thread? = null
    @Volatile private var dropped = 0

    private val listener = object : IShellMicListener.Stub() {
        override fun onPhoneAppsRecording(recording: Boolean) { handler.post { onRecording(recording) } }
    }
    private val death = IBinder.DeathRecipient { handler.post { if (service != null) { DiagLog.add("car mic for phone apps: the Shizuku helper went away"); unbind() } } }
    private val tick = Runnable { act(takeover.onTick(SystemClock.uptimeMillis())); scheduleTick() }

    fun start() { handler.post(::bind) }

    fun stop() {
        handler.post { act(takeover.stop()); unbind() }
        thread.quitSafely()
    }

    private fun bind() {
        if (!available()) { DiagLog.add("car mic for phone apps: needs Shizuku, allowed for AARE, and Android 10 or newer"); return }
        val s = ShellAudio.acquire(context) ?: run { DiagLog.add("car mic for phone apps: the Shizuku helper didn't start"); return }
        val (read, write) = ParcelFileDescriptor.createPipe()
        val ok = runCatching { s.startMicInjection(read, rate, channels, listener) }.onFailure { Log.w(TAG, "startMicInjection", it) }.getOrDefault(false)
        // The helper got its own copy of the read end.
        read.close()
        if (!ok) {
            write.close()
            ShellAudio.release()
            DiagLog.add("car mic for phone apps: the system refused the audio policy, so phone apps keep the phone's mic")
            return
        }
        runCatching { s.asBinder().linkToDeath(death, 0) }
        service = s
        val o = ParcelFileDescriptor.AutoCloseOutputStream(write)
        out = o
        writer = Thread({
            try {
                while (true) o.write(queue.take())
            } catch (_: InterruptedException) {
                // stopped
            } catch (_: IOException) {
                // the helper closed its end
            }
        }, "aare-apps-mic-pipe").apply { isDaemon = true; start() }
        DiagLog.add("car mic for phone apps: ready ($rate Hz, $channels ch)")
    }

    private fun onRecording(recording: Boolean) {
        if (service == null) return
        act(takeover.onRecording(recording, SystemClock.uptimeMillis()))
        scheduleTick()
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        val at = takeover.nextTick()
        if (at >= 0) handler.postAtTime(tick, at)
    }

    private fun act(action: MicTakeover.Action) {
        val s = service ?: return
        when (action) {
            MicTakeover.Action.OPEN -> {
                // Injecting before the car answers, so its first words reach the recording.
                runCatching { s.setMicInjecting(true) }
                if (router.openForPhoneApps(::enqueue) { ok -> handler.post { onCarAnswer(ok) } }) {
                    DiagLog.add("car mic for phone apps: a phone app records, opening the car's mic")
                } else {
                    takeover.onRefused()
                    runCatching { s.setMicInjecting(false) }
                    DiagLog.add("car mic for phone apps: the car's mic isn't available, the phone's stays in use")
                }
            }
            MicTakeover.Action.CLOSE -> {
                router.closeForPhoneApps()
                runCatching { s.setMicInjecting(false) }
                queue.clear()
                DiagLog.add("car mic for phone apps: no phone app records any more, closing the car's mic" +
                    if (dropped > 0) " ($dropped chunks dropped)" else "")
                dropped = 0
            }
            MicTakeover.Action.NONE -> Unit
        }
    }

    /** The car answered (refusals only matter: a granted mic just starts sending). */
    private fun onCarAnswer(ok: Boolean) {
        if (ok || !takeover.open) return
        takeover.onRefused()
        router.closeForPhoneApps()
        runCatching { service?.setMicInjecting(false) }
        DiagLog.add("car mic for phone apps: the car refused its mic, the phone's stays in use")
    }

    /** PCM from the car (its reader thread). */
    private fun enqueue(data: ByteArray, offset: Int, length: Int) {
        // Full: the helper fell behind, and late sound is worse than a gap.
        if (!queue.offer(data.copyOfRange(offset, offset + length))) dropped++
    }

    private fun unbind() {
        handler.removeCallbacks(tick)
        val s = service ?: return
        service = null
        if (takeover.stop() == MicTakeover.Action.CLOSE) router.closeForPhoneApps()
        runCatching { s.asBinder().unlinkToDeath(death, 0) }
        runCatching { s.stopMicInjection() }
        writer?.interrupt()
        writer = null
        runCatching { out?.close() }
        out = null
        queue.clear()
        ShellAudio.release()
    }

    companion object {
        private const val TAG = "AareAppsMic"
        /** About a second of the car's mic in its usual chunks. */
        private const val QUEUE_CHUNKS = 50

        /** Shizuku is running and allowed, on Android 10+ (the recording details the shell needs). */
        fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && CaptureMethods.shizukuReady()
    }
}
