package net.prototux.aare.host

/** Template behaviour that only some apps get, by package (and versionCode where it matters). */
internal object TemplateQuirks {
    private const val WAZE = "com.waze"
    private val SAMPLES = setOf("androidx.car.app.sample.showcase", "androidx.car.app.sample.navigation")

    /**
     * Apps whose own action countdowns ("TimedActionParams" title variants, see
     * [net.prototux.aare.host.templates.rowCountdown]) run. Other apps only get the default action's.
     */
    private val TIMED_ACTION_APPS = SAMPLES + setOf(
        WAZE,
        "com.ubercab.driver",
        "com.ubercab.driver.exo",
        "com.ubercab.driver.debug",
        "com.ubercab.driver.internal",
    )

    /** First Waze version for which a new MapTemplate header title is a new screen. */
    private const val WAZE_MAP_HEADER_FROM = 1030423L

    fun timedActionParams(packageName: String): Boolean = packageName in TIMED_ACTION_APPS

    /**
     * Whether a MapTemplate from [packageName] with a new header title is a new screen rather than a
     * refresh of the current one. An app whose version can't be read doesn't get it.
     */
    fun mapHeaderStartsScreen(packageName: String, versionCode: Long?): Boolean = versionCode != null && when (packageName) {
        in SAMPLES -> true
        WAZE -> versionCode >= WAZE_MAP_HEADER_FROM
        else -> false
    }
}
