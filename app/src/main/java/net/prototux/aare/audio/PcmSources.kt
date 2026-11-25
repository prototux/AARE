package net.prototux.aare.audio

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.root.Root
import net.prototux.aare.root.RootHandover
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

/** A stream of 16-bit PCM captured from the phone, for one car audio channel. */
interface PcmSource {
    /** Starts capturing; false if this source can't work now. Called on the audio thread. */
    fun start(): Boolean
    /** Blocking read, like AudioRecord.read; -1 once the source is gone. */
    fun read(buf: ByteArray, offset: Int, length: Int): Int
    fun release()
    /** Why [start] returned false, for the car audio screen (the root cause, short); null if unknown. */
    val failure: String? get() = null
}

/** How AARE captures the phone's audio, best first. [label] is for the log, [title] for the screen. */
enum class CaptureMethod(val label: String, @StringRes val title: Int) {
    /** Android Auto's own way: an audio policy (privileged app, see tools/magisk). Music and navigation voice apart. */
    AUDIO_POLICY("System audio routing (like Android Auto)", R.string.audio_method_policy),
    /** The same audio policy, registered by a Shizuku (adb rights) process: the shell holds MODIFY_AUDIO_ROUTING. */
    SHIZUKU_POLICY("Shizuku, music and navigation apart (like Android Auto)", R.string.audio_method_shizuku_policy),
    /** The same audio policy again, registered by a root helper (su) dropped to the shell uid: no Shizuku app, no Magisk module. */
    ROOT_POLICY("Root, music and navigation apart", R.string.audio_method_root_policy),
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
    fun shizukuAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && shizukuReady()

    /** Shizuku is running and AARE may use it, whatever the Android version (each use checks its own). */
    fun shizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku is running but AARE hasn't been allowed yet. */
    fun shizukuNeedsPermission(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Root is allowed (see Root), on Android 12+, where the helper can record as the shell. */
    fun rootAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Root.allowed(context)

    /** What the phone offers now, in detail, for the car audio screen ([CaptureStatus]). */
    fun setup(context: Context, playbackConsent: Boolean): CaptureStatus.Setup = CaptureStatus.Setup(
        sdk = Build.VERSION.SDK_INT,
        privileged = audioPolicyAvailable(context),
        rootFound = Root.found(),
        rootAllowed = Root.allowed(context),
        shizukuInstalled = runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess,
        shizukuRunning = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false),
        shizukuAllowed = shizukuReady(),
        consent = playbackConsent,
    )

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** What the phone offers now, for [CaptureOrder]; [playbackConsent]: a screen-capture consent is held. */
    fun env(context: Context, playbackConsent: Boolean): CaptureOrder.Env = CaptureOrder.Env(
        privileged = audioPolicyAvailable(context),
        shizuku = shizukuAvailable(),
        root = rootAvailable(context),
        playbackCapture = playbackConsent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    )

    /** The method to use now (see [CaptureOrder.best]), skipping [exclude] (methods that already failed). */
    fun best(context: Context, projection: MediaProjection?, exclude: Set<CaptureMethod> = emptySet()): CaptureMethod? =
        CaptureOrder.best(env(context, projection != null), exclude, Settings(context).forcedCaptureMethod)
}

