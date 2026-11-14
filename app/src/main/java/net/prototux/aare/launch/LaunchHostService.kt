package net.prototux.aare.launch

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import net.prototux.aare.BuildConfig
import net.prototux.aare.host.CarLinks
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess

/**
 * Puts an ordinary phone app on the car screen, from a process with adb-level (Shizuku) or root
 * rights. It creates a virtual display that renders into the car screen's [Surface], launches the
 * chosen app there, and injects the car's touches and keys into that display.
 *
 * DEVICE-TESTED, NOT verified here: creating a display an arbitrary app can run on
 * (VIRTUAL_DISPLAY_FLAG_TRUSTED and friends) and injecting input into it use hidden framework APIs
 * whose exact shape and permission requirements vary by Android version and by whether this runs as
 * root (su) or shell (Shizuku). Everything is wrapped so a failure logs and degrades rather than
 * crashing, but the privileged path WILL need on-device iteration. This is the scrcpy approach,
 * adapted to feed AARE's car screen.
 */
class LaunchHostService : ILaunchHost.Stub {
    private val injectedContext: Context?
    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var displayId: Int = -1
    @Volatile private var displayWakeLock: PowerManager.WakeLock? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var receiverContext: Context? = null

    constructor() : this(null)

    /** Shizuku passes a Context when the service has this constructor; the su helper passes null. */
    constructor(context: Context?) {
        this.injectedContext = context
        exemptHiddenApis()
    }

    override fun start(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        packageName: String,
        keepDisplayAwake: Boolean,
    ): Int {
        stop()
        approveCarLinks()
        val ctx = injectedContext ?: systemContext() ?: return -1
        val vd = createDisplay(ctx, surface, width, height, dpi) ?: return -1
        display = vd
        displayId = vd.display.displayId
        if (keepDisplayAwake) enableKeepAwakeFallback(ctx, displayId)
        if (!launch(ctx, packageName, displayId)) {
            Log.w(TAG, "created display ${displayId} but could not launch $packageName on it")
        }
        return displayId
    }

    override fun resize(surface: Surface, width: Int, height: Int, dpi: Int) {
        val vd = display ?: return
        runCatching { vd.resize(width, height, dpi) }
        runCatching { vd.surface = surface }
    }

