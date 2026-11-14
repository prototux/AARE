package net.prototux.aare.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException

/**
 * The car's microphone for the phone's other apps, in the Shizuku helper ([ShellAudioService]) as
 * the shell, which holds MODIFY_AUDIO_ROUTING.
 *
 * An audio policy with a recorder ("injection") mix matched to [PhoneAppsMic.SOURCES]. While the
 * policy's track plays, Android moves the recordings that match to that track's sound (a remote
 * submix looped back to them); while it doesn't, they record from the phone's mic as usual. So the
 * track plays only while AARE says the car's mic is on ([setInjecting]) and its PCM arrives on the
 * pipe. Recordings are watched so AARE can open the car's mic when another app records.
 *
 * Android 10+: the recording's uid and "silenced" state, needed to leave AARE's own recordings and
 * background apps alone, are reported from Android 10.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class ShellMicInjector private constructor(
    private val audioManager: AudioManager,
    private val policy: Any,
    private val track: AudioTrack,
    pcm: ParcelFileDescriptor,
    private val rate: Int,
    private val channels: Int,
    private val excludedUids: Set<Int>,
    private val listener: IShellMicListener,
    looper: Looper,
) {
    private val handler = Handler(looper)
    private val input = ParcelFileDescriptor.AutoCloseInputStream(pcm)
    /** Guards the track's state: the pump writes to it while AARE's calls start and stop it. */
    private val lock = Any()
    private var injecting = false
    private var playing = false
    private var buffered = 0
    @Volatile private var closed = false
    /** What the listener was last told (handler thread). */
    private var reported: Boolean? = null
    private val bytesPerMs = rate * channels * 2 / 1000

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) = report(configs)
    }

    private val pump = Thread({
        val buf = ByteArray(rate / 50 * channels * 2)
        try {
            while (!closed) {
                val n = input.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) feed(buf, n)
            }
        } catch (_: IOException) {
            // closed
        }
        // AARE closed its end (or went away): the recordings go back to the phone's mic.
        if (!closed) { Log.i(TAG, "car mic pipe closed"); setInjecting(false) }
    }, "aare-shell-mic")

    private fun start() {
        audioManager.registerAudioRecordingCallback(callback, handler)
        handler.post { report(audioManager.activeRecordingConfigurations) }
        pump.start()
    }

    private fun feed(buf: ByteArray, n: Int) = synchronized(lock) {
        if (!injecting) return@synchronized
        track.write(buf, 0, n)
        // A little sound queued before playing: the car's mic comes in bursts, and a track that
        // runs dry gives the recording gaps.
        if (!playing) {
            buffered += n
            if (buffered >= PREFILL_MS * bytesPerMs) { track.play(); playing = true }
        }
    }

    /** Off pauses and empties the track: Android then gives the recordings the phone's mic again. */
    fun setInjecting(on: Boolean) = synchronized(lock) {
        injecting = on
        if (!on && (playing || buffered > 0)) {
            runCatching { track.pause(); track.flush() }.onFailure { Log.w(TAG, "track pause", it) }
            playing = false
            buffered = 0
        }
    }

    private fun report(configs: List<AudioRecordingConfiguration>) {
        if (closed) return
        val recording = PhoneAppsMic.anyTakesOver(configs.map(::recording), excludedUids)
        if (recording == reported) return
        reported = recording
        runCatching { listener.onPhoneAppsRecording(recording) }.onFailure { Log.w(TAG, "listener", it) }
    }

    private fun recording(c: AudioRecordingConfiguration) = PhoneRecording(
        // A system API: the shell sees every app's uid (MODIFY_AUDIO_ROUTING), others get -1.
        uid = runCatching { c.javaClass.getMethod("getClientUid").invoke(c) as Int }.getOrDefault(-1),
        source = c.clientAudioSource,
        silenced = c.isClientSilenced,
    )

    fun close() {
        if (closed) return
        closed = true
        runCatching { audioManager.unregisterAudioRecordingCallback(callback) }
        setInjecting(false)
        runCatching { input.close() }
        pump.takeIf { it !== Thread.currentThread() }?.join(500)
        track.release()
        unregister(audioManager, policy)
    }

    companion object {
        private const val TAG = "AareShellMic"
        private const val RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET = 0x2
        private const val RULE_EXCLUDE_UID = 0x8000 or 0x4
        private const val ROUTE_FLAG_LOOP_BACK = 0x2
        private const val MIX_ROLE_INJECTOR = 1
        private const val USAGE_VIRTUAL_SOURCE = 15
        private const val PREFILL_MS = 120
        private const val BUFFER_MS = 500

        /**
         * Registers the policy and its track; throws if the system refuses. [context] says "the
         * shell" ([ShellContext]); call with the calling identity cleared, as the policy checks
         * MODIFY_AUDIO_ROUTING against the caller or this process.
         */
        @SuppressLint("PrivateApi")
        fun open(
            context: Context, audioManager: AudioManager, looper: Looper, pcm: ParcelFileDescriptor,
            rate: Int, channels: Int, excludedUids: Set<Int>, listener: IShellMicListener,
        ): ShellMicInjector {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/media/audiopolicy/", "Landroid/media/AudioManager;",
                "Landroid/media/AudioAttributes", "Landroid/media/AudioRecordingConfiguration;", "Landroid/media/AudioTrack")
            val ruleBuilder = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder").getConstructor().newInstance()
            // An injection mix: said outright where the API has it (Android 14+), told from the rules before.
            runCatching { ruleBuilder.javaClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType) }.getOrNull()
                ?.invoke(ruleBuilder, MIX_ROLE_INJECTOR)
            val setPreset = AudioAttributes.Builder::class.java.getMethod("setCapturePreset", Int::class.javaPrimitiveType)
            val addRule = ruleBuilder.javaClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
            for (source in PhoneAppsMic.SOURCES) {
                val attrs = AudioAttributes.Builder().also { setPreset.invoke(it, source) }.build()
                addRule.invoke(ruleBuilder, attrs, RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET)
            }
            // Leave AARE's own recordings alone where a recorder mix can tell uids apart (newer
            // releases; older ones refuse the rule). Where it can't, they could only ever hear the
            // car's mic, which is what AARE's car apps get anyway: there is no loop to fear.
            val addMixRule = ruleBuilder.javaClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            for (uid in excludedUids) runCatching { addMixRule.invoke(ruleBuilder, RULE_EXCLUDE_UID, uid) }
                .onFailure { Log.i(TAG, "no uid rule for an injection mix here") }
            val rule = ruleBuilder.javaClass.getMethod("build").invoke(ruleBuilder)

            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilder = Class.forName("android.media.audiopolicy.AudioMix\$Builder").getConstructor(rule.javaClass).newInstance(rule)
            mixBuilder.javaClass.getMethod("setFormat", android.media.AudioFormat::class.java).invoke(mixBuilder, pcmFormat(rate, channels, out = true))
            mixBuilder.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK)
            val mix = mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)

            val policyBuilder = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder").getConstructor(Context::class.java).newInstance(context)
            policyBuilder.javaClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
            policyBuilder.javaClass.getMethod("setLooper", Looper::class.java).invoke(policyBuilder, looper)
            val policy = policyBuilder.javaClass.getMethod("build").invoke(policyBuilder)
            val status = AudioManager::class.java.getMethod("registerAudioPolicy", policy.javaClass).invoke(audioManager, policy) as Int
            if (status != 0) throw IllegalStateException("registerAudioPolicy: $status")
            try {
                val track = track(context, policy, mix, mixClass, rate, channels)
                return ShellMicInjector(audioManager, policy, track, pcm, rate, channels, excludedUids, listener, looper).also { it.start() }
            } catch (e: Throwable) {
                unregister(audioManager, policy)
                throw e
            }
        }

        /**
         * The track that feeds the mix: the policy's own kind (the virtual-source usage, tagged
         * with the mix's address), with a bigger buffer to ride out the car's bursts and, from
         * Android 14, attributed to the shell. Newer releases build it from our Builder; older
         * ones get the same attributes by hand. The policy's plain one if all that is refused.
         */
        @SuppressLint("WrongConstant")
        private fun track(context: Context, policy: Any, mix: Any, mixClass: Class<*>, rate: Int, channels: Int): AudioTrack = runCatching {
            val builder = AudioTrack.Builder()
                .setAudioFormat(pcmFormat(rate, channels, out = true))
                .setBufferSizeInBytes(BUFFER_MS * rate * channels * 2 / 1000)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) builder.setContext(context)
            val withBuilder = runCatching { policy.javaClass.getMethod("createAudioTrackSource", mixClass, AudioTrack.Builder::class.java) }.getOrNull()
            val t = if (withBuilder != null) {
                withBuilder.invoke(policy, mix, builder) as AudioTrack? ?: throw IllegalStateException("createAudioTrackSource refused")
            } else {
                val address = mixClass.getMethod("getRegistration").invoke(mix) as String
                val attrs = AudioAttributes.Builder().setUsage(USAGE_VIRTUAL_SOURCE)
                    .also { AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java).invoke(it, "addr=$address") }
                    .build()
                builder.setAudioAttributes(attrs).build()
            }
            if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); throw IllegalStateException("track not initialized") }
            t
        }.getOrElse { e ->
            Log.w(TAG, "injection track with our buffer refused, using the policy's", e)
            policy.javaClass.getMethod("createAudioTrackSource", mixClass).invoke(policy, mix) as AudioTrack?
                ?: throw IllegalStateException("createAudioTrackSource refused")
        }

        private fun unregister(audioManager: AudioManager, policy: Any) {
            runCatching { AudioManager::class.java.getMethod("unregisterAudioPolicy", policy.javaClass).invoke(audioManager, policy) }
                .onFailure { Log.w(TAG, "unregisterAudioPolicy", it) }
        }
    }
}