internal fun pcmFormat(rate: Int, channels: Int, out: Boolean): AudioFormat = AudioFormat.Builder()
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
    override var failure: String? = null
        private set

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection).apply {
            CaptureMethods.MEDIA_USAGES.forEach(::addMatchingUsage)
        }.build()
        record = runCatching {
            AudioRecord.Builder().setAudioFormat(pcmFormat(rate, channels, out = false)).setAudioPlaybackCaptureConfig(capture)
                .setBufferSizeInBytes(bufferBytes(rate, channels)).build().also { it.startRecording() }
        }.onFailure { Log.w(TAG, "playback capture unavailable", it); failure = describeFailure(it) }.getOrNull()
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

    class Handle(private val audioManager: AudioManager, private val policy: Any, val record: AudioRecord, private val fullVolume: Boolean = false) {
        fun close() {
            runCatching { record.stop() }
            record.release()
            if (fullVolume) releaseFullVolume(audioManager)
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
    fun open(context: Context, audioManager: AudioManager, usages: IntArray, rate: Int, channels: Int, looper: Looper, recordContext: Context? = null, volume: Int = VOLUME_AS_IS): Handle {
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
        when (volume) {
            VOLUME_FULL -> fullVolume(audioManager)
            VOLUME_REPAIR -> repairFullVolume(audioManager)
        }
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
            if (volume == VOLUME_FULL) releaseFullVolume(audioManager)
            throw e
        }
        return Handle(audioManager, p, record, fullVolume = volume == VOLUME_FULL)
    }

    /** [open]'s volume: the loop back's volume as it is, at full volume while recording (Settings.carAudioFullVolume), or full volume left by a killed session put back first. */
    const val VOLUME_AS_IS = 0
    const val VOLUME_FULL = 1
    const val VOLUME_REPAIR = 2

    /**
     * The phone puts its own media volume on the loop back, as on any output: at its default index
     * (8 of 25 on a Pixel) some 30 dB below full, so the car played it faintly at a normal volume.
     * Android Auto sends at full volume and the car sets the level, so with Settings.carAudioFullVolume
     * the loop back's output gets full volume while AARE records it, then the behavior it had
     * (Android keeps the setting, and other recorders of that output would get full volume too).
     *
     * Sets the loop back's volume behavior to [behavior] (null: only reads it); returns the one it had, or null if it couldn't.
     */
    private fun volumeBehavior(audioManager: AudioManager, behavior: Int?): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/media/AudioDeviceAttributes;")
            val attrs = Class.forName("android.media.AudioDeviceAttributes")
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                .newInstance(ROLE_OUTPUT, AudioDeviceInfo.TYPE_REMOTE_SUBMIX, "")
            val before = AudioManager::class.java.getMethod("getDeviceVolumeBehavior", attrs.javaClass).invoke(audioManager, attrs) as Int
            if (behavior != null) AudioManager::class.java.getMethod("setDeviceVolumeBehavior", attrs.javaClass, Int::class.javaPrimitiveType)
                .invoke(audioManager, attrs, behavior)
            before
        }.onFailure { Log.w(TAG, "loop back volume behavior", it) }.getOrNull()
    }

    /** Captures open on the loop back at full volume (media and guidance each have one), and its behavior from before the first. */
    private var fullVolumeUsers = 0
    private var savedBehavior: Int? = null

    /** Full volume while any capture asking for it runs: the first saves the behavior to put back. */
    @Synchronized private fun fullVolume(audioManager: AudioManager) {
        if (fullVolumeUsers++ == 0) savedBehavior = volumeBehavior(audioManager, DEVICE_VOLUME_BEHAVIOR_FULL)?.let(::behaviorToRestore)
    }

    /** The last such capture closed: the loop back's volume as it was, so other recorders of it (screen recorders) aren't left at full volume. */
    @Synchronized private fun releaseFullVolume(audioManager: AudioManager) {
        if (fullVolumeUsers == 0 || --fullVolumeUsers > 0) return
        savedBehavior?.let { volumeBehavior(audioManager, it) }
        savedBehavior = null
    }

    /** Full volume a killed session left on the loop back: put back to the output's default (see [behaviorToRestore]). */
    @Synchronized private fun repairFullVolume(audioManager: AudioManager) {
        if (fullVolumeUsers > 0) return
        val now = volumeBehavior(audioManager, null) ?: return
        if (now != behaviorToRestore(now)) volumeBehavior(audioManager, behaviorToRestore(now))
    }

    private const val ROLE_OUTPUT = 2
}