    override fun touch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, pointerId: Int) {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        ev.source = SOURCE_TOUCHSCREEN
        setDisplayId(ev, displayId)
        inject(ev)
        ev.recycle()
    }

    override fun key(eventTime: Long, action: Int, keyCode: Int) {
        val ev = KeyEvent(eventTime, eventTime, action, keyCode, 0, 0, -1, 0, 0, SOURCE_KEYBOARD)
        setDisplayId(ev, displayId)
        inject(ev)
    }

    override fun stop() {
        screenOffReceiver?.let { receiver ->
            receiverContext?.let { context -> runCatching { context.unregisterReceiver(receiver) } }
        }
        screenOffReceiver = null
        receiverContext = null
        displayWakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        displayWakeLock = null
        display?.let { runCatching { it.release() } }
        display = null
        displayId = -1
    }

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    // ---------------------------------------------------------------- privileged plumbing (reflection)

    /**
     * Hold the hosted display awake. Some Android 17 devices still suspend it with the physical
     * display, so wake the phone after a power-off event or when starting while it is already off.
     */
    // BlockedPrivateApi: this runs in the privileged helper, which exempts PowerManager from the
    // hidden API checks when it starts (exemptHiddenApis); lint only sees the app's target SDK.
    @SuppressLint("BlockedPrivateApi")
    @Suppress("DEPRECATION")
    private fun enableKeepAwakeFallback(ctx: Context, id: Int) {
        runCatching {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val method = PowerManager::class.java.getDeclaredMethod(
                "newWakeLock",
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val lock = method.invoke(pm, PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "aare:standalone-display", id)
                as PowerManager.WakeLock
            lock.setReferenceCounted(false)
            lock.acquire()
            displayWakeLock = lock
            Log.i(TAG, "holding display wake lock for display $id")
        }.onFailure { Log.w(TAG, "display wake lock failed for display $id", it) }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) wakePhysicalDisplay(id)
            }
        }
        runCatching {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") ctx.registerReceiver(receiver, filter)
            screenOffReceiver = receiver
            receiverContext = ctx
        }.onFailure { Log.w(TAG, "could not watch physical screen state", it) }

        val phoneIsAwake = runCatching {
            ctx.getSystemService(PowerManager::class.java).isInteractive
        }.getOrDefault(true)
        if (!phoneIsAwake) wakePhysicalDisplay(id)
    }

    private fun wakePhysicalDisplay(virtualDisplayId: Int) {
        Thread {
            runCatching {
                Thread.sleep(500)
                val process = ProcessBuilder("/system/bin/cmd", "power", "wakeup")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText().trim() }
                val exit = process.waitFor()
                if (exit == 0) Log.i(TAG, "woke phone to keep display $virtualDisplayId active")
                else Log.w(TAG, "phone wake failed for display $virtualDisplayId (exit=$exit): $output")
            }.onFailure { Log.w(TAG, "phone wake failed for display $virtualDisplayId", it) }
        }.apply { name = "AARE-display-wakeup"; isDaemon = true }.start()
    }

    /** Build the virtual display. Trusted + its own display group lets an arbitrary app run and focus on it. */
    private fun createDisplay(ctx: Context, surface: Surface, w: Int, h: Int, dpi: Int): VirtualDisplay? {
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return null
        // Preferred flags for hosting a foreign app interactively; fall back to the plain public ones
        // if the framework rejects the hidden bits for this uid/version.
        for (flags in intArrayOf(FULL_FLAGS, FLAG_PUBLIC or FLAG_PRESENTATION)) {
            val vd = runCatching { dm.createVirtualDisplay("AARE app", w, h, dpi.coerceAtLeast(120), surface, flags) }
                .onFailure { Log.w(TAG, "createVirtualDisplay(flags=$flags) failed: ${it.message}") }
                .getOrNull()
            if (vd != null) return vd
        }
        return null
    }

    /**
     * Car links tapped in a hosted assistant's reply open in AARE, not the browser: Android leaves that
     * choice to the user, which adb rights may make for them. Runs aside, the launch doesn't wait for it.
     */
    private fun approveCarLinks() = Thread {
        runCatching {
            val pm = ProcessBuilder("pm", "set-app-links-user-selection", "--user", "current", "--package", BuildConfig.APPLICATION_ID, "true", CarLinks.HOST)
                .redirectErrorStream(true).start()
            val out = pm.inputStream.bufferedReader().readText()
            if (pm.waitFor() != 0) Log.w(TAG, "car links not approved: $out")
        }.onFailure { Log.w(TAG, "car links not approved: ${it.message}") }
    }.start()

    /** Launch [pkg]'s main activity onto [onDisplay]. */
    @SuppressLint("QueryPermissionsNeeded")
    private fun launch(ctx: Context, pkg: String, onDisplay: Int): Boolean = runCatching {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return false
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(onDisplay)
        ctx.startActivity(intent, opts.toBundle())
        true
    }.onFailure { Log.w(TAG, "startActivity($pkg) failed: ${it.message}") }.getOrDefault(false)

    /** Inject one event into the launched app's display via InputManager's hidden injectInputEvent. */
    private fun inject(event: InputEvent) {
        runCatching {
            val im = inputManager ?: return
            val method = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            method.invoke(im, event, INJECT_INPUT_EVENT_MODE_ASYNC)
        }.onFailure { Log.w(TAG, "injectInputEvent failed: ${it.message}") }
    }

    /** InputManager.getInstance() (hidden; still the simplest handle that exposes injectInputEvent). */
    private val inputManager: Any? by lazy {
        runCatching {
            val cls = Class.forName("android.hardware.input.InputManager")
            cls.getMethod("getInstance").invoke(null)
        }.onFailure { Log.w(TAG, "InputManager.getInstance failed: ${it.message}") }.getOrNull()
    }

    private fun setDisplayId(event: InputEvent, id: Int) {
        if (id < 0) return
        runCatching { event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, id) }
    }

    /** The system context of this (app_process) process, when Shizuku did not inject one. */
    @SuppressLint("PrivateApi")
    private fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as Context
    }.onFailure { Log.w(TAG, "no system context: ${it.message}") }.getOrNull()

    private fun exemptHiddenApis() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/app/ActivityThread;", "Landroid/hardware/input/InputManager;",
                "Landroid/hardware/display/DisplayManager;", "Landroid/os/PowerManager;",
                "Landroid/view/MotionEvent;", "Landroid/view/KeyEvent;",
            )
        }
    }

    private companion object {
        const val TAG = "AareLaunchHost"
        const val SOURCE_TOUCHSCREEN = 0x00001002 // InputDevice.SOURCE_TOUCHSCREEN
        const val SOURCE_KEYBOARD = 0x00000101 // InputDevice.SOURCE_KEYBOARD
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

        // VirtualDisplay flags (public + hidden bits). Hidden ones let a foreign app run and take focus.
        const val FLAG_PUBLIC = 1 shl 0
        const val FLAG_PRESENTATION = 1 shl 1
        const val FLAG_TRUSTED = 1 shl 10 // hidden, API 29+
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11 // hidden, API 31+
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12 // hidden, API 30+
        const val FULL_FLAGS = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
    }
}
