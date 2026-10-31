package net.prototux.aare.host

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import androidx.car.app.AppInfo
import androidx.car.app.FailureResponse
import androidx.car.app.HandshakeInfo
import androidx.car.app.IAppHost
import androidx.car.app.IAppManager
import androidx.car.app.ICarApp
import androidx.car.app.ICarHost
import androidx.car.app.IOnDoneCallback
import androidx.car.app.ISurfaceCallback
import androidx.car.app.SessionInfo
import androidx.car.app.SessionInfoIntentEncoder
import androidx.car.app.SurfaceContainer
import androidx.car.app.constraints.IConstraintHost
import androidx.car.app.hardware.ICarHardwareHost
import androidx.car.app.hardware.ICarHardwareResult
import androidx.car.app.media.CarAudioCallback
import androidx.car.app.media.OpenMicrophoneRequest
import androidx.car.app.media.OpenMicrophoneResponse
import androidx.car.app.model.Alert
import androidx.car.app.model.Template
import androidx.car.app.model.TemplateInfo
import androidx.car.app.model.TemplateWrapper
import androidx.car.app.navigation.INavigationHost
import androidx.car.app.navigation.INavigationManager
import androidx.car.app.navigation.model.Trip
import androidx.car.app.serialization.Bundleable
import androidx.car.app.versioning.CarAppApiLevels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import net.prototux.aare.car.CarData

/**
 * One hosted Car App Library app: binds its CarAppService and plays the part Android Auto plays.
 *
 * Sequence (what the library expects from a host): bind → getAppInfo → negotiate the API level →
 * onHandshakeCompleted (the app checks it trusts us here) → onAppCreate(our ICarHost) →
 * getManager("app") → getTemplate → onAppStart/onAppResume while visible.
 *
 * All state is Compose state, mutated on the main thread; binder calls from the app arrive on
 * binder threads and are posted to the main thread.
 */
