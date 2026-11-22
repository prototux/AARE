package net.prototux.aare.audio

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.StringRes
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.prototux.aare.BuildConfig
import net.prototux.aare.R
import net.prototux.aare.Settings
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

/** A stream of 16-bit PCM captured from the phone, for one car audio channel. */
interface PcmSource {
    /** Starts capturing; false if this source can't work now. Called on the audio thread. */
    fun start(): Boolean
    /** Blocking read, like AudioRecord.read; -1 once the source is gone. */
    fun read(buf: ByteArray, offset: Int, length: Int): Int
    fun release()
}

/** How AARE captures the phone's audio, best first. [label] is for the log, [title] for the screen. */
enum class CaptureMethod(val label: String, @StringRes val title: Int) {
    /** Android Auto's own way: an audio policy (privileged app, see tools/magisk). Music and navigation voice apart. */
    AUDIO_POLICY("System audio routing (like Android Auto)", R.string.audio_method_policy),
    /** The same audio policy, registered by a Shizuku (adb rights) process: the shell holds MODIFY_AUDIO_ROUTING. */
    SHIZUKU_POLICY("Shizuku, music and navigation apart (like Android Auto)", R.string.audio_method_shizuku_policy),
    /** The phone's whole output through Shizuku (adb rights) or root (su): navigation voice included, mixed with music. */
    SHIZUKU("Shizuku or root", R.string.audio_method_shizuku),
    /** Screen-capture consent: media only, apps may opt out, no navigation voice. */
    PLAYBACK_CAPTURE("Screen-capture permission", R.string.audio_method_playback_capture),
}

object CaptureMethods {
    /** Usages of the car's media channel and of its guidance channel, as Android Auto splits them. */
    val MEDIA_USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
    val GUIDANCE_USAGES = intArrayOf(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.USAGE_ASSISTANT)

    fun audioPolicyAvailable(context: Context): Boolean =
        context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") == PackageManager.PERMISSION_GRANTED

    /** Shizuku is running and AARE may use it (Android 12+, where the shell can attribute the recording). */
    fun shizukuAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku is running but AARE hasn't been allowed yet. */
    fun shizukuNeedsPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** The user allowed capturing car audio through su (a rooted phone), confirmed by [checkRoot]. */
    fun rootAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Settings(context).rootAudio

    /** Runs `su -c id` off the main thread; [onResult] gets true if root is granted (uid 0). */
    fun checkRoot(onResult: (Boolean) -> Unit) {
        Thread {
            val granted = runCatching {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().use { it.readText() }
                if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroy()
                out.contains("uid=0")
            }.getOrDefault(false)
            onResult(granted)
        }.apply { isDaemon = true }.start()
    }

    /** The best method available now, skipping [exclude] (methods that already failed). */
    fun best(context: Context, projection: MediaProjection?, exclude: Set<CaptureMethod> = emptySet()): CaptureMethod? = when {
        CaptureMethod.AUDIO_POLICY !in exclude && audioPolicyAvailable(context) -> CaptureMethod.AUDIO_POLICY
        CaptureMethod.SHIZUKU_POLICY !in exclude && shizukuAvailable() -> CaptureMethod.SHIZUKU_POLICY
        CaptureMethod.SHIZUKU !in exclude && (shizukuAvailable() || rootAvailable(context)) -> CaptureMethod.SHIZUKU
        // Capturing other apps' sound arrived in Android 10.
        CaptureMethod.PLAYBACK_CAPTURE !in exclude && projection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> CaptureMethod.PLAYBACK_CAPTURE
        else -> null
    }
}

private fun pcmFormat(rate: Int, channels: Int, out: Boolean): AudioFormat = AudioFormat.Builder()
    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
    .setSampleRate(rate)
    .setChannelMask(
        if (out) (if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
        else (if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO),
    )
    .build()

private fun bufferBytes(rate: Int, channels: Int): Int {
    val f = pcmFormat(rate, channels, out = false)
    return maxOf(rate / 50 * channels * 2 * 8, AudioRecord.getMinBufferSize(rate, f.channelMask, f.encoding))
}

/** Screen-capture consent (AudioPlaybackCapture): media, games and unknown usages only. */
class PlaybackCaptureSource(private val projection: MediaProjection, private val rate: Int, private val channels: Int) : PcmSource {
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection).apply {
            CaptureMethods.MEDIA_USAGES.forEach(::addMatchingUsage)
        }.build()
        record = runCatching {
            AudioRecord.Builder().setAudioFormat(pcmFormat(rate, channels, out = false)).setAudioPlaybackCaptureConfig(capture)
                .setBufferSizeInBytes(bufferBytes(rate, channels)).build().also { it.startRecording() }
        }.onFailure { Log.w(TAG, "playback capture unavailable", it) }.getOrNull()
        return record != null
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int = record?.read(buf, offset, length) ?: -1

    override fun release() {
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
    }
}

