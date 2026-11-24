package net.prototux.aare.launch

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
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
    @Volatile private var keyboardInCar = false
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
        val ctx = callerContext() ?: return -1
        val vd = createDisplay(ctx, surface, width, height, dpi) ?: return -1
        display = vd
        displayId = vd.display.displayId
        keyboard(displayId, keyboardInCar)
        if (keepDisplayAwake) enableKeepAwakeFallback(ctx, displayId)
        if (!launch(ctx, packageName, displayId)) {
            Log.w(TAG, "created display ${displayId} but could not launch $packageName on it")
        }
        return displayId
    }

    override fun startAssistant(surface: Surface, width: Int, height: Int, dpi: Int, packageName: String): Int {
        stop()
        val ctx = callerContext() ?: return -1
        val vd = createDisplay(ctx, surface, width, height, dpi) ?: return -1
        display = vd
        displayId = vd.display.displayId
        keyboard(displayId, keyboardInCar)
        if (!launchAssistant(ctx, packageName, displayId)) {
            // Unlike start(), no empty display: the caller opens the assistant on the phone instead.
            Log.w(TAG, "created display $displayId but could not open $packageName's assistant on it")
            stop()
            return -1
        }
        return displayId
    }

    override fun resize(surface: Surface, width: Int, height: Int, dpi: Int) {
        val vd = display ?: return
        runCatching { vd.resize(width, height, dpi) }
        runCatching { vd.surface = surface }
    }

    override fun touch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, pointerId: Int) {
        // A finger, as the phone's own screen reports one: Compose apps ignore a tap of unknown tool type.
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val ev = MotionEvent.obtain(downTime, eventTime, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, SOURCE_TOUCHSCREEN, 0)
        setDisplayId(ev, displayId)
        inject(ev)
        ev.recycle()
    }

    override fun key(eventTime: Long, action: Int, keyCode: Int) {
        val ev = KeyEvent(eventTime, eventTime, action, keyCode, 0, 0, -1, 0, 0, SOURCE_KEYBOARD)
        setDisplayId(ev, displayId)
        inject(ev)
    }

    override fun view(uri: String, packageName: String): Boolean {
        val id = displayId
        if (id < 0 || !launcherMayView(uri)) return false
        val ctx = callerContext() ?: return false
        return runCatching {
            // No CLEAR_TASK, unlike launch(): the app keeps its task and gets the place as a new intent.
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startOn(ctx, intent, ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
        }.onFailure { Log.w(TAG, "view on display $id failed: ${it.message}") }.getOrDefault(false)
    }

    /**
     * Before display [id] goes, hands the phone's focus to the display next in line, as a touch
     * there would: another app hosted in the car, else the phone's own screen. A display removed
     * while focused leaves Android 17's launcher on it, and Recents does nothing until a reboot; in
     * the car no touch moves the focus first. Nothing happens when another display already leads.
     */
    private fun focusPhone(id: Int) = runCatching {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        // Root tasks from top to bottom, across displays.
        val tasks = atm.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as List<*>
        val next = nextFocus(tasks.mapNotNull { t -> t?.let { it.javaClass.getField("displayId").getInt(it) } }, id)
        // The task manager's call where it has one, else the window manager's (gone in Android 17).
        runCatching { atm.javaClass.getMethod("focusTopTask", Int::class.javaPrimitiveType).invoke(atm, next) }.getOrElse { first ->
            Log.i(TAG, "focusTopTask: ${(first.cause ?: first).message}")
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window") as IBinder
            val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            wm.javaClass.getMethod("moveDisplayToTopIfAllowed", Int::class.javaPrimitiveType).invoke(wm, next)
        }
        Log.i(TAG, "focus on display $next before display $id goes")
    }.onFailure { Log.w(TAG, "focus hand-back failed: ${(it.cause ?: it).message}") }

    override fun keyboardInCar(inCar: Boolean) {
        keyboardInCar = inCar
        displayId.takeIf { display != null && it >= 0 }?.let { keyboard(it, inCar) }
    }

    override fun stop() {
        screenOffReceiver?.let { receiver ->
            receiverContext?.let { context -> runCatching { context.unregisterReceiver(receiver) } }
        }
        screenOffReceiver = null
        receiverContext = null
        displayWakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        displayWakeLock = null
        display?.let { focusPhone(displayId); runCatching { it.release() } }
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
        for (flags in intArrayOf(FULL_FLAGS, FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY)) {
            val vd = runCatching { dm.createVirtualDisplay("AARE app", w, h, dpi.coerceAtLeast(120), surface, flags) }
                .onFailure { Log.w(TAG, "createVirtualDisplay(flags=$flags) failed: ${it.message}") }
                .getOrNull()
            if (vd != null) return vd
        }
        return null
    }

    /** Launch [pkg]'s main activity onto [onDisplay]. */
    @SuppressLint("QueryPermissionsNeeded")
    private fun launch(ctx: Context, pkg: String, onDisplay: Int): Boolean = runCatching {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return false
        startOn(ctx, intent, ActivityOptions.makeBasic().setLaunchDisplayId(onDisplay).toBundle())
    }.onFailure { Log.w(TAG, "startActivity($pkg) failed: ${it.message}") }.getOrDefault(false)

    /**
     * Open [pkg]'s voice UI onto [onDisplay] (what the voice button asks of an assistant, as
     * Assistants.startOnPhone does on the phone), or its main activity if it has none.
     */
    @SuppressLint("QueryPermissionsNeeded")
    private fun launchAssistant(ctx: Context, pkg: String, onDisplay: Int): Boolean {
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(onDisplay).toBundle()
        for (action in listOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST)) {
            val intent = Intent(action).setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (ctx.packageManager.resolveActivity(intent, 0) == null) continue
            val opened = runCatching { startOn(ctx, intent, opts) }
                .onFailure { Log.w(TAG, "startActivity($action, $pkg) failed: ${it.message}") }.getOrDefault(false)
            if (opened) return true
        }
        return launch(ctx, pkg, onDisplay)
    }

    /**
     * Starts [intent] with [opts]; false if Android refuses. Straight at the activity manager with
     * [ctx]'s package as the caller: Context.startActivity sends AARE's package, which the shell uid
     * doesn't own, and is refused. Like scrcpy. The plain call where the hidden one is gone or changed.
     */
    private fun startOn(ctx: Context, intent: Intent, opts: Bundle): Boolean {
        val am = runCatching { Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null) }.getOrNull()
        val int = Int::class.javaPrimitiveType
        val thread = runCatching { Class.forName("android.app.IApplicationThread") }.getOrNull()
        val profiler = runCatching { Class.forName("android.app.ProfilerInfo") }.getOrNull()
        // Android 11 and later: startActivityAsUserWithFeature (a feature id after the package);
        // before, and where a vendor kept only the older one, startActivityAsUser.
        val result = runCatching {
            am!!.javaClass.getMethod(
                "startActivityAsUserWithFeature", thread, String::class.java, String::class.java, Intent::class.java,
                String::class.java, IBinder::class.java, String::class.java, int, int, profiler, Bundle::class.java, int,
            ).invoke(am, null, ctx.packageName, null, intent, null, null, null, 0, 0, null, opts, USER_CURRENT) as Int
        }.recoverCatching {
            am!!.javaClass.getMethod(
                "startActivityAsUser", thread, String::class.java, Intent::class.java,
                String::class.java, IBinder::class.java, String::class.java, int, int, profiler, Bundle::class.java, int,
            ).invoke(am, null, ctx.packageName, intent, null, null, null, 0, 0, null, opts, USER_CURRENT) as Int
        }.onFailure { Log.w(TAG, "startActivityAsUser unavailable: ${(it.cause ?: it).message}") }.getOrNull()
        if (result == null) ctx.startActivity(intent, opts)
        else if (result < 0) Log.w(TAG, "start of ${intent.`package` ?: intent.component} refused: $result")
        return result == null || result >= 0
    }

    /**
     * The keyboard of an app on display [id] shows there, in the car ([inCar]), or on the phone's
     * screen, where Android keeps it for a display of this kind. Like scrcpy's --display-ime-policy=local.
     */
    private fun keyboard(id: Int, inCar: Boolean) = runCatching {
        // Straight from the service manager: WindowManagerGlobal fails in this process on Android 17,
        // as no app was ever set up in it.
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window")
        val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, id,
                if (inCar) DISPLAY_IME_POLICY_LOCAL else DISPLAY_IME_POLICY_FALLBACK_DISPLAY)
        } else {
            wm.javaClass.getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(wm, id, inCar)
        }
    }.onFailure { Log.w(TAG, "keyboard on display $id: ${(it.cause ?: it).message}") }

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
     * dropped InputManager.getInstance()), InputManager before.
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

    /** Whom this process acts as: the shell's own package through Shizuku, as its uid doesn't own AARE's. */
    private fun callerContext(): Context? {
        val ctx = injectedContext ?: systemContext() ?: return null
        if (android.os.Process.myUid() != android.os.Process.SHELL_UID) return ctx
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
                "Landroid/hardware/display/DisplayManager;", "Landroid/os/PowerManager;",
                "Landroid/view/MotionEvent;", "Landroid/view/KeyEvent;",
                "Landroid/os/ServiceManager;", "Landroid/view/IWindowManager", "Landroid/app/ActivityTaskManager", "Landroid/app/IActivityTaskManager", "Landroid/app/TaskInfo;",
            )
        }
    }

    private companion object {
        const val TAG = "AareLaunchHost"
        const val SOURCE_TOUCHSCREEN = 0x00001002 // InputDevice.SOURCE_TOUCHSCREEN
        const val SOURCE_KEYBOARD = 0x00000101 // InputDevice.SOURCE_KEYBOARD
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        const val DISPLAY_IME_POLICY_LOCAL = 0 // WindowManager.DISPLAY_IME_POLICY_LOCAL, hidden
        const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1 // WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY, hidden
        const val USER_CURRENT = -2

        // VirtualDisplay flags (public + hidden bits). Hidden ones let a foreign app run and take focus.
        const val FLAG_PUBLIC = 1 shl 0
        const val FLAG_PRESENTATION = 1 shl 1
        const val FLAG_OWN_CONTENT_ONLY = 1 shl 3 // without it an empty public display mirrors the phone's screen
        const val FLAG_TRUSTED = 1 shl 10 // hidden, API 29+
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11 // hidden, API 31+
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12 // hidden, API 30+
        const val FULL_FLAGS = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
    }
}

/** The display to focus before display [gone] goes: the top one of [displays] (top to bottom, repeats allowed) other than it, else the phone's. */
internal fun nextFocus(displays: List<Int>, gone: Int): Int = displays.firstOrNull { it != gone } ?: android.view.Display.DEFAULT_DISPLAY
