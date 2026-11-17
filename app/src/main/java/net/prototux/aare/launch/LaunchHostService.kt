package net.prototux.aare.launch

import android.os.IBinder
import android.os.Bundle
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.os.Handler
import android.os.HandlerThread
import android.content.Context
import android.content.Intent
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
    @Volatile private var mirrorDisplay: VirtualDisplay? = null
    @Volatile private var displayWakeLock: PowerManager.WakeLock? = null
    private var unwatchScreen: (() -> Unit)? = null
    /** Where the phone's display changes arrive: a thread of its own, this helper has no main loop running. */
    private val screenHandler by lazy { Handler(HandlerThread("aare-screen").apply { start() }.looper) }
    /** A wake is under way: the phone's display changes it brings are not the power key. */
    @Volatile private var waking = false

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
        val ctx = callerContext() ?: return -1
        val desktop = packageName == AppLauncher.PHONE_DESKTOP
        val vd = createDisplay(ctx, surface, width, height, dpi, desktop) ?: return -1
        display = vd
        displayId = vd.display.displayId
        keyboardOn(displayId)
        if (keepDisplayAwake) enableKeepAwakeFallback(ctx, displayId)
        // The phone's own desktop: Android puts its home screen and navigation bar there by itself.
        if (desktop) return displayId
        if (!launch(ctx, packageName, displayId)) {
            // Left empty the display would only stay black: say it failed instead.
            Log.w(TAG, "created display ${displayId} but could not launch $packageName on it")
            stop()
            return -1
        }
        return displayId
    }

    override fun resize(surface: Surface, width: Int, height: Int, dpi: Int) {
        val vd = display ?: return
        runCatching { vd.resize(width, height, dpi.coerceAtLeast(MIN_DPI)) }
        runCatching { vd.surface = surface }
    }

    override fun touch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, pointerId: Int) {
        // A finger, as the phone's own screen reports one: Compose apps (Claude) ignore a tap of unknown tool type.
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val ev = MotionEvent.obtain(downTime, eventTime, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, SOURCE_TOUCHSCREEN, 0)
        setDisplayId(ev, displayId)
        inject(ev)
        ev.recycle()
    }

    override fun touches(downTime: Long, eventTime: Long, action: Int, xy: FloatArray, ids: IntArray) {
        val props = Array(ids.size) { i -> MotionEvent.PointerProperties().apply { id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(ids.size) { i -> MotionEvent.PointerCoords().apply { x = xy[2 * i]; y = xy[2 * i + 1]; pressure = 1f; size = 1f } }
        val ev = MotionEvent.obtain(downTime, eventTime, action, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0, SOURCE_TOUCHSCREEN, 0)
        setDisplayId(ev, displayId)
        inject(ev)
        ev.recycle()
    }

    override fun openLink(url: String, packageName: String): Boolean {
        val id = displayId
        if (id < 0) return false
        val ctx = callerContext() ?: return false
        // From here, not through AARE: with the car's screen only on a display of its own, AARE counts as in the
        // background, and Android blocks the start it asks for.
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Afresh, the app shows only what the link opens: Google Maps ignores a new destination while it guides.
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
        return startAsCaller(ctx, packageName, id, intent, opts) ?: runCatching { ctx.startActivity(intent, opts); true }
            .onFailure { Log.w(TAG, "openLink($packageName) failed: ${it.message}") }.getOrDefault(false)
    }

    override fun key(eventTime: Long, action: Int, keyCode: Int) {
        val ev = KeyEvent(eventTime, eventTime, action, keyCode, 0, 0, -1, 0, 0, SOURCE_KEYBOARD)
        setDisplayId(ev, displayId)
        inject(ev)
    }

    @Synchronized
    override fun stop() {
        phoneScreen(false, false, false)
        unwatchScreen?.let { runCatching { it() } }
        unwatchScreen = null
        displayWakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        displayWakeLock = null
        display?.let { runCatching { it.release() } }
        display = null
        displayId = -1
    }

    override fun mirror(surface: Surface, width: Int, height: Int, dpi: Int): Boolean {
        mirrorDisplay?.let { vd ->
            runCatching { vd.resize(width, height, dpi.coerceAtLeast(MIN_DPI)) }
            runCatching { vd.surface = surface }
            return true
        }
        val ctx = callerContext() ?: return false
        mirrorDisplay = createMirror(ctx, surface, width, height, dpi)
        return mirrorDisplay != null
    }

    // Not the phone's landscape, wake lock and panel: the car screen that set them puts them back
    // (phoneScreen), and stop() when the helper ends. A surface gone for a moment would drop them for good.
    override fun stopMirror() {
        mirrorDisplay?.let { runCatching { it.release() } }
        mirrorDisplay = null
    }

    /** The rotation setting before [phoneScreen] turned the phone, to put back ("free" or "lock N"); null when untouched. */
    private var savedRotation: String? = null
    @Volatile private var panelOff = false
    private var phoneAwake: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    @Synchronized
    override fun phoneScreen(landscape: Boolean, awake: Boolean, panelOff: Boolean) {
        if (landscape && savedRotation == null) {
            // Unreadable, it is left alone: there would be nothing right to put back.
            savedRotation = savedRotation(shell("cmd", "window", "user-rotation"))
            savedRotation?.let { Log.i(TAG, "phone screen: landscape, rotation was '$it': ${shell("cmd", "window", "user-rotation", "lock", "1").trim()}") }
        } else if (!landscape) {
            savedRotation?.let { Log.i(TAG, "phone screen: rotation back to '$it': ${shell(*("cmd window user-rotation $it").split(' ').toTypedArray()).trim()}") }
            savedRotation = null
        }
        if (awake && phoneAwake == null) {
            // Untouched, the phone would time out and lock, and what the car shows with it.
            phoneAwake = runCatching {
                callerContext()?.getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "aare:phone-screen")?.apply { acquire() }
            }.onFailure { Log.w(TAG, "phone screen wake lock failed: ${it.message}") }.getOrNull()
        } else if (!awake) {
            phoneAwake?.let { runCatching { if (it.isHeld) it.release() } }
            phoneAwake = null
        }
        if (switchesPanel(panelOff, this.panelOff) && setPanel(!panelOff)) this.panelOff = panelOff
    }

    /**
     * The phone's panel on or off while Android keeps drawing its screen, like scrcpy's
     * --turn-screen-off: the mirror goes on, and the power key brings the panel back. Android 15+.
     */
    private fun setPanel(on: Boolean): Boolean = runCatching {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "display") as IBinder
        val manager = Class.forName("android.hardware.display.IDisplayManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val method = manager.javaClass.methods.first { it.name == "requestDisplayPower" && it.parameterTypes.size == 2 }
        // Android 15 takes on/off, later versions a Display.STATE_ (2 on, 1 off).
        val state: Any = if (method.parameterTypes[1] == Boolean::class.javaPrimitiveType) on else if (on) 2 else 1
        method.invoke(manager, android.view.Display.DEFAULT_DISPLAY, state) as? Boolean ?: true
    }.onFailure { Log.w(TAG, "phone panel ${if (on) "on" else "off"} failed: ${it.message}") }.getOrDefault(false)

    /** Runs a shell command as this helper and returns its output. */
    private fun shell(vararg command: String): String = runCatching {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
    }.onFailure { Log.w(TAG, "${command.joinToString(" ")} failed: ${it.message}") }.getOrDefault("")

    override fun destroy() {
        stop()
        stopMirror()
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

        // The phone's own display says when it goes to sleep: a screen-off broadcast receiver never
        // got registered in this helper process. Only from Android 17, where this display goes dark with
        // the phone's: before, it has a group of its own, and a screen timeout isn't the power key.
        if (Build.VERSION.SDK_INT >= 37) runCatching {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(changed: Int) = Unit
                override fun onDisplayRemoved(changed: Int) = Unit
                override fun onDisplayChanged(changed: Int) {
                    if (changed != android.view.Display.DEFAULT_DISPLAY || waking || pm.isInteractive) return
                    // The power key: with the panel lit, the driver locks the phone, so it wakes with the
                    // panel dark; with the panel dark, the driver wants the phone, so it wakes lit.
                    wakePhysicalDisplay(id, dark = !panelOff)
                }
            }
            dm.registerDisplayListener(listener, screenHandler)
            unwatchScreen = { dm.unregisterDisplayListener(listener) }
            Log.i(TAG, "watching the phone screen for display $id")
        }.onFailure { Log.w(TAG, "could not watch physical screen state", it) }

        val phoneIsAwake = runCatching {
            ctx.getSystemService(PowerManager::class.java).isInteractive
        }.getOrDefault(true)
        if (!phoneIsAwake) wakePhysicalDisplay(id, dark = true)
    }

    /**
     * Wakes the phone, which keeps the hosted display on: Android 17 puts it in the phone's display
     * group whatever its flags, and switches it off with the phone. [dark]: the panel then goes off
     * again, so the phone stays dark and locked while the car shows the app.
     */
    private fun wakePhysicalDisplay(virtualDisplayId: Int, dark: Boolean) {
        if (!dark) panelOff = false
        waking = true
        Thread {
            try { runCatching {
                Thread.sleep(500)
                val process = ProcessBuilder("/system/bin/cmd", "power", "wakeup")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText().trim() }
                val exit = process.waitFor()
                if (exit == 0) Log.i(TAG, "woke phone to keep display $virtualDisplayId active")
                else Log.w(TAG, "phone wake failed for display $virtualDisplayId (exit=$exit): $output")
                if (dark && exit == 0) {
                    // After the wake has lit the panel: switched off before, the wake would light it again.
                    Thread.sleep(500)
                    // Not once stop() ran: it lit the panel and would leave it dark after the session.
                    synchronized(this) { if (displayId == virtualDisplayId && setPanel(false)) panelOff = true }
                }
            }.onFailure { Log.w(TAG, "phone wake failed for display $virtualDisplayId", it) } } finally { waking = false }
        }.apply { name = "AARE-display-wakeup"; isDaemon = true }.start()
    }

    /** Build the virtual display. Trusted + its own display group lets an arbitrary app run and focus on it. */
    private fun createDisplay(ctx: Context, surface: Surface, w: Int, h: Int, dpi: Int, desktop: Boolean = false): VirtualDisplay? {
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return null
        // Preferred flags for hosting a foreign app interactively; fall back to the plain public ones
        // if the framework rejects the hidden bits for this uid/version.
        // Only its own content: a public display without it mirrors the phone's screen while empty,
        // and Android then also puts it in the phone's display group.
        val full = FULL_FLAGS or (if (Build.VERSION.SDK_INT >= 34) FLAG_OWN_FOCUS else 0) or (if (desktop) FLAG_SYSTEM_DECORATIONS else 0)
        for (flags in intArrayOf(full, FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY)) {
            val vd = runCatching { dm.createVirtualDisplay("AARE app", w, h, dpi.coerceAtLeast(MIN_DPI), surface, flags) }
                .onFailure { Log.w(TAG, "createVirtualDisplay(flags=$flags) failed: ${it.message}") }
                .getOrNull()
            if (vd != null) return vd
        }
        return null
    }

    /**
     * A display showing the phone's screen. Android 14+ has a hidden helper for exactly this (the
     * one scrcpy uses); before that, an auto-mirroring display, which adb-level rights allow.
     */
    private fun createMirror(ctx: Context, surface: Surface, w: Int, h: Int, dpi: Int): VirtualDisplay? {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                val int = Int::class.javaPrimitiveType
                DisplayManager::class.java.getMethod("createVirtualDisplay", String::class.java, int, int, int, Surface::class.java)
                    .invoke(null, "AARE mirror", w, h, android.view.Display.DEFAULT_DISPLAY, surface) as VirtualDisplay?
            }.onFailure { Log.w(TAG, "mirror helper failed: ${it.message}") }.getOrNull()?.let { return it }
        }
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return null
        return runCatching { dm.createVirtualDisplay("AARE mirror", w, h, dpi.coerceAtLeast(MIN_DPI), surface, FLAG_PUBLIC or FLAG_AUTO_MIRROR) }
            .onFailure { Log.w(TAG, "mirror display failed: ${it.message}") }.getOrNull()
    }

    override fun hasApp(): Boolean = displayId.let { id ->
        id >= 0 && displayHasActivity(shell("dumpsys", "activity", "activities"), id)
    }

    override fun topApp(): String? = runCatching {
        topResumedPackage(shell("dumpsys", "activity", "activities"))
    }.onFailure { Log.w(TAG, "top app unknown: ${it.message}") }.getOrNull()

    override fun openPending(intent: PendingIntent): Boolean {
        val id = displayId
        if (id < 0) return false
        return runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(id)
            // Sent from here, not from the app: Android lets it start only when the sender says so.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            intent.send(null, 0, null, null, null, null, opts.toBundle())
            true
        }.onFailure { Log.w(TAG, "openPending on display $id failed: ${it.message}") }.getOrDefault(false)
    }

    /**
     * The keyboard of an app on [id] shows there, in the car, not on the phone's screen: Android
     * keeps it on the phone for a display of this kind. Like scrcpy's --display-ime-policy=local.
     */
    private fun keyboardOn(id: Int) = runCatching {
        // Straight from the service manager: WindowManagerGlobal fails in this process on Android 17
        // ("ApplicationSharedMemory not initialized"), as no app was ever set up in it.
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window") ?: return@runCatching
        val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder) ?: return@runCatching
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, id, DISPLAY_IME_POLICY_LOCAL)
        } else {
            wm.javaClass.getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(wm, id, true)
        }
    }.onFailure { Log.w(TAG, "keyboard on display $id: ${it.cause ?: it}") }

    /** Launch [pkg]'s main activity onto [onDisplay]. */
    @SuppressLint("QueryPermissionsNeeded")
    private fun launch(ctx: Context, pkg: String, onDisplay: Int): Boolean {
        // A new task, not a cleared one: an app already playing keeps playing.
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(onDisplay).toBundle()
        // Hidden API gone or changed: the plain call still works where the caller owns its package (root).
        return startAsCaller(ctx, pkg, onDisplay, intent, opts) ?: runCatching { ctx.startActivity(intent, opts); true }
            .onFailure { Log.w(TAG, "startActivity($pkg) failed: ${it.message}") }.getOrDefault(false)
    }

    /** Started through the activity manager as [ctx]'s package: the result, or null when the hidden call isn't there. */
    private fun startAsCaller(ctx: Context, pkg: String, onDisplay: Int, intent: Intent, opts: Bundle?): Boolean? = runCatching {
        // Straight to the activity manager with [ctx]'s package as the caller: Context.startActivity
        // sends AARE's package, which the shell uid doesn't own, and is refused. Like scrcpy.
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val int = Int::class.javaPrimitiveType
        val start = am.javaClass.getMethod(
            "startActivityAsUser", Class.forName("android.app.IApplicationThread"), String::class.java, Intent::class.java,
            String::class.java, IBinder::class.java, String::class.java, int, int, Class.forName("android.app.ProfilerInfo"), Bundle::class.java, int,
        )
        val result = start.invoke(am, null, ctx.packageName, intent, null, null, null, 0, 0, null, opts, USER_CURRENT) as Int
        if (result < 0) Log.w(TAG, "startActivity($pkg) on display $onDisplay: result $result")
        result >= 0
    }.onFailure { Log.w(TAG, "startActivityAsUser($pkg) unavailable: ${it.message}") }.getOrNull()

    /** Inject one event into the launched app's display via InputManager's hidden injectInputEvent. */
    private fun inject(event: InputEvent) {
        runCatching {
            val im = inputManager ?: return
            val method = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            method.invoke(im, event, INJECT_INPUT_EVENT_MODE_ASYNC)
        }.onFailure { Log.w(TAG, "injectInputEvent failed: ${it.message}") }
    }

    /**
     * The hidden handle that exposes injectInputEvent: InputManagerGlobal from Android 14 (Android 17
     * dropped InputManager.getInstance()), InputManager.getInstance() before.
     */
    private val inputManager: Any? by lazy {
        listOf("android.hardware.input.InputManagerGlobal", "android.hardware.input.InputManager").firstNotNullOfOrNull { name ->
            runCatching { Class.forName(name).getMethod("getInstance").invoke(null) }
                .onFailure { Log.w(TAG, "$name.getInstance failed: ${it.message}") }.getOrNull()
        }
    }

    private fun setDisplayId(event: InputEvent, id: Int) {
        if (id < 0) return
        runCatching { event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, id) }
    }

    /**
     * The context to call the framework with. Under Shizuku this process runs as the shell (uid
     * 2000) while the injected context names AARE's package, and the display service refuses a
     * package that isn't the caller's ("packageName must match the owner uid"): the shell's own
     * package context, then.
     */
    private fun callerContext(): Context? {
        val ctx = injectedContext ?: systemContext() ?: return null
        if (android.os.Process.myUid() != SHELL_UID) return ctx
        return runCatching { ctx.createPackageContext("com.android.shell", 0) }
            .onFailure { Log.w(TAG, "no shell context: ${it.message}") }.getOrDefault(ctx)
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
                "Landroid/app/ActivityThread;", "Landroid/app/ActivityManager;", "Landroid/app/IActivityManager", "Landroid/app/IApplicationThread;",
                "Landroid/app/ProfilerInfo;", "Landroid/hardware/input/InputManager;", "Landroid/hardware/input/InputManagerGlobal;",
                "Landroid/hardware/display/DisplayManager;", "Landroid/view/WindowManagerGlobal;", "Landroid/view/IWindowManager",
                "Landroid/os/ServiceManager;", "Landroid/os/PowerManager;", "Landroid/view/MotionEvent;", "Landroid/view/KeyEvent;",
                "Landroid/hardware/display/IDisplayManager",
            )
        }
    }

    private companion object {
        const val TAG = "AareLaunchHost"
        const val SHELL_UID = 2000
        const val SOURCE_TOUCHSCREEN = 0x00001002 // InputDevice.SOURCE_TOUCHSCREEN
        const val SOURCE_KEYBOARD = 0x00000101 // InputDevice.SOURCE_KEYBOARD
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

        // VirtualDisplay flags (public + hidden bits). Hidden ones let a foreign app run and take focus.
        const val FLAG_PUBLIC = 1 shl 0
        const val FLAG_PRESENTATION = 1 shl 1
        const val FLAG_AUTO_MIRROR = 1 shl 4
        const val FLAG_SYSTEM_DECORATIONS = 1 shl 9 // hidden: a home screen and navigation bar of its own
        const val FLAG_TRUSTED = 1 shl 10 // hidden, API 29+
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11 // hidden, API 31+
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12 // hidden, API 30+
        const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        const val FLAG_OWN_FOCUS = 1 shl 14 // hidden, API 34+: touches here leave the phone's focus alone
        const val FULL_FLAGS = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
        const val USER_CURRENT = -2
        const val DISPLAY_IME_POLICY_LOCAL = 0 // WindowManager.DISPLAY_IME_POLICY_LOCAL, hidden
        /** The lowest density a display gets, on creation and on resize alike. */
        const val MIN_DPI = 120
    }
}

