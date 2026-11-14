package net.prototux.aare.launch

/** What the user allows a launched phone app to do while driving, per package. */
enum class StandaloneDrivingRule(val key: String) {
    /** Paused while driving: its screen is hidden and its playback paused. */
    PARKED_ONLY("parked"),
    /** Usable while driving: for navigation apps, or for a passenger. */
    ALLOWED("driving"),
    /** Keeps running for its sound while driving, its screen hidden. */
    AUDIO_ONLY("audio"),
    ;

    companion object {
        fun byKey(key: String): StandaloneDrivingRule? = entries.firstOrNull { it.key == key }

        /**
         * The rule for an app with none set: navigation apps may be used while driving, as the phone
         * screen allows them; everything else is parked only.
         */
        fun default(navigation: Boolean): StandaloneDrivingRule = if (navigation) ALLOWED else PARKED_ONLY
    }
}

/** How a launched app appears on the car screen right now. */
enum class StandaloneDisplay { SHOWN, AUDIO_ONLY, PAUSED }

/** Resolves [rule] (null: none set) for an app that is, or not, a [navigation] app, while [driving] or parked. */
fun standaloneDisplay(rule: StandaloneDrivingRule?, driving: Boolean, navigation: Boolean): StandaloneDisplay {
    if (!driving) return StandaloneDisplay.SHOWN
    return when (rule ?: StandaloneDrivingRule.default(navigation)) {
        StandaloneDrivingRule.ALLOWED -> StandaloneDisplay.SHOWN
        StandaloneDrivingRule.AUDIO_ONLY -> StandaloneDisplay.AUDIO_ONLY
        StandaloneDrivingRule.PARKED_ONLY -> StandaloneDisplay.PAUSED
    }
}

/** Reads the stored rules ("package\trule" entries), skipping any entry it doesn't understand. */
fun parseDrivingRules(stored: Set<String>): Map<String, StandaloneDrivingRule> = stored.mapNotNull { e ->
    val i = e.lastIndexOf('\t')
    if (i <= 0) return@mapNotNull null
    StandaloneDrivingRule.byKey(e.substring(i + 1))?.let { e.substring(0, i) to it }
}.toMap()

/** The stored form of [rules], read back by [parseDrivingRules]. */
fun formatDrivingRules(rules: Map<String, StandaloneDrivingRule>): Set<String> =
    rules.map { (pkg, rule) -> "$pkg\t${rule.key}" }.toSet()
