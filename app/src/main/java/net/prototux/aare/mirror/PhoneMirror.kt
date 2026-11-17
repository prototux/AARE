package net.prototux.aare.mirror

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Display
import android.view.Surface
import net.prototux.aare.DiagLog
import net.prototux.aare.launch.AppLauncher
import java.util.concurrent.Executors

/**
 * The phone's own screen, mirrored into a surface of the car screen through the screen-capture
 * consent AARE already holds for car audio, or else through the privileged helper (Shizuku or
 * root) that also hosts phone apps.
 *
 * Android 14 lets one consent create a single virtual display, so the display is created once
 * per consent and only its surface and size change afterwards. Main thread only.
 */
object PhoneMirror {
    private const val TAG = "AareMirror"
    private var display: VirtualDisplay? = null
    private var owner: MediaProjection? = null

    /** Mirrors the phone screen into [surface] ([width]×[height] pixels). False without a consent or a helper. */
    fun attach(context: Context, projection: MediaProjection?, surface: Surface, width: Int, height: Int, dpi: Int): Boolean {
        if (projection == null) return Helper.attach(context, surface, width, height, dpi)
        if (owner !== projection) release()
        val d = display
        if (d != null) {
            d.resize(width, height, dpi)
            d.surface = surface
            return true
        }
        return runCatching {
            display = projection.createVirtualDisplay("aare-phone-screen", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
            owner = projection
        }.onFailure {
            Log.w(TAG, "can't mirror the phone screen", it)
            DiagLog.add("phone screen: Android refused to mirror the screen: ${it.javaClass.simpleName}: ${it.message}")
        }.isSuccess
    }

    /** A car touch for the phone's own screen, at its pixels, through the helper: live, as it happens. False without one. */
    fun touch(context: Context, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): Boolean = Helper.touch(context, downTime, eventTime, action, x, y)

    /** A key (Back) for the phone's own screen, through the helper. False without one. */
    fun key(context: Context, keyCode: Int): Boolean = Helper.key(context, keyCode)

    /** The phone's screen landscape and awake (its panel off with [panelOff]) while shown in the car; inactive, all back. */
    fun phoneScreen(context: Context, active: Boolean, panelOff: Boolean) = Helper.phoneScreen(context, active, panelOff)

    /** The app in front on the phone's own screen, through the helper (blocks); null without one. */
    fun topApp(context: Context): String? = Helper.topApp(context)

    /** Stops drawing (the display stays, for the next [attach]). */
    fun detach() {
        display?.surface = null
        Helper.detach()
    }

    fun release() {
        runCatching { display?.release() }
        display = null
        owner = null
        Helper.release()
    }

    /** Without a consent: the privileged helper mirrors the screen like scrcpy. Its binder calls block, so they run on [worker]. */
    private object Helper {
        private val worker = Executors.newSingleThreadExecutor()
        private var launcher: AppLauncher? = null // worker only

        fun attach(context: Context, surface: Surface, width: Int, height: Int, dpi: Int): Boolean {
            if (!AppLauncher.available(context)) return false
            worker.execute {
                if (connected(context)?.mirror(surface, width, height, dpi) != true) Log.w(TAG, "the helper can't mirror the phone screen")
            }
            return true
        }

        /** The helper, connected again if it went away (its process ended). Worker only. */
        private fun connected(context: Context): AppLauncher? = launcher?.takeIf { it.connected } ?: run {
            launcher?.release()
            AppLauncher(context.applicationContext, "mirror").let { if (it.connect()) it else { it.release(); null } }.also { launcher = it }
        }

        fun touch(context: Context, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): Boolean {
            if (!AppLauncher.available(context)) return false
            // No display of its own: the helper's touches land on the phone's screen.
            worker.execute { connected(context)?.touch(downTime, eventTime, action, x, y, 0) }
            return true
        }

        fun key(context: Context, keyCode: Int): Boolean {
            if (!AppLauncher.available(context)) return false
            worker.execute {
                connected(context)?.let { l ->
                    val t = android.os.SystemClock.uptimeMillis()
                    l.key(t, android.view.KeyEvent.ACTION_DOWN, keyCode)
                    l.key(t, android.view.KeyEvent.ACTION_UP, keyCode)
                }
            }
            return true
        }

        fun phoneScreen(context: Context, active: Boolean, panelOff: Boolean) {
            if (!AppLauncher.available(context)) return
            worker.execute { connected(context)?.phoneScreen(active, active, active && panelOff) }
        }

        /** The app in front on the phone's own screen, asked of the helper; blocks, so not on the main thread. */
        fun topApp(context: Context): String? =
            if (!AppLauncher.available(context)) null else runCatching { worker.submit<String?> { connected(context)?.topApp() }.get() }.getOrNull()

        fun detach() = worker.execute { launcher?.stopMirror() }

        fun release() = worker.execute { launcher?.release(); launcher = null }
    }

    /** The phone screen's current size in pixels, rotation included. */
    fun phoneScreenSize(context: Context): Point {
        val d = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        return Point().also { d.getRealSize(it) }
    }
}
