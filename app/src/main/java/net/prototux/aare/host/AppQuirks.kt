package net.prototux.aare.host

/**
 * One entry of a per-app rule: a package and the versionCodes the rule covers, both ends
 * included. These are written as "package:min:max", an empty bound meaning open-ended
 * ("com.waze:1023092:" is Waze 1023092 and later, "com.waze::1030544" up to 1030544).
 */
data class AppVersions(val packageName: String, val minVersion: Long = 0, val maxVersion: Long = Long.MAX_VALUE) {
    fun covers(versionCode: Long): Boolean = versionCode in minVersion..maxVersion

    companion object {
        /** Parses "package:min:max"; null when it isn't one (no bounds, or a bound that isn't a number). */
        fun parse(spec: String): AppVersions? {
            val parts = spec.split(':')
            if (parts.size < 3) return null
            val min = parts[parts.size - 2].ifEmpty { "0" }.toLongOrNull() ?: return null
            val max = parts.last().ifEmpty { Long.MAX_VALUE.toString() }.toLongOrNull() ?: return null
            val pkg = parts.dropLast(2).joinToString(":")
            if (pkg.isEmpty() || min > max) return null
            return AppVersions(pkg, min, max)
        }
    }
}

/**
 * A per-app rule list. An app matches only when it's listed and its installed
 * version is in one of its ranges: an app whose version can't be read never matches.
 */
class AppList(val entries: List<AppVersions>) {
    fun matches(packageName: String, versionCode: Long?): Boolean =
        versionCode != null && entries.any { it.packageName == packageName && it.covers(versionCode) }

    /** Listed at all, whatever the version. */
    operator fun contains(packageName: String): Boolean = entries.any { it.packageName == packageName }

    companion object {
        fun of(vararg specs: String) = AppList(specs.map { requireNotNull(AppVersions.parse(it)) { "bad app spec $it" } })
    }
}

/**
 * Workarounds for particular apps, as data, in one place, with their known default values
 * (package names and version bounds).
 */
object AppQuirks {
    private const val WAZE = "com.waze"

    /**
     * Navigation apps whose alerts ("Accept new route?") also show as heads-up notifications while
     * the app's own screen isn't up (default). Other apps' alerts only show in the
     * app.
     */
    val NAVIGATION_ALERTS_AS_NOTIFICATIONS = AppList.of(
        "androidx.car.app.sample.showcase::",
        "androidx.car.app.sample.navigation::",
        "$WAZE:1023092:",
        "com.ubercab.driver.exo::",
        "com.ubercab.driver::",
    )

    /**
     * Apps that take navigation requests in Waze's own intent format (see [toCarAppNavigation]);
     * only Waze itself does, the samples are listed for testing (default).
     */
    val WAZE_NAVIGATION_FORMAT = AppList.of(
        "androidx.car.app.sample.showcase::",
        "androidx.car.app.sample.navigation::",
        "$WAZE:1023098:",
    )

    /** Whether navigation requests go to [packageName] in Waze's own format: Waze from the listed version. */
    fun takesWazeNavigationFormat(packageName: String, versionCode: Long?): Boolean =
        packageName == WAZE && WAZE_NAVIGATION_FORMAT.matches(packageName, versionCode)

    /** Navigation apps whose cluster session is never started (default). */
    val CLUSTER_DISABLED = AppList.of(
        "com.example::",
        "$WAZE::1030544",
    )

    /** Car App Library apps whose icons are always tinted with the text colour (default). */
    val FORCE_ICON_TINT = AppList.of("com.nhn.android.nmap::")

    /**
     * Car App Library services never listed or started: Zoom's meeting services
     * ("package/class").
     */
    val HIDDEN_CAR_APP_SERVICES = setOf(
        "us.zoom.videomeetings/com.zipow.videobox.auto.ZmAutoMeetingService",
        "us.zoom.videomeetings/com.zipow.videobox.auto.ZmAutoMeetingServiceV2",
        "us.zoom.videomeetings4intune/com.zipow.videobox.auto.ZmAutoMeetingService",
        "us.zoom.videomeetings4intune/com.zipow.videobox.auto.ZmAutoMeetingServiceV2",
        "us.zoom.videomeetingsforgood/com.zipow.videobox.auto.ZmAutoMeetingService",
        "us.zoom.videomeetingsforgood/com.zipow.videobox.auto.ZmAutoMeetingServiceV2",
    )

    /**
     * The navigation apps with a known minimum version, for the dashboard's map
     * card. Below it the app still works, full screen only.
     */
    val DASHBOARD_NAVIGATION_MIN_VERSIONS = AppList.of(
        "com.google.android.apps.gmm.dev::",
        "com.google.android.apps.gmm.fishfood:1063030346:",
        "com.google.android.apps.gmm:1064025541:",
        "com.google.android.apps.maps:1066723824:",
        "com.google.android.apps.gmm.qp:1066034479:",
        "com.locnall.KimGiSa:40402:",
        "$WAZE:1023000:",
    )

    /**
     * Whether a navigation app may run in the dashboard's map card. Only an app whose installed
     * version can be read and is below its listed minimum loses the card; an unreadable version,
     * or an app with no minimum, keeps it as before. Waze's Car App Library app always keeps it:
     * its listed minimum is for its older projected app, which AARE doesn't host.
     */
    fun dashboardNavigationSupported(packageName: String, versionCode: Long?): Boolean =
        versionCode == null || packageName == WAZE || packageName !in DASHBOARD_NAVIGATION_MIN_VERSIONS ||
            DASHBOARD_NAVIGATION_MIN_VERSIONS.matches(packageName, versionCode)

    /**
     * Packages whose notifications are never read as conversations (default):
     * the system, the Play Store, downloads and Google Maps post MessagingStyle-like notifications
     * that aren't messages.
     */
    val NOT_CONVERSATIONS = setOf("android", "com.android.vending", "com.android.providers.downloads", "com.google.android.apps.maps")

    /**
     * Messaging apps whose voice messages sometimes come without their audio (a null or empty data
     * URI): such a conversation update is dropped instead of showing an empty message.
     */
    val DROP_EMPTY_AUDIO_MESSAGES = setOf("com.google.android.apps.messaging")
}