/**
 * The package of the activity resumed on top of the phone's own screen (display 0), from
 * `dumpsys activity activities`; null when the dump names none.
 */
/** Whether `dumpsys activity activities` lists an activity on the display [id]. */
internal fun displayHasActivity(dump: String, id: Int): Boolean {
    val start = dump.indexOf("Display #$id ")
    if (start < 0) return false
    val next = dump.indexOf("Display #", start + 1).let { if (it < 0) dump.length else it }
    return "ActivityRecord{" in dump.substring(start, next)
}

internal fun topResumedPackage(dump: String): String? {
    val display = dump.indexOf("Display #0 ")
    if (display < 0) return null
    val next = dump.indexOf("Display #", display + 1).let { if (it < 0) dump.length else it }
    return Regex("""topResumedActivity=ActivityRecord\{\S+ u\d+ ([\w.]+)/""").find(dump.substring(display, next))?.groupValues?.get(1)
}

/** What `cmd window user-rotation` printed, as the arguments that put it back ("free" or "lock N"); null for anything else. */
/**
 * Whether the panel is switched for [off] asked while it was set [wasOff]: on a change, and a dark
 * panel asked again too, as the power key may have lit it since.
 */
internal fun switchesPanel(off: Boolean, wasOff: Boolean) = off || off != wasOff

internal fun savedRotation(output: String): String? =
    output.trim().lowercase().takeIf { Regex("free|lock [0-3]").matches(it) }
