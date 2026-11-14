package net.prototux.aare.launch

/**
 * Whether a launched phone app with the driving [rule] (null: none set) acts as the car's
 * navigation app: a [navigationApp] (see [isNavigationApp]) the user gave the navigation rule.
 */
fun isStandaloneNavigation(rule: StandaloneDrivingRule?, navigationApp: Boolean): Boolean =
    effectiveDrivingRule(rule, navigationApp) == StandaloneDrivingRule.NAVIGATION

/**
 * Whether the launcher helper may open [uri] on its display. It starts activities with shell or
 * root rights, so it opens only what it is asked for: a place (geo:) for a navigation app.
 */
fun launcherMayView(uri: String): Boolean = uri.startsWith("geo:", ignoreCase = true)
