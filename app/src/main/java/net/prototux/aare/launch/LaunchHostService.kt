package net.prototux.aare.launch

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.TimeUnit
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

    constructor() : this(null)

    /** Shizuku passes a Context when the service has this constructor; the su helper passes null. */
    constructor(context: Context?) {
        this.injectedContext = context
        exemptHiddenApis()
    }

    override fun start(surface: Surface, width: Int, height: Int, dpi: Int, packageName: String): Int {
        return asHelper {
            stopDisplay()
            val ctx = shellContext() ?: return@asHelper -1
            val vd = createDisplay(ctx, surface, width, height, dpi) ?: return@asHelper -1
            display = vd
            displayId = vd.display.displayId
            if (!launch(ctx, packageName, displayId)) {
                Log.w(TAG, "created display ${displayId} but could not launch $packageName on it")
                stopDisplay()
                return@asHelper -1
            }
            displayId
        }
    }

    override fun resize(surface: Surface, width: Int, height: Int, dpi: Int) {
        asHelper {
            val vd = display ?: return@asHelper
            runCatching { vd.resize(width, height, dpi) }
            runCatching { vd.surface = surface }
        }
    }

    override fun touch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, pointerId: Int) = asHelper {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        ev.source = SOURCE_TOUCHSCREEN
        setDisplayId(ev, displayId)
        inject(ev)
        ev.recycle()
    }

    override fun key(eventTime: Long, action: Int, keyCode: Int) = asHelper {
        val ev = KeyEvent(eventTime, eventTime, action, keyCode, 0, 0, -1, 0, 0, SOURCE_KEYBOARD)
        setDisplayId(ev, displayId)
        inject(ev)
    }

    override fun stop() = asHelper { stopDisplay() }

    private fun stopDisplay() {
        display?.let { runCatching { it.release() } }
        display = null
        displayId = -1
    }

    override fun destroy() {
        asHelper { stopDisplay() }
        exitProcess(0)
    }

    // ---------------------------------------------------------------- privileged plumbing (reflection)

    /** Build the virtual display. Trusted + its own display group lets an arbitrary app run and focus on it. */
    private fun createDisplay(ctx: Context, surface: Surface, w: Int, h: Int, dpi: Int): VirtualDisplay? {
        // ContextWrapper.getSystemService() delegates to its base context, so the returned manager
        // would still carry AARE's package as the Android 17 display owner. Construct the manager
        // with our shell-attributed wrapper, as scrcpy does for its interactive virtual display.
        val dm = runCatching {
            DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }.newInstance(ctx)
        }.onFailure { Log.w(TAG, "could not construct shell DisplayManager", it) }.getOrNull()
            ?: return null
        val flags = displayFlags()
        return runCatching { dm.createVirtualDisplay("AARE app", w, h, dpi.coerceAtLeast(120), surface, flags) }
            .onFailure { Log.w(TAG, "createVirtualDisplay(flags=$flags) failed", it) }
            .getOrNull()
    }

    /** Launch [pkg]'s main activity onto [onDisplay]. */
    @SuppressLint("QueryPermissionsNeeded")
    private fun launch(ctx: Context, pkg: String, onDisplay: Int): Boolean = runCatching {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return false
        val component = intent.component ?: return false
        val command = mutableListOf(
            "cmd", "activity", "start-activity",
            "--user", (Process.myUid() / PER_USER_RANGE).toString(),
            "--display", onDisplay.toString(),
        )
        intent.action?.let { command += listOf("-a", it) }
        intent.categories.orEmpty().forEach { command += listOf("-c", it) }
        command += listOf("-f", "0x${intent.flags.toString(16)}", "-n", component.flattenToString())
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            Log.w(TAG, "activity command timed out for $pkg")
            return false
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.exitValue() != 0 || output.contains("Error:", ignoreCase = true) ||
            output.contains("Exception", ignoreCase = true)) {
            Log.w(TAG, "activity command failed for $pkg (${process.exitValue()}): $output")
            return false
        }
        Log.i(TAG, "launched $pkg on display $onDisplay: $output")
        true
    }.onFailure { Log.w(TAG, "startActivity($pkg) failed", it) }.getOrDefault(false)

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
            cls.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
                .newInstance(shellContext() ?: error("no shell context"))
        }.onFailure { Log.w(TAG, "could not construct shell InputManager", it) }.getOrNull()
    }

    private fun setDisplayId(event: InputEvent, id: Int) {
        if (id < 0) return
        runCatching { event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, id) }
    }

    /**
     * A binder call keeps the caller's identity. Without clearing it, framework services check
     * AARE's app uid instead of this privileged helper's shell uid and reject display creation,
     * activity launch and input injection. Root helpers drop to shell too: Android grants these
     * capabilities to uid 2000, not implicitly to uid 0.
     */
    private inline fun <T> asHelper(block: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        try {
            if (Process.myUid() == 0) Os.setgid(SHELL_UID).also { Os.setuid(SHELL_UID) }
            return block()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /** A context whose package attribution matches the shell uid used by DisplayManagerService. */
    private fun shellContext(): Context? {
        if (Process.myUid() != SHELL_UID) {
            Log.w(TAG, "launcher helper has uid ${Process.myUid()}, expected shell")
            return null
        }
        val base = injectedContext ?: systemContext() ?: return null
        val packageContext = runCatching {
            base.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure { Log.w(TAG, "could not create shell package context", it) }.getOrNull()
            ?: return null
        return ShellContext(packageContext)
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        @RequiresApi(Build.VERSION_CODES.S)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
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
                "Landroid/hardware/display/DisplayManager;", "Landroid/view/MotionEvent;", "Landroid/view/KeyEvent;",
            )
        }
    }

    private companion object {
        const val TAG = "AareLaunchHost"
        const val SOURCE_TOUCHSCREEN = 0x00001002 // InputDevice.SOURCE_TOUCHSCREEN
        const val SOURCE_KEYBOARD = 0x00000101 // InputDevice.SOURCE_KEYBOARD
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        const val SHELL_UID = 2000
        const val PER_USER_RANGE = 100_000
        const val SHELL_PACKAGE = "com.android.shell"

        // VirtualDisplay flags used by scrcpy's interactive new-display implementation.
        const val FLAG_PUBLIC = 1 shl 0
        const val FLAG_PRESENTATION = 1 shl 1
        const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val FLAG_TRUSTED = 1 shl 10 // hidden, API 29+
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11 // hidden, API 31+
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12 // hidden, API 30+
        const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val FLAG_OWN_FOCUS = 1 shl 14
        const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

        fun displayFlags(sdk: Int = Build.VERSION.SDK_INT): Int {
            var flags = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or
                FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= 33) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
                FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
            if (sdk >= 34) flags = flags or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP
            return flags
        }
    }
}
