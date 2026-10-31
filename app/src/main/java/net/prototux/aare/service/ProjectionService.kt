package net.prototux.aare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.prototux.aare.DiagAapLog
import net.prototux.aare.DiagLog
import net.prototux.aare.PinnedHeadUnits
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.aap.PinRefusal
import net.prototux.aare.aap.Transport
import net.prototux.aare.car.CarSession
import net.prototux.aare.cert.CertificateStore
import net.prototux.aare.media.MediaHub
import net.prototux.aare.transport.LocalSocketTransport
import net.prototux.aare.transport.TcpServer
import net.prototux.aare.transport.UsbAccessoryTransport
import net.prototux.aare.transport.wireless.WirelessConnector
import net.prototux.aare.ui.MainActivity

/**
 * Keeps the car connection alive: owns at most one [CarSession], the transports that can start
 * one (USB accessory, developer TCP server, wireless) and the audio-capture consent.
 */
class ProjectionService : Service() {

    data class State(
        val running: Boolean = false,
        val connected: Boolean = false,
        val status: String = "Not connected",
        val headUnit: String = "",
        val video: String = "",
        val transport: String = "",
        val certificate: String = "",
        val audioCapture: Boolean = false,
        val lastError: String? = null,
        /** The last head unit certificate refused as not trusted yet (new or changed), for the Trust button. */
        val pendingTrust: PinRefusal? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: Settings
    private lateinit var certs: CertificateStore
    private lateinit var media: MediaHub
    private var session: CarSession? = null
    private var tcpServer: TcpServer? = null
    private var tcpServerLan = false
    /** The running session came in over the head unit server's LAN listener, which turns off after it. */
    private var lanSession = false
    private var projection: MediaProjection? = null
    private var usbAccessory: UsbAccessory? = null
    private var usbRetries = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wirelessJob: Job? = null
    /** onDestroy ran: a session closing late must not touch this instance any more. */
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        certs = CertificateStore(this)
        media = MediaHub(this, scope)
        createChannel()
        _state.update { it.copy(running = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_USB -> IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)?.let { openUsb(it) }
            ACTION_WIRELESS -> intent.getStringExtra(EXTRA_DEVICE)?.let { connectWireless(it) }
            ACTION_AUDIO_CONSENT -> acceptAudioConsent(intent)
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
        }
        applyListeners()
        // Nothing to do: go away (USB attach and the Bluetooth receiver start us again when needed).
        main.postDelayed({ if (idle()) stopEverything() }, 3_000)
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        // Pending retries and idle checks would run on a dead instance.
        main.removeCallbacksAndMessages(null)
        wirelessJob?.cancel()
        // The session closes up to a second later; forget it first so its onEnded is ignored.
        val s = session
        session = null
        s?.stop()
        // onEnded is ignored from here on: a LAN session must not leave the network listener on.
        if (lanSession) { lanSession = false; settings.headUnitServerLan = false }
        tcpServer?.stop()
        projection?.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        _state.value = State()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ transports

    /** Starts or stops the head unit server according to the settings. */
    private fun applyListeners() {
        val lan = settings.headUnitServer && settings.headUnitServerLan
        // The LAN option changed (or switched itself off after a session): listen again with the new scope.
        if (tcpServer != null && tcpServerLan != lan) { tcpServer?.stop(); tcpServer = null }
        if (settings.headUnitServer && tcpServer == null) {
            tcpServerLan = lan
            tcpServer = runCatching {
                // Unless the user keeps network mode on, a LAN session clears the stored LAN option right away,
                // so a service killed mid-session does not come back listening on the network.
                TcpServer(lan = lan) { t ->
                    main.post {
                        if (session == null) {
                            lanSession = lan && t !is LocalSocketTransport && settings.headUnitServerLanOnce
                            if (lanSession) settings.headUnitServerLan = false
                        }
                        startSession(t, wireless = true, peerKey = "tcp", trustOnFirstUse = false, mirror = settings.headUnitServerMirror)
                    }
                }.also { it.start() }
            }.onFailure { log("can't listen for the head unit server: ${it.message}") }.getOrNull()
            if (tcpServer != null) log("head unit server listening on @${TcpServer.LOCAL_NAME} (adb)${if (lan) " and TCP ${TcpServer.PORT} (LAN)" else ""}")
        } else if (!settings.headUnitServer && tcpServer != null) {
            tcpServer?.stop(); tcpServer = null
        }
    }

    /**
     * Wireless: Bluetooth bootstrap, join the car's Wi-Fi, TCP to its AAP server. Runs in the
     * foreground service because Android only lets foreground apps request Wi-Fi networks.
     */
    private fun connectWireless(address: String) {
        if (session != null || wirelessJob?.isActive == true) return
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        val connector = WirelessConnector(this, DiagAapLog)
        val missing = connector.missingPermissions()
        if (missing.isNotEmpty()) {
            log("wireless: missing permissions ${missing.joinToString { it.substringAfterLast('.') }}")
            return
        }
        log("wireless: connecting to ${runCatching { device.name }.getOrNull() ?: address}")
        _state.update { it.copy(status = getString(R.string.notif_status_connecting_wirelessly)) }
        wirelessJob = scope.launch {
            try {
                val transport = connector.connect(device)
                startSession(transport, wireless = true, peerKey = address, trustOnFirstUse = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("wireless: ${e.message}")
                _state.update { it.copy(status = getString(R.string.notif_status_not_connected), lastError = e.message) }
            }
        }
    }

    private fun openUsb(accessory: UsbAccessory) {
        usbAccessory = accessory
        val usb = getSystemService(UsbManager::class.java)
        val transport = runCatching { UsbAccessoryTransport(usb, accessory) }
            .onFailure { log("USB: ${it.message}") }.getOrNull() ?: return
        startSession(transport, wireless = false, peerKey = usbPeerKey(accessory), trustOnFirstUse = false)
    }

    /**
     * What a trusted certificate is keyed on for USB: the accessory strings the head unit reports.
     * The head unit chooses them, so a new certificate under a known key still needs the user's Trust.
     */
    private fun usbPeerKey(accessory: UsbAccessory) =
        "usb:${accessory.manufacturer}|${accessory.model}|${runCatching { accessory.serial }.getOrNull().orEmpty()}"

    /**
     * [trustOnFirstUse] only for wireless, where the head unit was bonded over Bluetooth by the user:
     * USB and developer server head units need a Trust tap for each new certificate.
     * [mirror]: whether the car may be offered the phone screen (the developer TCP server only if opted in).
     */
    private fun startSession(transport: Transport, wireless: Boolean, peerKey: String, trustOnFirstUse: Boolean, mirror: Boolean = true) {
        if (session != null) {
            log("already connected; refusing ${transport.name}")
            transport.close()
            return
        }
        val cert = certs.pick(settings.certificateIndex)
        log("connecting ${transport.name} with certificate \"${cert.name}\"")
        acquireWakeLock()
        val s = CarSession(this, transport, cert.identity, settings, media, { projection }, wireless, peerKey, trustOnFirstUse, sessionListener, mirror)
        session = s
        _state.update { it.copy(connected = true, transport = transport.name, certificate = cert.name, lastError = null, pendingTrust = null) }
        s.start()
    }

    private val sessionListener = object : CarSession.Listener {
        override fun onStatus(session: CarSession, status: String) {
            log(status)
            _state.update { it.copy(status = status, headUnit = session.headUnitName, video = session.videoDescription) }
            updateNotification(status)
        }

        override fun onEnded(session: CarSession, reason: String, certificateRejected: Boolean, pinRefusal: PinRefusal?) {
            if (destroyed || this@ProjectionService.session !== session) return
            this@ProjectionService.session = null
            releaseWakeLock()
            if (lanSession && pinRefusal != null && tcpServerLan) {
                // Refused at the handshake, before any car screen: keep listening so it can reconnect after Trust.
                lanSession = false
                settings.headUnitServerLan = true
            } else if (lanSession) {
                // Listening on the network is for one session at a time: switch it off again.
                lanSession = false
                settings.headUnitServerLan = false
                log("head unit server: LAN access switched off after the session")
                applyListeners()
            }
            // A changed certificate gets the same Trust prompt as a new head unit, saying it changed.
            val why = when {
                pinRefusal == null -> reason
                pinRefusal.changed -> getString(R.string.notif_cert_changed_message)
                else -> getString(R.string.notif_cert_untrusted_message)
            }
            pinRefusal?.let { notifyUntrusted(it) }
            if (certificateRejected) {
                // Offer the next certificate next time: strict head units only accept Google-issued ones.
                settings.certificateIndex = settings.certificateIndex + 1
                log("head unit rejected the certificate; next connection offers \"${certs.pick(settings.certificateIndex).name}\"")
            }
            pinRefusal?.let { log("head unit ${it.peerKey} showed certificate SHA-256 ${it.fingerprint}") }
            log("disconnected: $why")
            _state.update { it.copy(connected = false, status = getString(R.string.notif_status_not_connected), video = "", lastError = why.takeIf { r -> "stopped" !in r && "ended" !in r }, pendingTrust = pinRefusal) }
            updateNotification(getString(R.string.notif_status_not_connected))
            // A USB link that dropped while still plugged in usually comes back: retry a few times.
            // Not a refused certificate: it would be refused again until the user trusts it.
            val acc = usbAccessory
            if (acc != null && pinRefusal == null && usbRetries++ < 3 && getSystemService(UsbManager::class.java).accessoryList?.contains(acc) == true) {
                main.postDelayed({ if (this@ProjectionService.session == null) openUsb(acc) }, 1_500)
            } else {
                usbAccessory = null
                usbRetries = 0
                main.postDelayed({ if (idle()) stopEverything() }, 3_000)
            }
        }
    }

    // ------------------------------------------------------------------ audio capture consent

    private fun acceptAudioConsent(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java) ?: return
        // The service must be a media-projection foreground service before taking the token.
        goForeground(mediaProjection = true)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection?.stop()
        projection = runCatching { mpm.getMediaProjection(code, data) }.onFailure { log("audio capture: ${it.message}") }.getOrNull()
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                projection = null
                _state.update { it.copy(audioCapture = false) }
                if (destroyed) return
                updateNotification(_state.value.status)
                // The consent was what kept an unconnected service alive.
                main.postDelayed({ if (idle()) stopEverything() }, 3_000)
            }
        }, main)
        _state.update { it.copy(audioCapture = projection != null) }
        log(if (projection != null) "car audio enabled" else "car audio not enabled")
        updateNotification(_state.value.status)
    }

    // ------------------------------------------------------------------ foreground, notification

    private fun goForeground(mediaProjection: Boolean = projection != null) {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (mediaProjection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        fun start(t: Int) {
            // Service types arrived in Android 10.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification(_state.value.status), t)
            else startForeground(NOTIFICATION_ID, notification(_state.value.status))
        }
        // Car apps listen through the phone's mic (voice search) with the screen off: since
        // Android 11 that needs the microphone type, which Android 14 refuses when we were
        // started from the background (USB, Bluetooth); then run without it.
        val mic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        runCatching {
            if (mic) {
                runCatching { start(types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
                    .onFailure { Log.i(TAG, "no microphone foreground type: ${it.message}"); start(types) }
            } else {
                start(types)
            }
        }
            .onFailure { Log.w(TAG, "startForeground", it); log("can't run in the foreground: ${it.message}") }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        // Screen-capture consent only lives as long as the service: say why it stays without a car.
        val shown = if (session == null && projection != null) getString(R.string.notif_keeping_consent_suffix, text) else text
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ProjectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(shown)
            .setStyle(Notification.BigTextStyle().bigText(shown))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_action_stop), stop).build())
            .build()
    }

    /**
     * Asks the user to trust a head unit certificate the phone refused; they reconnect after.
     * Open-source and Google's desktop head units ship certificates whose private keys are
     * public: once trusted, anyone holding that key can pose as that head unit (notif_trust_warning).
     */
    private fun notifyUntrusted(refusal: PinRefusal) {
        val trust = PendingIntent.getBroadcast(this, TrustReceiver.NOTIFICATION_ID,
            Intent(this, TrustReceiver::class.java)
                .putExtra(TrustReceiver.EXTRA_PEER_KEY, refusal.peerKey)
                .putExtra(TrustReceiver.EXTRA_SHA256, refusal.fingerprint),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = getString(R.string.notif_untrusted_message, refusal.fingerprint.take(16), getString(R.string.notif_trust_warning))
        getSystemService(NotificationManager::class.java).notify(TrustReceiver.NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (refusal.changed) getString(R.string.notif_cert_changed_title) else getString(R.string.notif_new_head_unit_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_action_trust), trust).build())
            .build())
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aare:projection").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    /** Nothing to keep the service for. A held screen-capture consent counts: it can't be given again silently. */
    private fun idle() = session == null && tcpServer == null && wirelessJob?.isActive != true && projection == null

    private fun stopEverything() {
        // No USB reconnect after this: the session's onEnded may come after the stop.
        usbAccessory = null
        usbRetries = 0
        session?.stop()
        tcpServer?.stop(); tcpServer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun log(line: String) = DiagLog.add(line)

    companion object {
        private const val TAG = "AareService"
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        const val ACTION_USB = "net.prototux.aare.USB"
        const val ACTION_IDLE = "net.prototux.aare.IDLE"
        const val ACTION_STOP = "net.prototux.aare.STOP"
        const val ACTION_WIRELESS = "net.prototux.aare.WIRELESS"
        const val EXTRA_DEVICE = "device"
        const val ACTION_AUDIO_CONSENT = "net.prototux.aare.AUDIO_CONSENT"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        /**
         * Trusts a refused head unit certificate, from the notification's Trust action or the app's
         * Trust button; the next connection to that head unit goes through.
         */
        fun trust(context: Context, peerKey: String, sha256: String) {
            PinnedHeadUnits(context).add(peerKey, sha256)
            context.getSystemService(NotificationManager::class.java).cancel(TrustReceiver.NOTIFICATION_ID)
            _state.update { s -> s.copy(pendingTrust = null, lastError = s.lastError.takeIf { s.pendingTrust == null }) }
        }

        fun startUsb(context: Context, accessory: UsbAccessory) {
            context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(ACTION_USB)
                .putExtra(UsbManager.EXTRA_ACCESSORY, accessory))
        }

        /** (Re)applies the listening settings: starts the service if something should listen. */
        fun startIdleIfEnabled(context: Context) {
            val s = Settings(context)
            if (s.headUnitServer || _state.value.running) {
                context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(ACTION_IDLE))
            }
        }

        /**
         * A wireless head unit appeared. Starting a foreground service from the background can be
         * refused; then a notification lets the driver start it with one tap.
         */
        fun startWireless(context: Context, address: String) {
            val intent = Intent(context, ProjectionService::class.java).setAction(ACTION_WIRELESS).putExtra(EXTRA_DEVICE, address)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "can't start from background, asking the user", e)
                DiagLog.add("wireless: Android didn't let AARE start in the background; tap the notification to connect")
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_LOW))
                val pi = PendingIntent.getForegroundService(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(2, Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.notif_car_nearby_title)).setContentText(context.getString(R.string.notif_car_nearby_text)).setContentIntent(pi).setAutoCancel(true).build())
            }
        }

        fun giveAudioConsent(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(ACTION_AUDIO_CONSENT)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProjectionService::class.java).setAction(ACTION_STOP))
        }
    }
}
