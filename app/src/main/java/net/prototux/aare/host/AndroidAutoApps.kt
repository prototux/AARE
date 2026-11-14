package net.prototux.aare.host

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Telephony
import androidx.core.graphics.drawable.toBitmap
import net.prototux.aare.media.MediaDiscovery
import net.prototux.aare.mirror.KNOWN_NAVIGATION
import org.xmlpull.v1.XmlPullParser

/** An app that declares Android Auto support but that AARE doesn't show, and why. */
data class UnsupportedCarApp(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    /** The `<uses name="…">` entries of its `automotive_app_desc`. */
    val uses: Set<String>,
    val reason: String,
)

/**
 * An app on the phone listed in the car (a messaging or media app), by package. [declaresMedia]:
 * a messaging app that also declares `<uses name="media"/>`, so its media browser is a real player.
 */
data class PhoneApp(val packageName: String, val label: String, val icon: Bitmap?, val declaresMedia: Boolean = false)

/**
 * Finds every app that declares Android Auto support (`com.google.android.gms.car.application`
 * meta-data): the messaging apps among them, and the ones AARE can't show, to tell the user.
 */
object AndroidAutoApps {
    private const val META_CAR_APPLICATION = "com.google.android.gms.car.application"
    private val MESSAGING_USES = setOf("notification", "sms")

    /**
     * Messaging apps, as Android Auto finds them: apps declaring `<uses name="notification"/>`
     * (or `sms`), plus the default SMS app.
     */
    fun messagingApps(context: Context, iconSizePx: Int = 128): List<PhoneApp> {
        val pm = context.packageManager
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        return declaring(context)
            .filter { (app, uses) -> isMessaging(app.packageName, uses) || app.packageName == sms }
            .map { (app, uses) ->
                PhoneApp(app.packageName, app.loadLabel(pm).toString(), runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull(), "media" in uses)
            }
            .let { found ->
                if (sms == null || found.any { it.packageName == sms }) found
                else found + listOfNotNull(runCatching {
                    val app = pm.getApplicationInfo(sms, 0)
                    PhoneApp(sms, app.loadLabel(pm).toString(), runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull())
                }.getOrNull())
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Whether an app declaring these `<uses>` is a messaging app. Navigation apps can declare
     * `notification` too, for their own alerts, but have no conversations: listed as messaging
     * apps, they only ever showed an empty list. They are told apart by package, like on the
     * phone screen.
     */
    internal fun isMessaging(packageName: String, uses: Set<String>): Boolean =
        uses.any { it in MESSAGING_USES } && packageName !in KNOWN_NAVIGATION

    /**
     * The [messaging] apps that must not be listed as media apps: a media browser alone, like
     * Signal's voice-note player (#156), doesn't make a messaging app a media app. Android Auto
     * lists it as one only when it also declares `<uses name="media"/>`.
     */
    fun messagingOnly(messaging: List<PhoneApp>): Set<String> =
        messaging.filterNot { it.declaresMedia }.mapTo(HashSet()) { it.packageName }

    /** Media apps, as the car's launcher lists them (see MediaDiscovery): one per package. */
    fun mediaApps(context: Context, iconSizePx: Int = 128): List<PhoneApp> {
        val pm = context.packageManager
        return mediaPackages(pm).filter { it != context.packageName }.mapNotNull { pkg ->
            runCatching {
                val app = pm.getApplicationInfo(pkg, 0)
                PhoneApp(pkg, app.loadLabel(pm).toString(), runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull())
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    /** Apps declaring Android Auto support that AARE lists neither as car apps ([carApps]) nor as media or messaging apps. */
    fun unsupported(context: Context, carApps: Collection<String>, iconSizePx: Int = 96): List<UnsupportedCarApp> {
        val pm = context.packageManager
        val shown = carApps.toSet() + mediaPackages(pm)
        return declaring(context)
            .filter { (app, uses) -> app.packageName !in shown && !isMessaging(app.packageName, uses) }
            .map { (app, uses) ->
                UnsupportedCarApp(
                    packageName = app.packageName,
                    label = app.loadLabel(pm).toString(),
                    icon = runCatching { app.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull(),
                    uses = uses,
                    reason = reason(uses),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Enabled apps with `com.google.android.gms.car.application` meta-data, with their `<uses>` entries. */
    private fun declaring(context: Context): List<Pair<ApplicationInfo, Set<String>>> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.enabled && it.metaData?.containsKey(META_CAR_APPLICATION) == true && it.packageName != context.packageName }
            .map { it to uses(pm, it) }
    }

    /** Packages with a media browser service, which AARE lists as media apps (see MediaDiscovery). */
    private fun mediaPackages(pm: PackageManager): Set<String> =
        listOf(MediaDiscovery.ACTION_MEDIA3_LIBRARY, MediaDiscovery.ACTION_LEGACY_BROWSER).flatMap { action ->
            @Suppress("DEPRECATION") pm.queryIntentServices(Intent(action), 0)
                .mapNotNull { ri -> ri.serviceInfo?.takeIf { it.exported }?.packageName }
        }.toSet()

    private fun reason(uses: Set<String>): String = when {
        "media" in uses -> "Declares media support but has no media browser service AARE can use"
        "template" in uses -> "Declares a car app but has no Car App Library service AARE can use"
        uses.isEmpty() -> "Uses Google's private Android Auto interface, which only Android Auto can host"
        else -> "Uses Google's private Android Auto interface (${uses.joinToString()}), which only Android Auto can host"
    }

    /** The `<uses name="…">` entries of the app's `automotive_app_desc` XML. */
    private fun uses(pm: PackageManager, app: ApplicationInfo): Set<String> {
        val res = app.metaData?.getInt(META_CAR_APPLICATION, 0)?.takeIf { it != 0 } ?: return emptySet()
        return runCatching {
            pm.getResourcesForApplication(app).getXml(res).use { xml ->
                buildSet {
                    var type = xml.eventType
                    while (type != XmlPullParser.END_DOCUMENT) {
                        if (type == XmlPullParser.START_TAG && xml.name == "uses") xml.getAttributeValue(null, "name")?.let { add(it) }
                        type = xml.next()
                    }
                }
            }
        }.getOrDefault(emptySet())
    }
}
