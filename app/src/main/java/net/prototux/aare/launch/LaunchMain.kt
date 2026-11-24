package net.prototux.aare.launch

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.Log
import net.prototux.aare.root.RootHandover
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Root entry point for the app launcher, run through
 * `su -c "CLASSPATH=<apk> app_process / net.prototux.aare.launch.LaunchMain <appPackage>"`, the same
 * way [net.prototux.aare.audio.SuAudioMain] runs the audio helper. It builds a [LaunchHostService]
 * and hands its binder back to the app with a targeted broadcast carrying the token AARE wrote on
 * its stdin ([RootHandover]: a LocalSocket can't carry a live binder), then stays alive on a Looper
 * to serve it, until AARE closes its stdin.
 *
 * Kept out of R8 by the release build having no minification (see SuAudioMain's note), so the class
 * and method survive under this exact name for app_process to find.
 */
object LaunchMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val appPackage = args.getOrNull(0) ?: return
        val token = RootHandover.readToken() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityThread;") }
        Looper.prepareMainLooper()
        val service = LaunchHostService(null)
        val ctx = systemContext()
        if (ctx != null) RootHandover.handBack(ctx, ACTION_HOST, appPackage, token, service)
        RootHandover.exitWithApp()
        Looper.loop()
    }

    @SuppressLint("PrivateApi")
    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as Context
    }.onFailure { Log.w(TAG, "no system context", it) }.getOrNull()

    const val ACTION_HOST = "net.prototux.aare.LAUNCH_HOST"
    private const val TAG = "AareLaunchMain"
}
