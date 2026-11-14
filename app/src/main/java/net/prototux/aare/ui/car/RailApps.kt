package net.prototux.aare.ui.car

/**
 * The apps the user chose for the rail (a setting, `Settings.railApps`), picked on the phone.
 * Empty: no choice was made, and the rail keeps its built-in set (today's behaviour).
 */

/** The key an app is listed under in the user's saved orders (appOrder, railApps): its package,
 * or its own id for AARE's built-in entries (phone, settings, notifications…), whose package is
 * AARE itself. Mirrors CarRuntime.orderKey, the scheme the launcher order already uses. */
internal fun CarAppEntry.orderKey(selfPackage: String): String = if (packageName == selfPackage) id else packageName

/**
 * [keys] (the user's chosen rail apps, in order) resolved against what is actually available now
 * ([apps]): kept only where a key matches an app, in the user's order. An app no longer installed,
 * or hidden from the car, is silently skipped — its key stays in the setting in case it returns.
 * Empty [keys]: no custom rail was chosen, so this returns empty too (the caller falls back to the
 * rail's built-in set).
 */
internal fun resolveRailApps(keys: List<String>, apps: List<CarAppEntry>, selfPackage: String): List<CarAppEntry> {
    if (keys.isEmpty()) return emptyList()
    val byKey = apps.associateBy { it.orderKey(selfPackage) }
    return keys.mapNotNull { byKey[it] }
}

/** This order with [key] chosen (added at the end) or dropped. */
internal fun List<String>.withKey(key: String, chosen: Boolean): List<String> =
    if (chosen) { if (key in this) this else this + key } else this - key
