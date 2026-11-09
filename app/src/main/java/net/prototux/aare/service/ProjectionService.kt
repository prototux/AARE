package net.prototux.aare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import net.prototux.aare.aap.wireless.WirelessBootstrapException
import net.prototux.aare.car.CarSession
import net.prototux.aare.car.DrivingMode
import net.prototux.aare.cert.CertificateDownload
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
    /** The accessory whose USB permission dialog is showing; keeps the service alive until answered. */
    private var usbPermissionPending: UsbAccessory? = null
    private var usbPermissionReceiver: BroadcastReceiver? = null
    /** When the running session started, so a link that worked for a while gets a fresh retry budget. */
    private var sessionStart = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wirelessJob: Job? = null
    /** onDestroy ran: a session closing late must not touch this instance any more. */
    private var destroyed = false
    /** The download prompt is shown at most once per service lifetime, until a Google cert arrives. */
    private var certDownloadPrompted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        certs = CertificateStore(this)
        media = MediaHub(this, scope)
        // No session runs yet: undo a Do Not Disturb left on by a drive that crashed, now rather than at the next drive.
        DrivingMode.recover(this)
        createChannel()
        _state.update { it.copy(running = true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_USB -> IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)?.let { openUsb(it) }
            ACTION_WIRELESS -> intent.getStringExtra(EXTRA_DEVICE)?.let { connectWireless(it) }
            ACTION_PROBE_WIRELESS -> probeConnectedWireless()
            ACTION_AUDIO_CONSENT -> acceptAudioConsent(intent)
            ACTION_DOWNLOAD_CERT -> downloadCertificate()
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
        usbPermissionReceiver?.let { runCatching { unregisterReceiver(it) } }
        usbPermissionReceiver = null
        usbPermissionPending = null
        // The session closes up to a second later; forget it first so its onEnded is ignored.
        val s = session
        session = null
        s?.stop()
        // Sessions borrow this hub (ownsMedia = false): only the service can release its browser and listeners.
        media.release()
        net.prototux.aare.mirror.PhoneMirror.release()
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
                        // Accepted while onDestroy ran: a session here would hold a wake lock nothing releases.
                        if (destroyed) { t.close(); return@post }
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
        // Pass the phone's stable id as the WPP device serial: strict head units (#46) want it in
        // the version response before they send the WifiStartRequest.
        val connector = WirelessConnector(this, DiagAapLog,
            WirelessConnector.Options(bootstrap = net.prototux.aare.aap.wireless.WirelessBootstrap.Options(deviceSerial = settings.instanceId)))
        val missing = connector.missingPermissions()
        if (missing.isNotEmpty()) {
            log("wireless: missing permissions ${missing.joinToString { it.substringAfterLast('.') }}")
            return
        }
        log("wireless: connecting to ${runCatching { device.name }.getOrNull() ?: address}")
        _state.update { it.copy(status = getString(R.string.notif_status_connecting_wirelessly)) }
        wirelessJob = scope.launch {
            // Right after the Bluetooth link comes up some cars accept the connection but don't
            // start Android Auto yet (the Mach-E hangs up after a few silent seconds, #74); a
            // little later they do. The retries run in this job, so the service stays up meanwhile.
            var retries = 0
            while (true) {
                try {
                    val transport = connector.connect(device)
                    startSession(transport, wireless = true, peerKey = address, trustOnFirstUse = true)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("wireless: ${e.message}")
                    val carNotReady = generateSequence<Throwable>(e) { it.cause }.any { it is WirelessBootstrapException && it.whileNegotiating }
                    if (carNotReady && retries < WIRELESS_RETRIES && session == null) {
                        retries++
                        val wait = WIRELESS_RETRY_DELAY_MS * retries
                        log("wireless: the car didn't start Android Auto yet; trying again in ${wait / 1000} s ($retries/$WIRELESS_RETRIES)")
                        delay(wait)
                        if (session == null) continue
                        return@launch
                    }
                    _state.update { it.copy(status = getString(R.string.notif_status_not_connected), lastError = e.message) }
                    return@launch
                }
            }
        }
    }

    /**
     * The car was already connected over Bluetooth when AARE opened, so the ACL-connect broadcast
     * that normally starts wireless never fired (#56). Find a connected wireless head unit and
     * start the flow. No-op (with a clear log) if a session is up or nothing is connected.
     */
    private fun probeConnectedWireless() {
        if (!settings.wireless || wirelessJob?.isActive == true) { scheduleIdleStop(); return }
        if (session != null) { log("wireless: already connected; unplug USB first to connect wirelessly"); scheduleIdleStop(); return }
        val connector = WirelessConnector(this, DiagAapLog)
        if (connector.missingPermissions().isNotEmpty()) { scheduleIdleStop(); return }
        val device = runCatching { connector.findConnectedHeadUnits() }.getOrDefault(emptyList()).firstOrNull()
        if (device == null) {
            log("wireless: no Android Auto car connected over Bluetooth to probe")
            scheduleIdleStop()
            return
        }
        log("wireless: ${runCatching { device.name }.getOrNull() ?: device.address} already connected over Bluetooth, connecting")
        connectWireless(device.address)
    }

    private fun scheduleIdleStop() {
        main.postDelayed({ if (idle()) stopEverything() }, 3_000)
    }

    private fun openUsb(accessory: UsbAccessory) {
        usbAccessory = accessory
        val usb = getSystemService(UsbManager::class.java)
        // Some phones (Samsung) don't grant the permission with USB_ACCESSORY_ATTACHED: opening
        // then fails with "User has not given permission to accessory". Ask, and open once granted.
        if (!usb.hasPermission(accessory)) {
            requestUsbPermission(usb, accessory)
            return
        }
        val transport = runCatching { UsbAccessoryTransport(usb, accessory) }
            .onFailure { log("USB: ${it.message}") }.getOrNull() ?: return
        startSession(transport, wireless = false, peerKey = usbPeerKey(accessory), trustOnFirstUse = false)
    }

    /**
     * Shows the system USB permission dialog for [accessory]; [openUsb] runs again when granted.
     * The PendingIntent is explicit (our package) and mutable: the system adds
     * EXTRA_PERMISSION_GRANTED and EXTRA_ACCESSORY to it.
     */
    private fun requestUsbPermission(usb: UsbManager, accessory: UsbAccessory) {
        if (usbPermissionPending == accessory) return // dialog already showing
        usbPermissionPending = accessory
        if (usbPermissionReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION || destroyed) return
                    val acc = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                        ?: usbPermissionPending
                    usbPermissionPending = null
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                        (acc != null && getSystemService(UsbManager::class.java).hasPermission(acc))
                    if (granted && acc != null) {
                        log("USB: permission granted")
                        if (session == null) openUsb(acc)
                    } else {
                        log("USB: permission denied")
                        usbAccessory = null
                        usbRetries = 0
                        scheduleIdleStop()
                    }
                }
            }
            ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            usbPermissionReceiver = receiver
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
        log("USB: asking for permission to use the accessory")
        runCatching { usb.requestPermission(accessory, pi) }.onFailure {
            log("USB: permission request failed: ${it.message}")
            usbPermissionPending = null
            scheduleIdleStop()
            return
        }
        // Not answered (dialog dismissed without a broadcast): stop waiting after a while.
        main.postDelayed({
            if (usbPermissionPending == accessory) { usbPermissionPending = null; scheduleIdleStop() }
        }, 120_000)
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
        if (destroyed) { transport.close(); return }
        if (session != null) {
            log("already connected; refusing ${transport.name}")
            transport.close()
            return
        }
        val cert = certs.pick(settings.certificateIndex)
        log("connecting ${transport.name} with certificate \"${cert.name}\"")
        acquireWakeLock()
        sessionStart = SystemClock.elapsedRealtime()
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
                // Strict head units accept only a Google-issued certificate. If we don't have one yet,
                // offer to fetch it, so the user needn't open Advanced before connecting.
                if (!certs.hasGoogleCertificate() && !certDownloadPrompted) {
                    certDownloadPrompted = true
                    certNotice(getString(R.string.notif_cert_download_title), getString(R.string.notif_cert_download_message), retry = true)
                }
            }
            pinRefusal?.let { log("head unit ${it.peerKey} showed certificate SHA-256 ${it.fingerprint}") }
            log("disconnected: $why")
            _state.update { it.copy(connected = false, status = getString(R.string.notif_status_not_connected), video = "", lastError = why.takeIf { r -> "stopped" !in r && "ended" !in r }, pendingTrust = pinRefusal) }
            updateNotification(getString(R.string.notif_status_not_connected))
            // A USB link that dropped while still plugged in usually comes back: retry a few times.
            // Not a refused certificate: it would be refused again until the user trusts it.
            // The budget counts drops in a row, not per plug-in: a head unit that drops each link at once is not retried forever.
            if (SystemClock.elapsedRealtime() - sessionStart > USB_RETRY_RESET_MS) usbRetries = 0
            val acc = usbAccessory
            if (acc != null && pinRefusal == null && usbRetries++ < 3 && getSystemService(UsbManager::class.java).accessoryList?.contains(acc) == true) {
                // openUsb checks hasPermission() again and asks for it if it was lost.
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
                net.prototux.aare.mirror.PhoneMirror.release()
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
        // Navigation apps read location with the screen off the same way: they are bound with
        // BIND_INCLUDE_CAPABILITIES, so they only get location while locked if this service has the
        // location type (Android Auto's host has it). Without it their location requests go inactive
        // once the phone locks: the map stops following the car, or stays black when the app was
        // never opened on the phone. Needs AARE's own location permission (already used for car GPS).
        val location = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        val micType = if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val locationType = if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        // Android 14 refuses either extra type when we were started from the background: try with
        // both, then each, then neither.
        val attempts = listOf(types or micType or locationType, types or micType, types or locationType, types).distinct()
        runCatching {
            var last: Throwable? = null
            for (t in attempts) {
                val ok = runCatching { start(t) }.onFailure { last = it }.isSuccess
                if (ok) {
                    if (location && t and locationType == 0) Log.i(TAG, "no location foreground type: ${last?.message}")
                    if (mic && t and micType == 0) Log.i(TAG, "no microphone foreground type: ${last?.message}")
                    return@runCatching
                }
            }
            throw last ?: IllegalStateException("startForeground failed")
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

    /**
     * The certificate-download prompt and its progress/result. [retry] adds a Download action, for
     * the first offer and after a failed attempt; the progress and success notices are plain.
     */
    private fun certNotice(title: String, text: String, retry: Boolean) {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(!retry)
        if (retry) {
            val download = PendingIntent.getService(this, CERT_DOWNLOAD_NOTIFICATION_ID,
                Intent(this, ProjectionService::class.java).setAction(ACTION_DOWNLOAD_CERT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.notif_action_download_cert), download).build())
        }
        getSystemService(NotificationManager::class.java).notify(CERT_DOWNLOAD_NOTIFICATION_ID, builder.build())
    }

    /**
     * Fetches Android Auto's certificate (the Advanced > Download certificate action, from a
     * notification), adds it to the store and offers it on the next connection, so the user can
     * just reconnect. Runs off the main thread; the notification tracks progress and the result.
     */
    private fun downloadCertificate() {
        certNotice(getString(R.string.app_name), getString(R.string.notif_cert_download_progress), retry = false)
        Thread {
            val result = runCatching {
                certs.importDownloaded(CertificateDownload.fetch())
            }
            main.post {
                if (destroyed) return@post
                result.onSuccess { entry ->
                    // Offer the downloaded certificate first on the next connection.
                    settings.certificateIndex = certs.entries().indexOfFirst { it.name == entry.name }.coerceAtLeast(0)
                    certDownloadPrompted = false
                    certNotice(getString(R.string.notif_cert_download_ready_title),
                        getString(R.string.notif_cert_download_ready_message), retry = false)
                    log("downloaded Android Auto's certificate \"${entry.name}\"; reconnect to use it")
                }.onFailure { e ->
                    certNotice(getString(R.string.notif_cert_download_title),
                        getString(R.string.notif_cert_download_failed, e.message ?: e.toString()), retry = true)
                    log("certificate download failed: ${e.message}")
                }
            }
        }.start()
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
    private fun idle() = session == null && usbPermissionPending == null && tcpServer == null && wirelessJob?.isActive != true && projection == null

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
        // 2 = wireless re-connect, 3 = trust prompt (TrustReceiver); 4 = certificate download prompt.
        private const val CERT_DOWNLOAD_NOTIFICATION_ID = 4
        /** A USB session that lasted this long had recovered: its drop starts a new retry budget. */
        private const val USB_RETRY_RESET_MS = 60_000L
        /** Wireless attempts after a car that wasn't ready yet, waiting this much longer each time. */
        private const val WIRELESS_RETRIES = 2
        private const val WIRELESS_RETRY_DELAY_MS = 10_000L
        const val ACTION_USB = "net.prototux.aare.USB"
        const val ACTION_USB_PERMISSION = "net.prototux.aare.USB_PERMISSION"
        const val ACTION_IDLE = "net.prototux.aare.IDLE"
        const val ACTION_STOP = "net.prototux.aare.STOP"
        const val ACTION_DOWNLOAD_CERT = "net.prototux.aare.DOWNLOAD_CERT"
        const val ACTION_WIRELESS = "net.prototux.aare.WIRELESS"
        const val ACTION_PROBE_WIRELESS = "net.prototux.aare.PROBE_WIRELESS"
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

        /** Accessory strings head units use for Android Auto (res/xml/accessory_filter.xml). */
        private fun isAndroidAuto(a: UsbAccessory) =
            a.manufacturer == "Android" && (a.model == "Android Auto" || a.model == "Android Open Automotive Protocol")

        /**
         * Opening the app: an Android Auto accessory may already be attached without the USB
         * permission (Samsung). Hand it to the service, which asks for the permission and connects.
         */
        fun probeUsb(context: Context) {
            if (_state.value.connected) return
            val usb = context.getSystemService(UsbManager::class.java) ?: return
            val accessory = runCatching { usb.accessoryList }.getOrNull()?.firstOrNull(::isAndroidAuto) ?: return
            startUsb(context, accessory)
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
        /** Try to connect to a car already on Bluetooth (#56): opening AARE, or enabling wireless. */
        fun probeWireless(context: Context) {
            if (!Settings(context).wireless) return
            context.startForegroundService(Intent(context, ProjectionService::class.java).setAction(ACTION_PROBE_WIRELESS))
        }

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
