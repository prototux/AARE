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
import net.prototux.aare.BuildConfig
import net.prototux.aare.Settings
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A stream of 16-bit PCM captured from the phone, for one car audio channel. */
interface PcmSource {
    /** Starts capturing; false if this source can't work now. Called on the audio thread. */
    fun start(): Boolean
    /** Blocking read, like AudioRecord.read; -1 once the source is gone. */
    fun read(buf: ByteArray, offset: Int, length: Int): Int
    fun release()
}

/** How AARE captures the phone's audio, best first. */
enum class CaptureMethod(val label: String) {
    /** Android Auto's own way: an audio policy (privileged app, see tools/magisk). Music and navigation voice apart. */
    AUDIO_POLICY("System audio routing (like Android Auto)"),
    /** The phone's whole output through Shizuku (adb rights) or root (su): navigation voice included, mixed with music. */
    SHIZUKU("Shizuku or root"),
    /** Screen-capture consent: media only, apps may opt out, no navigation voice. */
    PLAYBACK_CAPTURE("Screen-capture permission"),
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
 * speaker and loops them back to us. The policy API is for privileged apps only: AARE must be
 * installed in /system/priv-app with MODIFY_AUDIO_ROUTING (tools/magisk).
 */
class AudioPolicySource(
    private val context: Context,
    private val usages: IntArray,
    private val rate: Int,
    private val channels: Int,
) : PcmSource {
    private var policy: Any? = null
    private var record: AudioRecord? = null

    override fun start(): Boolean = try {
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
        policyBuilder.javaClass.getMethod("setLooper", Looper::class.java).invoke(policyBuilder, Looper.getMainLooper())
        val p = policyBuilder.javaClass.getMethod("build").invoke(policyBuilder)

        val am = context.getSystemService(AudioManager::class.java)
        val status = AudioManager::class.java.getMethod("registerAudioPolicy", p.javaClass).invoke(am, p) as Int
        if (status != 0) throw IllegalStateException("registerAudioPolicy: $status")
        policy = p
        val r = p.javaClass.getMethod("createAudioRecordSink", mixClass).invoke(p, mix) as AudioRecord
        r.startRecording()
        record = r
        true
    } catch (e: Throwable) {
        Log.w(TAG, "audio policy capture unavailable", e)
        release()
        false
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int = record?.read(buf, offset, length) ?: -1

    override fun release() {
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        val p = policy ?: return
        policy = null
        runCatching {
            val am = context.getSystemService(AudioManager::class.java)
            AudioManager::class.java.getMethod("unregisterAudioPolicy", p.javaClass).invoke(am, p)
        }.onFailure { Log.w(TAG, "unregisterAudioPolicy", it) }
    }

    private companion object {
        const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
        const val ROUTE_FLAG_LOOP_BACK = 0x2
    }
}

/** The phone's whole output, recorded by [ShellAudioService] in a Shizuku (adb rights) process. */
class ShizukuSource(private val context: Context, private val rate: Int, private val channels: Int) : PcmSource {
    private var service: IShellAudio? = null
    private var stream: InputStream? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellAudioService::class.java.name))
        .daemon(false)
        .processNameSuffix("audio")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let(IShellAudio.Stub::asInterface)
            bound.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            runCatching { stream?.close() }
        }
    }
    private val bound = CountDownLatch(1)

    override fun start(): Boolean {
        if (!CaptureMethods.shizukuAvailable()) return false
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { Log.w(TAG, "Shizuku bind", it); return false }
        if (!bound.await(5, TimeUnit.SECONDS)) return false
        val pfd = runCatching { service?.start(rate, channels) }.onFailure { Log.w(TAG, "Shizuku capture", it) }.getOrNull() ?: return false
        stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        return true
    }

    override fun read(buf: ByteArray, offset: Int, length: Int): Int = try {
        stream?.read(buf, offset, length) ?: -1
    } catch (_: IOException) {
        -1
    }

    override fun release() {
        runCatching { service?.stop() }
        runCatching { stream?.close() }
        stream = null
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
    }
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