/** [AudioPolicyCapture] in AARE itself: needs AARE installed in /system/priv-app (tools/magisk). */
class AudioPolicySource(
    private val context: Context,
    private val usages: IntArray,
    private val rate: Int,
    private val channels: Int,
    private val volume: Int = AudioPolicyCapture.VOLUME_AS_IS,
) : PcmSource {
    private var handle: AudioPolicyCapture.Handle? = null
    override var failure: String? = null
        private set

    override fun start(): Boolean = try {
        handle = AudioPolicyCapture.open(context, context.getSystemService(AudioManager::class.java), usages, rate, channels, Looper.getMainLooper(), volume = volume)
        true
    } catch (e: Throwable) {
        Log.w(TAG, "audio policy capture unavailable", e)
        failure = describeFailure(e)
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
/**
 * A privileged audio helper ([ShellAudioService], as the shell) shared by the captures that use it:
 * [ShellAudio] through Shizuku, [RootAudio] through su. [acquire] pairs with [release].
 */
internal interface AudioHelper {
    /** For the connection log. */
    val name: String
    /** The helper, started if needed; null if it can't be. */
    fun acquire(context: Context): IShellAudio?
    /** Done with [service], as [acquire] gave it: a helper started since doesn't count it. */
    fun release(service: IShellAudio)
}

/** [ShellAudioService] as a Shizuku user service. */
internal object ShellAudio : AudioHelper {
    override val name = "Shizuku"
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
    override fun acquire(context: Context): IShellAudio? {
        if (!CaptureMethods.shizukuReady()) return null
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

    override fun release(service: IShellAudio) = release()

    fun release() = synchronized(lock) {
        if (users == 0 || --users > 0) return@synchronized
        // Leave the helper running (it ends with AARE's process): removing it and binding again
        // straight away, as a failover does, can leave the new bind never connecting.
        binding?.let { b -> runCatching { Shizuku.unbindUserService(b.args, b, false) } }
        binding = null
        service = null
    }
}

/**
 * [ShellAudioService] run as root ([SuAudioMain], started with su), for rooted phones without the
 * Shizuku app. The music and navigation channels share one helper; it stops with the last.
 */
internal object RootAudio : AudioHelper {
    override val name = "root"
    private val lock = Any()
    private var users = 0
    private var helper: RootHandover.Helper? = null
    private var service: IShellAudio? = null

    /** Starts the helper if none runs (blocks up to a few seconds: the capture thread's start()). */
    override fun acquire(context: Context): IShellAudio? = synchronized(lock) {
        if (!CaptureMethods.rootAvailable(context)) return null
        val running = service?.takeIf { it.asBinder().isBinderAlive }
        val s = running ?: run {
            // Gone (it died, or never started): its captures fail over, and their releases no
            // longer count (see release).
            helper?.stop()
            helper = null
            users = 0
            val h = RootHandover.start(context, SuAudioMain::class.java.name, SuAudioMain.ACTION, listOf(context.packageName))
            if (h == null) {
                DiagLog.add("car audio: the root helper could not be started")
                return null
            }
            helper = h
            IShellAudio.Stub.asInterface(h.binder).also { service = it }
        }
        users++
        s
    }

    override fun release(service: IShellAudio) = synchronized(lock) {
        if (service !== this.service || users == 0 || --users > 0) return@synchronized
        // Its stdin closes: it stops every capture and exits.
        helper?.stop()
        helper = null
        this.service = null
    }
}

/** A PCM pipe from [ShellAudioService] in [helper]; [open] asks it for the stream. */
abstract class ShellPipeSource internal constructor(private val context: Context, private val helper: AudioHelper) : PcmSource {
    private var service: IShellAudio? = null
    private var stream: InputStream? = null

    protected abstract fun open(service: IShellAudio): ParcelFileDescriptor?
    protected abstract fun close(service: IShellAudio)
    final override var failure: String? = null
        private set

    override fun start(): Boolean {
        val s = helper.acquire(context) ?: run { failure = "the ${helper.name} helper could not be started"; return false }
        service = s
        val pfd = runCatching { open(s) }.onFailure { Log.w(TAG, "${helper.name} capture", it) }.getOrNull()
        if (pfd == null) {
            val why = runCatching { s.lastFailure() }.getOrNull()
            DiagLog.add("car audio: ${helper.name} capture refused${why?.let { " ($it)" }.orEmpty()}")
            failure = why
            release()
            return false
        }
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
        helper.release(s)
    }
}

/** The phone's whole output, recorded by [ShellAudioService] in [helper]: navigation voice mixed into the music. */
class OutputSource internal constructor(context: Context, helper: AudioHelper, private val rate: Int, private val channels: Int) :
    ShellPipeSource(context, helper) {
    override fun open(service: IShellAudio): ParcelFileDescriptor? = service.start(rate, channels)
    override fun close(service: IShellAudio) = service.stop()
}

/** [usages] only, through an audio policy that [ShellAudioService] registers as the shell, in [helper]. */
class PolicySource internal constructor(
    context: Context,
    helper: AudioHelper,
    private val usages: IntArray,
    private val rate: Int,
    private val channels: Int,
    private val volume: Int = AudioPolicyCapture.VOLUME_AS_IS,
) : ShellPipeSource(context, helper) {
    // One stream per car channel in the helper, told apart by their usages.
    private val key = usages.sorted().hashCode()
    override fun open(service: IShellAudio): ParcelFileDescriptor? = service.startPolicy(key, rate, channels, usages, volume)
    override fun close(service: IShellAudio) = service.stopPolicy(key)
}

private const val TAG = "AareCapture"

/**
 * The loop back's volume behavior to put back, given the one it had: full is AARE's own, left behind
 * when AARE was stopped while recording, so it becomes variable, the output's default.
 */
internal fun behaviorToRestore(before: Int): Int = if (before == DEVICE_VOLUME_BEHAVIOR_FULL) DEVICE_VOLUME_BEHAVIOR_VARIABLE else before

private const val DEVICE_VOLUME_BEHAVIOR_VARIABLE = 0
private const val DEVICE_VOLUME_BEHAVIOR_FULL = 1
