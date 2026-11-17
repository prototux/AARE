package net.prototux.aare.launch

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
class AppLauncher(private val context: Context) {
    @Volatile private var host: ILaunchHost? = null

    // --- Shizuku path ---
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, LaunchHostService::class.java.name))
        .daemon(false).processNameSuffix("launch").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
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
        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                host = intent?.getBundleExtra(LaunchMain.EXTRA_BUNDLE)?.getBinder(LaunchMain.EXTRA_BINDER)
                    ?.let(ILaunchHost.Stub::asInterface)
                got.countDown()
            }
        }
        receiver = rec
        val filter = IntentFilter(LaunchMain.ACTION_HOST)
        // The broadcast comes from the root process (another uid), so it must be exported.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(rec, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(rec, filter)
        val apk = context.applicationInfo.sourceDir
        val cmd = "CLASSPATH='$apk' app_process / ${LaunchMain::class.java.name} ${context.packageName}"
        suProcess = runCatching { ProcessBuilder("su", "-c", cmd).start() }
            .onFailure { Log.w(TAG, "su launch failed", it) }.getOrNull() ?: return false
        suProcess?.let { p ->
            Thread { runCatching { p.errorStream.bufferedReader().forEachLine { if (BuildConfig.DEBUG) Log.d(TAG, "su: $it") } } }
                .apply { isDaemon = true }.start()
        }
        return got.await(8, TimeUnit.SECONDS) && host != null
    }

    val connected: Boolean get() = host != null

    /** Create the display into [surface] and launch [pkg] on it. Returns the display id, or -1. */
    fun start(surface: Surface, width: Int, height: Int, dpi: Int, pkg: String, keepDisplayAwake: Boolean): Int =
        runCatching { host?.start(surface, width, height, dpi, pkg, keepDisplayAwake) ?: -1 }
            .onFailure { Log.w(TAG, "start", it) }.getOrDefault(-1)

    fun resize(surface: Surface, width: Int, height: Int, dpi: Int) { runCatching { host?.resize(surface, width, height, dpi) } }
    fun touch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, pointerId: Int) {
        runCatching { host?.touch(downTime, eventTime, action, x, y, pointerId) }
    }
    fun key(eventTime: Long, action: Int, keyCode: Int) { runCatching { host?.key(eventTime, action, keyCode) } }

    fun release() {
        runCatching { host?.stop() }
        if (didBind) runCatching { Shizuku.unbindUserService(args, connection, true) }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        runCatching { suProcess?.destroy() }
        suProcess = null
        host = null
    }

    companion object {
        private const val TAG = "AareAppLauncher"
        /** Whether a privileged backend (Shizuku or allowed root) is available to run the launcher. */
        fun available(context: Context): Boolean =
            CaptureMethods.shizukuAvailable() || CaptureMethods.rootAvailable(context)
    }
}