/**
 * Android Auto's way: an audio policy whose mix takes the matching usages away from the phone's
 * speaker and loops them back to a recorder. Registering one needs MODIFY_AUDIO_ROUTING: a
 * privileged app (tools/magisk) or the shell, which Shizuku runs [ShellAudioService] as.
 */
internal object AudioPolicyCapture {
    private const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
    private const val ROUTE_FLAG_LOOP_BACK = 0x2

    class Handle(private val audioManager: AudioManager, private val policy: Any, val record: AudioRecord) {
        fun close() {
            runCatching { record.stop() }
            record.release()
            runCatching {
                AudioManager::class.java.getMethod("unregisterAudioPolicy", policy.javaClass).invoke(audioManager, policy)
            }.onFailure { Log.w(TAG, "unregisterAudioPolicy", it) }
        }
    }

    /**
     * Registers the policy and starts recording; throws if the system refuses. [recordContext]
     * attributes the recording (the shell's helper must say "the shell", or AudioFlinger refuses
     * a record for a package that isn't its uid's); null uses the process's own identity.
     */
    fun open(context: Context, audioManager: AudioManager, usages: IntArray, rate: Int, channels: Int, looper: Looper, recordContext: Context? = null): Handle {
        // Hidden-API restrictions arrived in Android 9.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("Landroid/media/audiopolicy/", "Landroid/media/AudioManager;")
        val ruleBuilder = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder").getConstructor().newInstance()
        val addRule = ruleBuilder.javaClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
        for (u in usages) addRule.invoke(ruleBuilder, AudioAttributes.Builder().setUsage(u).build(), RULE_MATCH_ATTRIBUTE_USAGE)
        val rule = ruleBuilder.javaClass.getMethod("build").invoke(ruleBuilder)

        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilder = Class.forName("android.media.audiopolicy.AudioMix\$Builder").getConstructor(rule.javaClass).newInstance(rule)
        mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, pcmFormat(rate, channels, out = true))
        mixBuilder.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK)
        val mix = mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)

        val policyBuilder = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder").getConstructor(Context::class.java).newInstance(context)
        policyBuilder.javaClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        policyBuilder.javaClass.getMethod("setLooper", Looper::class.java).invoke(policyBuilder, looper)
        val p = policyBuilder.javaClass.getMethod("build").invoke(policyBuilder)

        val status = AudioManager::class.java.getMethod("registerAudioPolicy", p.javaClass).invoke(audioManager, p) as Int
        if (status != 0) throw IllegalStateException("registerAudioPolicy: $status")
        val record = try {
            val sink = if (recordContext != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The Builder overload (newer Android) keeps our attribution and a buffer of ~160 ms
                // rather than the policy's ~60 ms, which a late reader overruns; without it, fall back.
                val withBuilder = runCatching { p.javaClass.getMethod("createAudioRecordSink", mixClass, AudioRecord.Builder::class.java) }.getOrNull()
                if (withBuilder != null) withBuilder.invoke(p, mix, AudioRecord.Builder().setContext(recordContext).setBufferSizeInBytes(bufferBytes(rate, channels))) as AudioRecord?
                else p.javaClass.getMethod("createAudioRecordSink", mixClass).invoke(p, mix) as AudioRecord?
            } else {
                p.javaClass.getMethod("createAudioRecordSink", mixClass).invoke(p, mix) as AudioRecord?
            }
            (sink ?: throw IllegalStateException("createAudioRecordSink refused")).also { it.startRecording() }
        } catch (e: Throwable) {
            runCatching { AudioManager::class.java.getMethod("unregisterAudioPolicy", p.javaClass).invoke(audioManager, p) }
            throw e
        }
        return Handle(audioManager, p, record)
    }
}

