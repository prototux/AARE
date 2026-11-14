package net.prototux.aare.audio

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * Runs in a separate process that Shizuku starts with adb (shell) rights, which may record the
 * "remote submix": everything the phone plays, navigation voice included. While it is recorded
 * the phone itself goes quiet, as it does with Android Auto. This is how scrcpy captures audio.
 *
 * Android 12+ only (the recording must be attributed to the shell, which needs AudioRecord's
 * setContext).
 */
class ShellAudioService : IShellAudio.Stub {
    private val context: Context?
    @Volatile private var record: AudioRecord? = null
    private var pump: Thread? = null

    constructor() : this(null)

    /** Shizuku passes a Context when the service has this constructor. */
    constructor(context: Context?) {
        this.context = context
    }

    // The shell holds RECORD_AUDIO and CAPTURE_AUDIO_OUTPUT; REMOTE_SUBMIX isn't in AudioRecord's public list.
    @SuppressLint("MissingPermission", "WrongConstant")
    override fun start(sampleRate: Int, channels: Int): ParcelFileDescriptor? {
        stop()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        // Shizuku runs this as the shell (2000), which holds CAPTURE_AUDIO_OUTPUT; the su helper
        // (SuAudioMain via app_process) runs as uid 0, which doesn't — the permission is checked
        // against the recording uid, and being root isn't enough. Drop to the shell uid (gid before
        // uid; once dropped we can't go back, which is fine: we only record audio from here on).
        if (Process.myUid() == 0) runCatching { Os.setgid(SHELL_UID); Os.setuid(SHELL_UID) }
            .onFailure { Log.w(TAG, "could not drop to the shell uid", it); return null }
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO)
                .build()
            val chunk = sampleRate / 50 * channels * 2
            val r = AudioRecord.Builder()
                .setContext(ShellContext(context ?: systemContext()))
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(chunk * 8, AudioRecord.getMinBufferSize(sampleRate, format.channelMask, format.encoding)))
                .build()
            r.startRecording()
            record = r
            val (read, write) = ParcelFileDescriptor.createPipe()
            pump = Thread({
                val buf = ByteArray(chunk)
                FileOutputStream(write.fileDescriptor).use { out ->
                    try {
                        while (record === r) {
                            val n = r.read(buf, 0, buf.size)
                            if (n < 0) break
                            if (n > 0) out.write(buf, 0, n)
                        }
                    } catch (_: Exception) {
                        // AARE closed its end of the pipe
                    }
                }
                write.close()
                // Nobody reads any more (AARE closed the pipe without stop(), or went away): don't
                // keep recording, or the phone stays silent with nothing playing it anywhere.
                if (record === r) { Log.i(TAG, "capture pipe closed; stopping"); stop() }
            }, "aare-shell-audio").apply { start() }
            read
        } catch (e: Exception) {
            Log.w(TAG, "remote submix capture failed", e)
            stop()
            null
        }
    }

    /** An audio policy stream (startPolicy): the policy's recorder, pumped into a pipe. */
    private class PolicyStream(val handle: AudioPolicyCapture.Handle) {
        @Volatile var open = true
        var pump: Thread? = null
    }
    private val policies = HashMap<Int, PolicyStream>()
    private val policyLooper: Looper by lazy { HandlerThread("aare-shell-policy").apply { start() }.looper }

    override fun startPolicy(key: Int, sampleRate: Int, channels: Int, usages: IntArray): ParcelFileDescriptor? = asShell { synchronized(policies) {
        stopPolicyLocked(key)
        try {
            val ctx = ShellContext(context ?: systemContext())
            val handle = AudioPolicyCapture.open(ctx, shellAudioManager(ctx), usages, sampleRate, channels, policyLooper, recordContext = ctx)
            val stream = PolicyStream(handle)
            val (read, write) = ParcelFileDescriptor.createPipe()
            val chunk = sampleRate / 50 * channels * 2
            stream.pump = Thread({
                val buf = ByteArray(chunk)
                FileOutputStream(write.fileDescriptor).use { out ->
                    try {
                        while (stream.open) {
                            val n = handle.record.read(buf, 0, buf.size)
                            if (n < 0) break
                            if (n > 0) out.write(buf, 0, n)
                        }
                    } catch (_: Exception) {
                        // AARE closed its end of the pipe
                    }
                }
                write.close()
                // As for the remote submix: a policy nobody reads keeps its sound off the phone's
                // speaker for good, so it goes with its reader.
                if (stream.open) synchronized(policies) {
                    if (policies[key] === stream) { Log.i(TAG, "policy $key pipe closed; stopping"); stopPolicyLocked(key) }
                }
            }, "aare-shell-policy-$key").apply { start() }
            policies[key] = stream
            read
        } catch (e: Throwable) {
            Log.w(TAG, "audio policy as the shell failed", e)
            null
        }
    } }

    override fun stopPolicy(key: Int) = asShell { synchronized(policies) { stopPolicyLocked(key) } }

    /**
     * Runs [block] as this process (the shell), not as the AARE call that brought us here: the
     * audio policy checks MODIFY_AUDIO_ROUTING with checkCallingOrSelfPermission, which would
     * otherwise check AARE's uid and refuse.
     */
    private inline fun <T> asShell(block: () -> T): T {
        val id = Binder.clearCallingIdentity()
        try { return block() } finally { Binder.restoreCallingIdentity(id) }
    }

    private fun stopPolicyLocked(key: Int) {
        val s = policies.remove(key) ?: return
        s.open = false
        runCatching { s.handle.record.stop() }
        s.pump?.takeIf { it !== Thread.currentThread() }?.join(500)
        s.handle.close()
    }

    /** An AudioManager that speaks for the shell, as the uid holding MODIFY_AUDIO_ROUTING. */
    @SuppressLint("PrivateApi")
    private fun shellAudioManager(ctx: Context): AudioManager = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("Landroid/media/AudioManager;")
        AudioManager::class.java.getConstructor(Context::class.java).newInstance(ctx)
    }.getOrElse { ctx.getSystemService(AudioManager::class.java) }

    // Synchronized: AARE's call and the pump noticing its reader is gone can come together.
    @Synchronized
    override fun stop() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        pump?.takeIf { it !== Thread.currentThread() }?.join(500)
        pump = null
        r.release()
    }

    override fun destroy() {
        stop()
        synchronized(policies) { policies.keys.toList().forEach(::stopPolicyLocked) }
        exitProcess(0)
    }

    /** The system context of this (app_process) process. */
    @SuppressLint("PrivateApi")
    private fun systemContext(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityThread;")
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }

    /** A context that says "the shell", so the recording is attributed to the uid running it. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        @RequiresApi(Build.VERSION_CODES.S) // only used from Android 12, see start()
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
    }

    private companion object {
        const val TAG = "AareShellAudio"
        const val SHELL_PACKAGE = "com.android.shell"
        const val SHELL_UID = 2000
    }
}
