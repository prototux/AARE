package net.prototux.aare.launch

import android.content.pm.ApplicationInfo
import net.prototux.aare.mirror.KNOWN_NAVIGATION

/**
 * What the user allows a launched phone app to do while driving, per package. Passenger mode
 * (ignore driving) lifts all of them: the caller passes the car's restricted state, not the raw
 * driving status.
 */
enum class StandaloneDrivingRule(val key: String) {
    /** Paused while driving: its screen is hidden and its playback paused. The default. */
    PARKED_ONLY("parked"),
    /** Keeps running for its sound while driving, its screen hidden. */
    AUDIO_ONLY("audio"),
    /**
     * Shown while driving, and the car's navigation app (map slot, "navigate to"). Offered only to
     * navigation apps (see [isNavigationApp]); any other app with it stored is parked only.
     */
    NAVIGATION("navigation"),
    ;

    companion object {
        fun byKey(key: String): StandaloneDrivingRule? = entries.firstOrNull { it.key == key }

        /** The rules the phone offers for an app that is, or not, a [navigation] app. */
        fun offered(navigation: Boolean): List<StandaloneDrivingRule> =
            if (navigation) entries else entries - NAVIGATION
    }
}

/**
 * The rule that applies to an app with [rule] stored (null: none) that is, or not, a [navigation]
 * app: parked only unless the user chose otherwise, and never navigation for an app that isn't one.
 */
fun effectiveDrivingRule(rule: StandaloneDrivingRule?, navigation: Boolean): StandaloneDrivingRule =
    rule?.takeIf { it in StandaloneDrivingRule.offered(navigation) } ?: StandaloneDrivingRule.PARKED_ONLY

/**
 * Whether [pkg] is a navigation app, which may be given [StandaloneDrivingRule.NAVIGATION]: one the
 * phone screen also shows while driving ([KNOWN_NAVIGATION]), or one that declares itself a maps
 * app ([category], from its ApplicationInfo).
 */
fun isNavigationApp(pkg: String, category: Int): Boolean =
    pkg in KNOWN_NAVIGATION || category == ApplicationInfo.CATEGORY_MAPS

/** How a launched app appears on the car screen right now. */
enum class StandaloneDisplay { SHOWN, AUDIO_ONLY, PAUSED }

/**
 * Resolves [rule] (null: none set) for an app that is, or not, a [navigation] app, while driving
 * ([drivingRestricted]: false in passenger mode) or parked.
 */
fun standaloneDisplay(rule: StandaloneDrivingRule?, drivingRestricted: Boolean, navigation: Boolean): StandaloneDisplay {
    if (!drivingRestricted) return StandaloneDisplay.SHOWN
    return when (effectiveDrivingRule(rule, navigation)) {
        StandaloneDrivingRule.NAVIGATION -> StandaloneDisplay.SHOWN
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
