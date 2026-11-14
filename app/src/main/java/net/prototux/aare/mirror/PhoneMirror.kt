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

/**
 * The phone's own screen, mirrored into a surface of the car screen through the screen-capture
 * consent AARE already holds for car audio.
 *
 * Android 14 lets one consent create a single virtual display, so the display is created once
 * per consent and only its surface and size change afterwards. Main thread only.
 */
object PhoneMirror {
    private const val TAG = "AareMirror"
    private var display: VirtualDisplay? = null
    private var owner: MediaProjection? = null

    /** Mirrors the phone screen into [surface] ([width]×[height] pixels). False without a usable consent. */
    fun attach(projection: MediaProjection?, surface: Surface, width: Int, height: Int, dpi: Int): Boolean {
        if (projection == null) {
            DiagLog.add("phone screen: no screen-capture permission (Car audio > Screen-capture permission on the phone)")
            return false
        }
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

    /** Stops drawing (the display stays, for the next [attach]). */
    fun detach() {
        display?.surface = null
    }

    fun release() {
        runCatching { display?.release() }
        display = null
        owner = null
    }

    /** The phone screen's current size in pixels, rotation included. */
    fun phoneScreenSize(context: Context): Point {
        val d = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        return Point().also { d.getRealSize(it) }
    }
}
