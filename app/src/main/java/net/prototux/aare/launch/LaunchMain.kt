package net.prototux.aare.launch

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Root entry point for the app launcher, run through
 * `su -c "CLASSPATH=<apk> app_process / net.prototux.aare.launch.LaunchMain <appPackage>"`, the same
 * way [net.prototux.aare.audio.SuAudioMain] runs the audio helper. It builds a [LaunchHostService]
 * and hands its binder back to the app with a targeted broadcast (the libsu way — a LocalSocket
 * can't carry a live binder), then stays alive on a Looper to serve it.
 *
 * Kept out of R8 by the release build having no minification (see SuAudioMain's note), so the class
 * and method survive under this exact name for app_process to find.
 */
object LaunchMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val appPackage = args.getOrNull(0) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityThread;") }
        Looper.prepareMainLooper()
        val service = LaunchHostService(null)
        val ctx = systemContext()
        if (ctx != null) {
            val bundle = Bundle().apply { putBinder(EXTRA_BINDER, service) }
            val intent = Intent(ACTION_HOST).setPackage(appPackage).putExtra(EXTRA_BUNDLE, bundle)
            runCatching { ctx.sendBroadcast(intent) }.onFailure { Log.w(TAG, "could not hand the binder back", it) }
        }
        Looper.loop()
    }

    @SuppressLint("PrivateApi")
    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as Context
    }.onFailure { Log.w(TAG, "no system context", it) }.getOrNull()

    const val ACTION_HOST = "net.prototux.aare.LAUNCH_HOST"
    const val EXTRA_BUNDLE = "bundle"
    const val EXTRA_BINDER = "host"
    private const val TAG = "AareLaunchMain"
}
