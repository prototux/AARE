package net.prototux.aare.car

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import net.prototux.aare.R
import net.prototux.aare.service.ProjectionService

/** An app calls can go through, with its name and icon for the car's contact list. */
data class VoipApp(val packageName: String, val label: String, val icon: Bitmap?)

/** A call through a messaging app: the contact's data row for it ([dataId], [mimeType]) and the app. */
data class VoipTarget(val dataId: Long, val mimeType: String, val app: VoipApp)

/**
 * Calls through messaging apps from the car's contacts. The app adds a row of its own to each
 * contact it can call ([CALL_MIME_TYPES]); opening that row starts the call in the app.
 */
@SuppressLint("MissingPermission")
object VoipCalls {
    private const val TAG = "AareVoip"
    private const val MIC_NOTIFICATION_ID = 6

    /** The contact data MIME type of a voice call through an app, to the app's package. */
    val CALL_MIME_TYPES = mapOf("vnd.android.cursor.item/vnd.com.whatsapp.voip.call" to "com.whatsapp")

    /**
     * The apps of [CALL_MIME_TYPES] calls can go through, by package: the installed ones that hand
     * their calls to Android's telephony (a self-managed phone account), so the phone treats them
     * as calls. Android 11 and later, with READ_PHONE_STATE.
     */
    fun available(context: Context, iconSizePx: Int = 96): Map<String, VoipApp> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyMap()
        val accounts = runCatching { context.getSystemService(TelecomManager::class.java).selfManagedPhoneAccounts }
            .onFailure { Log.w(TAG, "can't list the calling apps", it) }.getOrNull().orEmpty()
        val pm = context.packageManager
        return callingApps(accounts.map { it.componentName.packageName }).mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                val icon = runCatching { info.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull()
                pkg to VoipApp(pkg, info.loadLabel(pm).toString(), icon)
            }.getOrNull()
        }.toMap()
    }

    /** Of the packages with a self-managed phone account, those listed in [CALL_MIME_TYPES]. */
    fun callingApps(accountPackages: Collection<String>): Set<String> = CALL_MIME_TYPES.values.toSet().intersect(accountPackages.toSet())

    /** Whether [packageName] may record audio: without it a call through it has no microphone. */
    fun hasMicrophone(context: Context, packageName: String): Boolean = runCatching {
        val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
        context.checkPermission(Manifest.permission.RECORD_AUDIO, -1, uid) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Starts the call by opening the contact's row in the app, as the contacts app does. Returns false
     * when nothing took it (or Android kept a background start from happening).
     */
    fun place(context: Context, target: VoipTarget): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, target.dataId), target.mimeType)
            .setPackage(target.app.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = context.packageManager
        val handlers = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (handlers.isEmpty()) {
            Log.w(TAG, "${target.app.packageName} doesn't open ${target.mimeType}")
            return false
        }
        return runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "can't start the call", it) }.isSuccess
    }

    /** A phone notification asking to let [target]'s app use the microphone; a tap opens its settings. */
    fun askForMicrophone(context: Context, target: VoipTarget) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(ProjectionService.CHANNEL,
            context.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_LOW))
        val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", target.app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val text = context.getString(R.string.voip_mic_notification_text, target.app.label)
        val notification = Notification.Builder(context, ProjectionService.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.voip_mic_title, target.app.label))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(context, MIC_NOTIFICATION_ID, settings,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        // one per app: tagged with its package
        runCatching { nm.notify(target.app.packageName, MIC_NOTIFICATION_ID, notification) }.onFailure { Log.w(TAG, "notify", it) }
    }
}
