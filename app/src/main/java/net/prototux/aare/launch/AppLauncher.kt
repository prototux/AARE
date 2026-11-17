package net.prototux.aare.launch

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import net.prototux.aare.BuildConfig
import net.prototux.aare.audio.CaptureMethods
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * App-side connector to the privileged [LaunchHostService]: over Shizuku (a bound UserService) when
 * it's available, else root (the [LaunchMain] app_process helper, which hands its binder back by
 * broadcast). Mirrors ShizukuSource / SuSource in the audio code. One instance per launched app.
 *
 * [connect] blocks and must run off the main thread.
 */
class AppLauncher(private val context: Context, key: String = "launch") {
    @Volatile private var host: ILaunchHost? = null

    // --- Shizuku path ---
    // One helper per [key]: Shizuku tells user services apart by their tag only, so two launchers
    // with the same key would share one helper, its display, and its end when one releases it.
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, LaunchHostService::class.java.name))
        .daemon(false).tag(key).processNameSuffix(helperName(key)).debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    private val bound = CountDownLatch(1)
    private var didBind = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            host = binder?.takeIf { it.pingBinder() }?.let(ILaunchHost.Stub::asInterface)
            bound.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { host = null }
    }

    // --- root path ---
    private var suProcess: Process? = null
    private var receiver: BroadcastReceiver? = null

    /** Connects via Shizuku if available, else root. Blocks up to a few seconds; false if neither works. */
    fun connect(): Boolean {
        if (CaptureMethods.shizukuAvailable()) return connectShizuku()
        return connectRoot()
    }

    private fun connectShizuku(): Boolean {
        runCatching { Shizuku.bindUserService(args, connection); didBind = true }
            .onFailure { Log.w(TAG, "Shizuku bind failed", it); return false }
        return bound.await(8, TimeUnit.SECONDS) && host != null
    }

    private fun connectRoot(): Boolean {
        val got = CountDownLatch(1)
        // Only our own helper's binder: other launchers' helpers broadcast on the same action.
        val nonce = java.util.UUID.randomUUID().toString()
        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.getStringExtra(LaunchMain.EXTRA_NONCE) != nonce) return
                host = intent.getBundleExtra(LaunchMain.EXTRA_BUNDLE)?.getBinder(LaunchMain.EXTRA_BINDER)
                    ?.let(ILaunchHost.Stub::asInterface)
                runCatching { context.unregisterReceiver(this) }
                receiver = null
                got.countDown()
            }
        }
        receiver = rec
        val filter = IntentFilter(LaunchMain.ACTION_HOST)
        // The broadcast comes from the root process (another uid), so it must be exported.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(rec, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(rec, filter)
        val apk = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH='$apk' app_process / ${LaunchMain::class.java.name} ${context.packageName} $nonce"
        suProcess = runCatching { ProcessBuilder("su", "-c", cmd).start() }
            .onFailure { Log.w(TAG, "su launch failed", it) }.getOrNull() ?: return false
        suProcess?.let { p ->
            Thread { runCatching { p.errorStream.bufferedReader().forEachLine { if (BuildConfig.DEBUG) Log.d(TAG, "su: $it") } } }
                .apply { isDaemon = true }.start()
        }
        return got.await(8, TimeUnit.SECONDS) && host != null
    }

    val connected: Boolean get() = host != null

    /** Whether an app still shows on the display [start] made; true when it can't tell. */
    fun hasApp(): Boolean = runCatching { host?.hasApp() }.onFailure { Log.w(TAG, "hasApp", it) }.getOrNull() ?: true

    /** The app in front on the phone's own screen, or null if unknown. */
    fun topApp(): String? = runCatching { host?.topApp() }.onFailure { Log.w(TAG, "topApp", it) }.getOrNull()

    /** Start [intent] on the display [start] made, in place of the app's start screen; false if it can't. */
    fun openPending(intent: PendingIntent): Boolean =
        runCatching { host?.openPending(intent) ?: false }.onFailure { Log.w(TAG, "openPending", it) }.getOrDefault(false)

    /** The phone's own screen while the car shows it or one of its apps: landscape, awake, panel off; all false, all back. */
    fun phoneScreen(landscape: Boolean, awake: Boolean, panelOff: Boolean) { runCatching { host?.phoneScreen(landscape, awake, panelOff) } }

    /** Create the display into [surface] and launch [pkg] on it. Returns the display id, or -1. */
    fun start(surface: Surface, width: Int, height: Int, dpi: Int, pkg: String, keepDisplayAwake: Boolean): Int =
        runCatching { host?.start(surface, width, height, dpi, pkg, keepDisplayAwake) ?: -1 }
            .onFailure { Log.w(TAG, "start", it) }.getOrDefault(-1)

    fun resize(surface: Surface, width: Int, height: Int, dpi: Int) { runCatching { host?.resize(surface, width, height, dpi) } }
    fun touch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, pointerId: Int) {
        runCatching { host?.touch(downTime, eventTime, action, x, y, pointerId) }
    }
    fun touches(downTime: Long, eventTime: Long, touch: net.prototux.aare.ui.car.Touch) {
        val xy = FloatArray(touch.fingers.size * 2).also { a -> touch.fingers.forEachIndexed { i, f -> a[2 * i] = f.x; a[2 * i + 1] = f.y } }
        runCatching { host?.touches(downTime, eventTime, touch.action, xy, IntArray(touch.fingers.size) { touch.fingers[it].id }) }
    }

    /** Open [url] in [pkg] on the display [start] made, started by the helper; false if it can't. */
    fun openLink(url: String, pkg: String): Boolean =
        runCatching { host?.openLink(url, pkg) ?: false }.onFailure { Log.w(TAG, "openLink", it) }.getOrDefault(false)

    fun key(eventTime: Long, action: Int, keyCode: Int) { runCatching { host?.key(eventTime, action, keyCode) } }

    /** Mirrors the phone screen into [surface]; false if the helper can't. */
    fun mirror(surface: Surface, width: Int, height: Int, dpi: Int): Boolean =
        runCatching { host?.mirror(surface, width, height, dpi) == true }.onFailure { Log.w(TAG, "mirror", it) }.getOrDefault(false)

    fun stopMirror() { runCatching { host?.stopMirror() } }

    fun release() {
        runCatching { host?.stop() }
        // The root helper otherwise keeps running on its looper, a display of the mirror included.
        if (suProcess != null) runCatching { host?.destroy() }
        if (didBind) runCatching { Shizuku.unbindUserService(args, connection, true) }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        runCatching { suProcess?.destroy() }
        suProcess = null
        host = null
    }

    companion object {
        private const val TAG = "AareAppLauncher"

        /** In place of a package for [start]: the phone's own desktop, its home screen and any app, at the car screen's size. */
        const val PHONE_DESKTOP = ":desktop"

        /** A process name suffix for the helper of [key]: letters, digits and underscores only. */
        internal fun helperName(key: String) = key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        /** Whether a privileged backend (Shizuku or allowed root) is available to run the launcher. */
        fun available(context: Context): Boolean =
            CaptureMethods.shizukuAvailable() || CaptureMethods.rootAvailable(context)
    }
}
