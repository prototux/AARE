package net.prototux.aare.cert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.service.ProjectionService
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * Keeps the Google-issued certificate usable, for head units that accept no other.
 *
 * A downloaded certificate comes from an Android Auto build and expires a few months later. With
 * automatic updates on (Advanced > Phone certificates; off by default, as it means a request to a
 * third-party repository), the repository's version is checked at most once a day and never while
 * a car is connected: a newer certificate replaces the downloaded one and is offered first.
 *
 * Either way, when the Google-issued certificate that lasts longest expires within [WARN_DAYS]
 * days, the home screen warns and one notification says so, with a Download action; another comes
 * once it has expired. Phones without a Google-issued certificate get neither: the head units they
 * connect to don't need one.
 */
object CertificateUpdates {
    private const val TAG = "AareCertUpdate"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    /** How long before the expiry the warning starts. */
    internal const val WARN_DAYS = 14
    // ProjectionService uses 1-4 (see its companion).
    private const val NOTIFICATION_ID = 5

    /** The Google-issued certificate [name] expires at [notAfter], or has ([expired]). */
    data class Expiring(val name: String, val notAfter: Date, val expired: Boolean) {
        /** Notified and dismissed once each: before the expiry, then again once it has passed. */
        val key: String get() = "$name|$expired"
    }

    private val _expiring = MutableStateFlow<Expiring?>(null)
    /** The home screen's warning, or null. */
    val expiring: StateFlow<Expiring?> = _expiring.asStateFlow()

    @Volatile private var checking = false

    /**
     * Updates the expiry warning and, if automatic updates are on and one is due, checks the
     * repository on a background thread. Returns at once; safe to call on every app open and after
     * every session.
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val settings = Settings(app)
        warn(app, settings)
        if (!settings.certAutoUpdate || ProjectionService.state.value.connected) return
        if (checking || System.currentTimeMillis() - settings.certUpdateLastCheckMs < DAY_MS) return
        checking = true
        thread(isDaemon = true, name = "aare-cert-update") {
            try {
                update(app, settings)
            } finally {
                checking = false
            }
            warn(app, settings)
        }
    }

    /** Hides the home screen's warning for the current certificate and state. */
    fun dismiss(context: Context) {
        _expiring.value?.let { Settings(context.applicationContext).certExpiryDismissed = it.key }
        _expiring.value = null
    }

    private fun update(context: Context, settings: Settings) {
        val certs = CertificateStore(context)
        runCatching {
            val latest = CertificateDownload.latestVersion()
            settings.certUpdateLastCheckMs = System.currentTimeMillis()
            if (latest == certs.downloadedVersion()) return
            val download = CertificateDownload.fetch()
            val longest = certs.entries().filter { it.isGoogleIssued }.maxOfOrNull { it.notAfter }
            if (!replaces(CertificateDownload.notAfter(download.certPem), longest)) {
                DiagLog.add("certificate update: version ${download.version} lasts no longer than the one on the phone; kept")
                return
            }
            val entry = certs.importDownloaded(download)
            settings.certificateIndex = certs.indexOf(entry.name)
            DiagLog.add("certificate update: imported \"${entry.name}\", valid until ${entry.notAfter}")
            notify(context, context.getString(R.string.notif_cert_updated_title),
                context.getString(R.string.notif_cert_updated_message, DateFormat.getDateInstance().format(entry.notAfter)), download = false)
        }.onFailure {
            // Silent like the app update check: the next app open or session end tries again tomorrow.
            Log.i(TAG, "certificate update check failed: ${it.message}")
        }
    }

    /** Shows or clears the warning, and notifies a new one once. */
    private fun warn(context: Context, settings: Settings) {
        val google = CertificateStore(context).entries().filter { it.isGoogleIssued }.associate { it.name to it.notAfter }
        val warning = expiryWarning(google, Date())
        _expiring.value = warning?.takeIf { it.key != settings.certExpiryDismissed }
        if (warning == null || warning.key == settings.certExpiryNotified) return
        settings.certExpiryNotified = warning.key
        val date = DateFormat.getDateInstance().format(warning.notAfter)
        val text = if (warning.expired) context.getString(R.string.notif_cert_expired_text, warning.name, date)
            else context.getString(R.string.notif_cert_expiring_text, warning.name, date)
        DiagLog.add("certificate \"${warning.name}\" ${if (warning.expired) "expired" else "expires"} on $date")
        notify(context, context.getString(if (warning.expired) R.string.notif_cert_expired_title else R.string.notif_cert_expiring_title),
            text, download = true)
    }

    private fun notify(context: Context, title: String, text: String, download: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(ProjectionService.CHANNEL,
            context.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_LOW))
        val builder = Notification.Builder(context, ProjectionService.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (download) {
            val intent = PendingIntent.getService(context, NOTIFICATION_ID,
                Intent(context, ProjectionService::class.java).setAction(ProjectionService.ACTION_DOWNLOAD_CERT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.notif_action_download_cert), intent).build())
        }
        runCatching { nm.notify(NOTIFICATION_ID, builder.build()) }.onFailure { Log.w(TAG, "notify", it) }
    }

    /**
     * The warning for the Google-issued certificates [google] (name to expiry) at [now]: about the
     * one that lasts longest, since the others are no longer needed once it is there. Null when
     * there is none, or when it is valid for more than [WARN_DAYS] days.
     */
    internal fun expiryWarning(google: Map<String, Date>, now: Date): Expiring? {
        val (name, notAfter) = google.maxByOrNull { it.value } ?: return null
        val left = notAfter.time - now.time
        if (left > WARN_DAYS * DAY_MS) return null
        return Expiring(name, notAfter, expired = left <= 0)
    }

    /** Whether a download valid until [downloaded] is worth replacing the phone's longest-lasting Google certificate ([current]). */
    internal fun replaces(downloaded: Date, current: Date?): Boolean = current == null || downloaded.after(current)
}