class CarAppConnection(
    private val context: Context,
    val info: CarAppInfo,
    private val env: HostEnvironment,
    /** The instrument cluster's session of a navigation app (FEATURE_CLUSTER), not its main one. */
    val cluster: Boolean = false,
) {
    enum class Status { IDLE, CONNECTING, READY, FAILED }

    var status by mutableStateOf(Status.IDLE)
        private set
    /** Human-readable reason when [status] is FAILED. */
    var error by mutableStateOf<String?>(null)
        private set
    var template by mutableStateOf<Template?>(null)
        private set
    var templateId by mutableStateOf<String?>(null)
        private set
    var alert by mutableStateOf<Alert?>(null)
        private set
    var navigating by mutableStateOf(false)
        private set
    var trip by mutableStateOf<Trip?>(null)
        private set
    /** The app wants a map surface (it called setSurfaceCallback). */
    var wantsSurface by mutableStateOf(false)
        private set
    var apiLevel = 0
        private set
    var libraryVersion: String = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private var carApp: ICarApp? = null
    private var appManager: IAppManager? = null
    private var navManager: INavigationManager? = null
    @Volatile private var surfaceCallback: ISurfaceCallback? = null
    private var bound = false
    /** Set once [cleanup] has run, so a subscribeCarHardwareResult racing in on a binder thread doesn't re-add a listener nothing will remove. */
    @Volatile private var cleanedUp = false
    private var visible = false
    private var started = false
    private var pendingIntent: Intent? = null
    private var retries = 0
    /** Screen stack of the last template applied, top first (as ScreenManager lists it). */
    private var screenStack: List<TemplateInfo> = emptyList()
    /** getTemplate calls started / the newest one applied: replies can come back out of order. */
    private var fetchSeq = 0
    private var appliedSeq = 0

    private var surfaceOwner: Any? = null
    private var surface: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null

    private val hardwareSubscriptions = HashMap<Int, ICarHardwareResult>()

    // ------------------------------------------------------------------ public API (main thread)

    /** Binds the app. [intent] is delivered to its Session.onCreateScreen (e.g. a navigation request). */
    fun connect(intent: Intent? = null) {
        if (status == Status.CONNECTING || status == Status.READY) {
            intent?.let { newIntent(it) }
            return
        }
        pendingIntent = intent
        status = Status.CONNECTING
        error = null
        cleanedUp = false
        val bindIntent = Intent(CarAppInfo.SERVICE_ACTION).setComponent(info.service)
        // A second session of the same app, for the cluster display: the library tells them apart by SessionInfo.
        if (cluster) SessionInfoIntentEncoder.encode(SessionInfo(SessionInfo.DISPLAY_TYPE_CLUSTER, "aare-cluster"), bindIntent)
        val ok = runCatching {
            context.bindService(bindIntent, serviceConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES or Context.BIND_IMPORTANT)
        }.getOrElse { log("bind failed", it); false }
        bound = ok
        if (!ok) fail("Could not start ${info.label}")
    }

    /** On screen (map on the dashboard counts) or not: drives the app's Screen lifecycle. */
    fun setVisible(v: Boolean) {
        if (visible == v) return
        visible = v
        if (status == Status.READY) applyVisibility()
        else if (v && (status == Status.IDLE || status == Status.FAILED)) connect()
    }

    fun newIntent(intent: Intent) {
        val app = carApp
        if (status == Status.READY && app != null) { call("onNewIntent") { app.onNewIntent(intent, it) }; return }
        // connect() without the intent would reset the pending one
        if (status == Status.CONNECTING || status == Status.READY) pendingIntent = intent else connect(intent)
    }

    fun back() {
        val m = appManager ?: return
        call("onBackPressed") { m.onBackPressed(it) }
    }

    /** The driver stopped navigation from the car UI. */
    fun stopNavigation() {
        val n = navManager ?: return
        call("onStopNavigation") { n.onStopNavigation(it) }
    }

    fun dismissAlert() {
        val a = alert ?: return
        alert = null
        runCatching { a.callbackDelegate?.sendDismiss(templateDone) }
    }

    fun release() {
        visible = false
        if (status == Status.READY) applyVisibility()
        surfaceOwner?.let { surfaceDestroyed(it) }
        unbind()
        status = Status.IDLE
    }

    /** Pushes a new screen configuration (night mode or size changed). */
    fun configurationChanged() {
        val app = carApp ?: return
        call("onConfigurationChanged") { app.onConfigurationChanged(configuration(), it) }
    }

    // ------------------------------------------------------------------ surface (main thread)

    fun surfaceAvailable(owner: Any, s: Surface, width: Int, height: Int, dpi: Int) {
        surfaceOwner?.takeIf { it !== owner }?.let { surfaceDestroyed(it) }
        surfaceOwner = owner
        surface = SurfaceContainer(s, width, height, dpi)
        sendSurface()
    }

    fun surfaceDestroyed(owner: Any) {
        if (surfaceOwner !== owner) return
        val container = surface
        surfaceOwner = null
        surface = null
        val cb = surfaceCallback ?: return
        if (container != null) call("onSurfaceDestroyed") { cb.onSurfaceDestroyed(Bundleable.create(container), it) }
    }

    fun setVisibleArea(r: Rect) {
        if (r == visibleArea) return
        visibleArea = Rect(r)
        val cb = surfaceCallback ?: return
        if (surface != null) call("onVisibleAreaChanged") { cb.onVisibleAreaChanged(r, it) }
    }

    fun setStableArea(r: Rect) {
        if (r == stableArea) return
        stableArea = Rect(r)
        val cb = surfaceCallback ?: return
        if (surface != null) call("onStableAreaChanged") { cb.onStableAreaChanged(r, it) }
    }

    fun scroll(dx: Float, dy: Float) = surfaceCallback?.let { runCatching { it.onScroll(dx, dy) } }
    fun fling(vx: Float, vy: Float) = surfaceCallback?.let { runCatching { it.onFling(vx, vy) } }
    fun scale(fx: Float, fy: Float, factor: Float) = surfaceCallback?.let { runCatching { it.onScale(fx, fy, factor) } }
    fun click(x: Float, y: Float) = surfaceCallback?.let { if (apiLevel >= CarAppApiLevels.LEVEL_5) runCatching { it.onClick(x, y) } }

    private fun sendSurface() {
        val cb = surfaceCallback ?: return
        val container = surface ?: return
        call("onSurfaceAvailable") { cb.onSurfaceAvailable(Bundleable.create(container), it) }
        visibleArea?.let { r -> call("onVisibleAreaChanged") { cb.onVisibleAreaChanged(r, it) } }
        stableArea?.let { r -> call("onStableAreaChanged") { cb.onStableAreaChanged(r, it) } }
    }

    // ------------------------------------------------------------------ handshake

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            runCatching { binder.linkToDeath({ main.post { onAppDied() } }, 0) }
            val app = ICarApp.Stub.asInterface(binder)
            carApp = app
            handshake(app)
        }

        override fun onServiceDisconnected(name: ComponentName) = onAppDied()
        override fun onBindingDied(name: ComponentName) = onAppDied()
        override fun onNullBinding(name: ComponentName) = fail("${info.label} refused the connection")
    }

    private fun handshake(app: ICarApp) {
        call("getAppInfo", onSuccess = { result ->
            val appInfo = result as? AppInfo
            val hostMax = CarAppApiLevels.getLatest()
            val appLatest = appInfo?.latestCarAppApiLevel ?: CarAppApiLevels.LEVEL_1
            val appMin = appInfo?.minCarAppApiLevel ?: CarAppApiLevels.LEVEL_1
            libraryVersion = appInfo?.libraryDisplayVersion ?: ""
            if (appMin > hostMax) {
                fail("${info.label} needs car API level $appMin; this host supports $hostMax")
                return@call
            }
            apiLevel = minOf(appLatest, hostMax)
            Log.i(TAG, "${info.packageName}: library $libraryVersion, API $appMin..$appLatest, negotiated $apiLevel")
            val handshake = Bundleable.create(HandshakeInfo(context.packageName, apiLevel))
            call("onHandshakeCompleted", onSuccess = { create(app) }) { app.onHandshakeCompleted(handshake, it) }
        }) { app.getAppInfo(it) }
    }

    private fun create(app: ICarApp) {
        val intent = pendingIntent ?: Intent(CarAppInfo.SERVICE_ACTION).setComponent(info.service)
        pendingIntent = null
        call("onAppCreate", onSuccess = {
            call("getManager(app)", onSuccess = { m ->
                appManager = (m as? IInterface)?.let { IAppManager.Stub.asInterface(it.asBinder()) }
                if (info.isNavigation) {
                    call("getManager(navigation)", onSuccess = { n ->
                        navManager = (n as? IInterface)?.let { INavigationManager.Stub.asInterface(it.asBinder()) }
                    }) { app.getManager("navigation", it) }
                }
                status = Status.READY
                retries = 0
                screenStack = emptyList() // a new session: its first template is never a refresh
                fetchTemplate()
                applyVisibility()
                // An intent that came in after onAppCreate took the pending one (still CONNECTING then).
                pendingIntent?.let { pendingIntent = null; newIntent(it) }
            }) { app.getManager("app", it) }
        }) { app.onAppCreate(carHost, intent, configuration(), it) }
    }

    private fun fetchTemplate() {
        val m = appManager ?: return
        val seq = ++fetchSeq
        call("getTemplate", onSuccess = { w ->
            val wrapper = w as? TemplateWrapper ?: return@call
            if (seq < appliedSeq) return@call
            appliedSeq = seq
            val stack = runCatching { wrapper.templateInfosForScreenStack }.getOrNull().orEmpty()
            val refresh = templateId != null && isRefresh(screenStack, stack)
            screenStack = stack
            // Record only the accepted template; older, out-of-order callbacks were rejected above.
            (wrapper.template as? androidx.car.app.navigation.model.NavigationTemplate)
                ?.let { AareTbtProbe.routing(context, info.packageName, it) }
            template = wrapper.template
            // A refresh of the same screen (search results while typing, a navigation update, a
            // toggled row) keeps the on-screen state: what's typed, pan mode, the scroll position.
            if (!refresh) templateId = wrapper.id
        }) { m.getTemplate(it) }
    }

    /**
     * The library never sets TemplateWrapper.isRefresh and wraps every template with a new id, so
     * decide like Android Auto from the screen stack: same depth, same screens below the top
     * (class and id: their ids are stable) and the same template class on top. The list is top first.
     */
    private fun isRefresh(old: List<TemplateInfo>, new: List<TemplateInfo>): Boolean =
        new.isNotEmpty() && new.size == old.size && new.drop(1) == old.drop(1) &&
            new[0].templateClass == old[0].templateClass

    private fun applyVisibility() {
        val app = carApp ?: return
        if (visible && !started) {
            started = true
            call("onAppStart") { app.onAppStart(it) }
            call("onAppResume") { app.onAppResume(it) }
            surface?.let { sendSurface() }
        } else if (!visible && started) {
            started = false
            call("onAppPause") { app.onAppPause(it) }
            call("onAppStop") { app.onAppStop(it) }
        }
    }

    private fun configuration(): Configuration = Configuration(context.resources.configuration).apply {
        screenWidthDp = env.screenWidthDp
        screenHeightDp = env.screenHeightDp
        smallestScreenWidthDp = minOf(env.screenWidthDp, env.screenHeightDp)
        densityDpi = env.densityDpi
        orientation = if (env.screenWidthDp >= env.screenHeightDp) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (env.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }

    private fun onAppDied() {
        if (status == Status.IDLE) return
        Log.w(TAG, "${info.packageName} died or unbound")
        // It won't send navigationEnded any more: don't leave its trip on the dashboard and cluster.
        if (navigating) { navigating = false; trip = null; env.navigationEnded(this) }
        cleanup()
        status = Status.FAILED
        error = "${info.label} stopped"
        // Reconnect while it's on screen, a few times: apps get killed and restarted by the OS.
        if (visible && retries++ < 3) main.postDelayed({ if (visible && status == Status.FAILED) connect() }, 1_000L * retries)
    }

    private fun fail(message: String) {
        Log.w(TAG, "${info.packageName}: $message")
        cleanup()
        unbind()
        status = Status.FAILED
        error = message
    }

    private fun cleanup() {
        carApp = null
        appManager = null
        navManager = null
        surfaceCallback = null
        wantsSurface = false
        started = false
        navigating = false
        alert = null
        synchronized(hardwareSubscriptions) { cleanedUp = true; hardwareSubscriptions.clear() }
        CarData.removeListener(carDataListener)
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        runCatching { context.unbindService(serviceConnection) }
    }

    // ------------------------------------------------------------------ calls into the app

    /**
     * Calls a oneway app method with a done-callback. Results are unwrapped from their Bundleable
     * and delivered on the main thread; failures are logged and, during the handshake, end the
     * connection with an explanation.
     */
    private fun call(name: String, onSuccess: ((Any?) -> Unit)? = null, block: (IOnDoneCallback) -> Unit) {
        val cb = object : IOnDoneCallback.Stub() {
        override fun getInterfaceVersion(): Int = IOnDoneCallback.VERSION

            override fun onSuccess(response: Bundleable?) {
                val value = runCatching { response?.get() }.getOrElse { e -> main.post { onFailure(name, e.toString()) }; return }
                main.post { onSuccess?.invoke(value) }
            }

            override fun onFailure(response: Bundleable) {
                val msg = runCatching { (response.get() as FailureResponse).stackTrace }.getOrDefault("unknown error")
                main.post { onFailure(name, msg) }
            }
        }
        try {
            block(cb)
        } catch (e: Exception) {
            onFailure(name, e.toString())
        }
    }

    private fun onFailure(call: String, message: String) {
        Log.w(TAG, "${info.packageName}.$call failed: ${message.lineSequence().firstOrNull()}")
        if (status == Status.CONNECTING) {
            fail(when {
                "Unknown host" in message || "not allowed" in message || "SecurityException" in message ->
                    "${info.label} only accepts Google's Android Auto. It needs to allow AARE (see the app list on the phone)."
                else -> "${info.label} couldn't start: ${message.lineSequence().first().take(120)}"
            })
        }
    }

    // ------------------------------------------------------------------ host binders (app → us)

    private val carHost = object : ICarHost.Stub() {
        override fun getInterfaceVersion(): Int = ICarHost.VERSION

        override fun startCarApp(intent: Intent) {
            main.post { env.startCarApp(this@CarAppConnection, intent) }
        }

        override fun getHost(type: String): IBinder = when (type) {
            "app" -> appHost.asBinder()
            "navigation" -> navigationHost.asBinder()
            "constraints" -> constraintHost.asBinder()
            "hardware" -> hardwareHost.asBinder()
            else -> throw IllegalArgumentException("Unknown host type: $type")
        }

        override fun finish() {
            main.post { env.finish(this@CarAppConnection) }
        }
    }

    private val appHost = object : IAppHost.Stub() {
        override fun getInterfaceVersion(): Int = IAppHost.VERSION

        override fun invalidate() {
            main.post { fetchTemplate() }
        }

        override fun showToast(text: CharSequence, duration: Int) {
            main.post { env.toast(this@CarAppConnection, text, duration == 1) }
        }

        override fun setSurfaceCallback(callback: ISurfaceCallback?) {
            main.post {
                surfaceCallback = callback
                wantsSurface = callback != null
                if (callback != null) sendSurface()
            }
        }

        override fun sendLocation(location: Location) = Unit

        override fun showAlert(alertBundle: Bundleable) {
            val a = runCatching { alertBundle.get() as Alert }.getOrNull() ?: return
            main.post { alert = a }
        }

        override fun dismissAlert(alertId: Int) {
            main.post { if (alert?.id == alertId) alert = null }
        }

        override fun openMicrophone(request: Bundleable): Bundleable? = openMic(request)
    }

    private val navigationHost = object : INavigationHost.Stub() {
        override fun getInterfaceVersion(): Int = INavigationHost.VERSION

        override fun navigationStarted() {
            main.post {
                AareTbtProbe.lifecycle(context, info.packageName, "started")
                navigating = true
                env.navigationStarted(this@CarAppConnection)
            }
        }

        override fun navigationEnded() {
            main.post {
                AareTbtProbe.lifecycle(context, info.packageName, "ended")
                navigating = false
                trip = null
                env.navigationEnded(this@CarAppConnection)
            }
        }

        override fun updateTrip(tripBundle: Bundleable) {
            val t = runCatching { tripBundle.get() as Trip }.getOrNull() ?: return
            main.post {
                AareTbtProbe.trip(context, info.packageName, t)
                trip = t
                env.tripUpdated(this@CarAppConnection, t)
            }
        }
    }

    private val constraintHost = object : IConstraintHost.Stub() {
        override fun getInterfaceVersion(): Int = IConstraintHost.VERSION

        override fun getContentLimit(contentType: Int): Int = env.contentLimit(contentType)
        override fun isAppDrivenRefreshEnabled(): Boolean = false
    }

    /**
     * Car hardware for apps: CarInfo (model, energy, speed, mileage, toll card) and CarSensors
     * (compass, GPS, accelerometer, gyroscope) from what the head unit reports (see
     * CarHardwareResults). CoMaps and Organic Maps subscribe to the compass and the car's GPS and
     * crash if there is no hardware host at all.
     */
    private val hardwareHost = object : ICarHardwareHost.Stub() {
        override fun getInterfaceVersion(): Int = ICarHardwareHost.VERSION

        override fun getCarHardwareResult(resultType: Int, params: Bundleable?, callback: ICarHardwareResult) {
            deliverHardware(resultType, callback)
        }

        override fun subscribeCarHardwareResult(resultType: Int, params: Bundleable?, callback: ICarHardwareResult) {
            // Refuse an unpermitted location subscription up front instead of failing on every GPS fix.
            if (resultType == CarHardwareResults.CAR_LOCATION && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                deliverHardware(resultType, callback)
                return
            }
            // Binder thread: cleanup() may already have torn this connection down.
            val live = synchronized(hardwareSubscriptions) { if (!cleanedUp) hardwareSubscriptions[resultType] = callback; !cleanedUp }
            if (!live) return
            CarData.addListener(carDataListener)
            deliverHardware(resultType, callback)
        }

        override fun unsubscribeCarHardwareResult(resultType: Int, params: Bundleable?) {
            synchronized(hardwareSubscriptions) { hardwareSubscriptions.remove(resultType) }
        }
    }

    /** Result types waiting to be pushed: bursts of sensor batches (accelerometer...) collapse into one push. */
    private val pendingHardware = HashSet<Int>()

    /**
     * New car data (reader thread). GPS and compass keep their own path ([carSensorsChanged], after
     * the runtime has stored them); everything else is pushed from here.
     */
    private val carDataListener = CarData.Listener { _, changed ->
        val types = CarHardwareResults.typesFor(changed) - CarHardwareResults.COMPASS - CarHardwareResults.CAR_LOCATION
        if (types.isEmpty()) return@Listener
        val wanted = synchronized(hardwareSubscriptions) { types.filter { it in hardwareSubscriptions } }
        if (wanted.isEmpty()) return@Listener
        val first = synchronized(pendingHardware) { pendingHardware.isEmpty().also { pendingHardware += wanted } }
        if (first) main.post {
            val due = synchronized(pendingHardware) { pendingHardware.toList().also { pendingHardware.clear() } }
            val subs = synchronized(hardwareSubscriptions) { hardwareSubscriptions.toMap() }
            for (type in due) subs[type]?.let { deliverHardware(type, it) }
        }
    }

    /** Pushes fresh car GPS/compass values to subscribed apps (called by the runtime on sensor updates). */
    fun carSensorsChanged() {
        val subs = synchronized(hardwareSubscriptions) { hardwareSubscriptions.toMap() }
        for ((type, cb) in subs) if (type == CarHardwareResults.COMPASS || type == CarHardwareResults.CAR_LOCATION) deliverHardware(type, cb)
    }

    private fun deliverHardware(type: Int, cb: ICarHardwareResult) {
        // The car's position is location data: like Android Auto, only hand it to an app that holds the permission itself.
        val result = if (type == CarHardwareResults.CAR_LOCATION && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            log("car location refused: ACCESS_FINE_LOCATION not granted")
            null
        } else runCatching { CarHardwareResults.build(type, CarData.state.value, env.carLocation, env.carHeading) }
            .onFailure { Log.w(TAG, "hardware result $type: $it") }.getOrNull()
        runCatching {
            if (result != null) cb.onCarHardwareResult(type, true, Bundleable.create(result), LoggingDone.asBinder())
            else cb.onCarHardwareResult(type, false, null, LoggingDone.asBinder())
        }.onFailure { Log.w(TAG, "hardware result $type to ${info.packageName} failed: $it") }
    }

    // ------------------------------------------------------------------ microphone

    /**
     * IAppHost.openMicrophone is synchronous: we return a pipe whose read end the app reads
     * 16 kHz mono PCM from, and keep the write end fed with the car's microphone.
     */
    private fun openMic(requestBundle: Bundleable): Bundleable? {
        // The car's microphone is the cabin audio: only an app that holds RECORD_AUDIO itself may open it (null = refused, as when no mic is available).
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            log("microphone refused: RECORD_AUDIO not granted")
            return null
        }
        val request = runCatching { requestBundle.get() as OpenMicrophoneRequest }.getOrNull()
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        var handle: MicHandle? = null
        val stop = {
            handle?.close()
            runCatching { out.close() }
            Unit
        }
        handle = env.openMicrophone(this, sink = { data, off, len ->
            try { out.write(data, off, len) } catch (e: IOException) { main.post { stop() } }
        }, onStopped = {
            runCatching { request?.carAudioCallbackDelegate?.onStopRecording() }
            stop()
        })
        if (handle == null) {
            runCatching { pipe[0].close() }; runCatching { out.close() }
            return null
        }
        val response = OpenMicrophoneResponse.Builder(object : CarAudioCallback {
            override fun onStopRecording() { main.post { stop() } }
        }).setCarMicrophoneDescriptor(pipe[0]).build()
        return Bundleable.create(response)
    }

    private fun hasPermission(permission: String): Boolean =
        context.packageManager.checkPermission(permission, info.packageName) == PackageManager.PERMISSION_GRANTED

    private fun log(msg: String, t: Throwable? = null) = Log.w(TAG, "${info.packageName}: $msg", t)

    private object LoggingDone : IOnDoneCallback.Stub() {
        override fun getInterfaceVersion(): Int = IOnDoneCallback.VERSION

        override fun onSuccess(response: Bundleable?) = Unit
        override fun onFailure(response: Bundleable) {
            Log.w(TAG, "callback failed: ${runCatching { (response.get() as FailureResponse).stackTrace.lineSequence().first() }.getOrNull()}")
        }
    }

    companion object {
        private const val TAG = "AareCarApp"

        /** The OnDoneCallback handed to template delegates: logs failures. */
        val templateDone = object : androidx.car.app.OnDoneCallback {
            override fun onSuccess(response: Bundleable?) = Unit
            override fun onFailure(response: Bundleable) {
                Log.w(TAG, "template callback failed: ${runCatching { (response.get() as FailureResponse).stackTrace.lineSequence().first() }.getOrNull()}")
            }
        }
    }
}