/** [AudioPolicyCapture] in AARE itself: needs AARE installed in /system/priv-app (tools/magisk). */
class AudioPolicySource(
    private val context: Context,
    private val usages: IntArray,
    private val rate: Int,
    private val channels: Int,
) : PcmSource {
    private var handle: AudioPolicyCapture.Handle? = null

    override fun start(): Boolean = try {
        handle = AudioPolicyCapture.open(context, context.getSystemService(AudioManager::class.java), usages, rate, channels, Looper.getMainLooper())
        true
    } catch (e: Throwable) {
        Log.w(TAG, "audio policy capture unavailable", e)
        release()
        false
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int = handle?.record?.read(buf, offset, length) ?: -1

    override fun release() {
        handle?.close()
        handle = null
    }
}

/**
 * The one binding to [ShellAudioService] for this process, shared by every Shizuku capture: the
 * music and navigation channels each have a stream in the same helper, which stops with the last.
 */
internal object ShellAudio {
    private val lock = Any()
    private var users = 0
    @Volatile private var service: IShellAudio? = null
    private var binding: Binding? = null

    /** One bind: its own connection, so callbacks from an earlier, torn-down helper are ignored. */
    private class Binding(val args: Shizuku.UserServiceArgs) : ServiceConnection {
        val bound = CountDownLatch(1)
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(lock) {
                if (binding === this) service = binder?.takeIf { it.pingBinder() }?.let(IShellAudio.Stub::asInterface)
            }
            bound.countDown()
        }
        // The helper's pipes close with it, so each stream reads -1 and its output fails over.
        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { if (binding === this) service = null }
        }
    }

    /** The helper, bound if needed; null if Shizuku can't start it. Pair with [release]. */
    fun acquire(context: Context): IShellAudio? {
        if (!CaptureMethods.shizukuAvailable()) return null
        val b = synchronized(lock) {
            if (users++ == 0) {
                val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellAudioService::class.java.name))
                    .daemon(false)
                    .processNameSuffix("audio")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE)
                val nb = Binding(args)
                binding = nb
                runCatching { Shizuku.bindUserService(args, nb) }.onFailure {
                    Log.w(TAG, "Shizuku bind", it)
                    users--
                    binding = null
                    return null
                }
            }
            binding!!
        }
        if (!b.bound.await(5, TimeUnit.SECONDS)) { release(); return null }
        return service ?: run { release(); null }
    }

    fun release() = synchronized(lock) {
        if (users == 0 || --users > 0) return@synchronized
        // Leave the helper running (it ends with AARE's process): removing it and binding again
        // straight away, as a failover does, can leave the new bind never connecting.
        binding?.let { b -> runCatching { Shizuku.unbindUserService(b.args, b, false) } }
        binding = null
        service = null
    }
}

/** A PCM pipe from [ShellAudioService]; [open] asks the helper for the stream. */
abstract class ShellPipeSource(private val context: Context) : PcmSource {
    private var service: IShellAudio? = null
    private var stream: InputStream? = null

    protected abstract fun open(service: IShellAudio): ParcelFileDescriptor?
    protected abstract fun close(service: IShellAudio)

    override fun start(): Boolean {
        val s = ShellAudio.acquire(context) ?: return false
        service = s
        val pfd = runCatching { open(s) }.onFailure { Log.w(TAG, "Shizuku capture", it) }.getOrNull()
        if (pfd == null) { release(); return false }
        stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        return true
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int = try {
        stream?.read(buf, offset, length) ?: -1
    } catch (_: IOException) {
        -1
    }

    override fun release() {
        val s = service ?: return
        service = null
        runCatching { close(s) }
        runCatching { stream?.close() }
        stream = null
        ShellAudio.release()
    }
}

/** The phone's whole output, recorded by [ShellAudioService] in a Shizuku (adb rights) process. */
class ShizukuSource(context: Context, private val rate: Int, private val channels: Int) : ShellPipeSource(context) {
    override fun open(service: IShellAudio): ParcelFileDescriptor? = service.start(rate, channels)
    override fun close(service: IShellAudio) = service.stop()
}

/** [usages] only, through an audio policy that [ShellAudioService] registers as the shell. */
class ShizukuPolicySource(
    context: Context,
    private val usages: IntArray,
    private val rate: Int,
    private val channels: Int,
) : ShellPipeSource(context) {
    // One stream per car channel in the helper, told apart by their usages.
    private val key = usages.sorted().hashCode()
    override fun open(service: IShellAudio): ParcelFileDescriptor? = service.startPolicy(key, rate, channels, usages)
    override fun close(service: IShellAudio) = service.stopPolicy(key)
}

/**
 * The phone's whole output, recorded by [SuAudioMain] in a root process AARE starts with `su` and
 * `app_process` (the scrcpy/Shizuku way). The same capture as [ShizukuSource], for rooted phones
 * (KernelSU, Magisk, APatch…) without the Shizuku app or the Magisk module. PCM arrives on the
 * process's stdout.
 */
class SuSource(private val context: Context, private val rate: Int, private val channels: Int) : PcmSource {
    private var process: Process? = null
    private var stream: InputStream? = null

    override fun start(): Boolean {
        val apk = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH='$apk' app_process / ${SuAudioMain::class.java.name} $rate $channels"
        val p = runCatching { ProcessBuilder("su", "-c", cmd).start() }
            .onFailure { Log.w(TAG, "su launch", it) }.getOrNull() ?: return false
        process = p
        stream = p.inputStream
        // Drain stderr so a chatty app_process can't fill its pipe and stall the capture.
        Thread {
            runCatching { p.errorStream.bufferedReader().forEachLine { if (BuildConfig.DEBUG) Log.d(TAG, "su: $it") } }
        }.apply { isDaemon = true }.start()
        return true
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int = try {
        stream?.read(buf, offset, length) ?: -1
    } catch (_: IOException) {
        -1
    }

    override fun release() {
        // Closing our end breaks the helper's stdout pipe, so it stops; destroy() kills the su shell.
        runCatching { stream?.close() }
        runCatching { process?.destroy() }
        stream = null
        process = null
    }
}

private const val TAG = "AareCapture"
