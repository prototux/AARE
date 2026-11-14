package net.prototux.aare.host

/** Template behaviour that only some apps get, by package. */
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

    fun timedActionParams(packageName: String): Boolean = packageName in TIMED_ACTION_APPS
}
