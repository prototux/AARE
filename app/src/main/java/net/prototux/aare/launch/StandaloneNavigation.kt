package net.prototux.aare.launch

import net.prototux.aare.mirror.KNOWN_NAVIGATION

/**
 * Whether the launched phone app [pkg] acts as the car's navigation app: a known navigation app
 * (the ones the phone screen also shows while driving), or one the user [marked] as one.
 */
fun isStandaloneNavigation(pkg: String, marked: Set<String>): Boolean = pkg in KNOWN_NAVIGATION || pkg in marked

/**
 * Whether the launcher helper may open [uri] on its display. It starts activities with shell or
 * root rights, so it opens only what it is asked for: a place (geo:) for a navigation app.
 */
fun launcherMayView(uri: String): Boolean = uri.startsWith("geo:", ignoreCase = true)
