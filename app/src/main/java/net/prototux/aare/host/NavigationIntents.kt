package net.prototux.aare.host

import java.net.URLDecoder

/** A navigation request as a navigation car app receives it: the intent's action, data and extras. */
data class NavigationRequest(
    val action: String,
    val data: String?,
    /** The "avoid" extra: some of t (tolls), h (highways), f (ferries). Waze format only. */
    val avoid: String? = null,
)

/*
 * Navigation intents, converted before a navigation car
 * app sees them. The Car App Library's format is ACTION_NAVIGATE with `geo:lat,lng` or
 * `geo:0,0?q=query`; requests also come as Google Maps URLs (https://maps.google.com/maps?q=...),
 * `google.navigation:q=...`, or Waze's voice commands (`geo.action:?act=mute`). Waze (from version
 * 1023098) takes its own variant: a Maps URL's entry=sar becomes a search, dirflg the "avoid" extra,
 * and act= a VIEW intent with Waze's command.
 *
 * Strings rather than android.net.Uri, so this runs in unit tests.
 */

private const val ACTION_NAVIGATE = "androidx.car.app.action.NAVIGATE"
private const val ACTION_VIEW = "android.intent.action.VIEW"
private const val ACTION_SEARCH = "android.intent.action.SEARCH"

/** The extra an assistant sets for "navigate home/work": the app resolves the place itself. */
const val EXTRA_LOCATION_ALIAS = "com.google.android.googlequicksearchbox.LocationAlias"

private val NAVIGATION_SCHEMES = setOf("geo", "google.navigation", "google.maps", "geo.action")
private val MAPS_URLS = listOf("http://maps.google.com", "https://maps.google.com", "https://assistant-maps.google.com")

/** Route options in a Maps URL's dirflg, in the order Waze's "avoid" extra lists them. */
private val AVOID_FLAGS = listOf("t", "h", "f")

/** The parameters of a Waze report (act=report_hazard&hazard_type=...) that go along with act. */
private val WAZE_REPORT_PARAMS = setOf("accident_type", "road_direction", "location_on_road", "hazard_type", "traffic_type")

/** Whether [data] is something treated as a navigation request. */
fun isNavigationData(data: String?): Boolean =
    data != null && (schemeOf(data) in NAVIGATION_SCHEMES || MAPS_URLS.any { data.startsWith(it) })

/**
 * A Car App Library navigation intent's data as a Google Maps URL, the form a car
 * app's own navigation request takes: `geo:lat,lng` or `geo:0,0?q=...` becomes
 * `https://maps.google.com/maps?nav=1&q=...`. Null when it has neither a place nor a query.
 */
fun mapsUrlOf(geo: String): String? {
    location(geo)?.let { (lat, lng) -> return "https://maps.google.com/maps?nav=1&q=$lat,$lng" }
    return query(geo)?.let { "https://maps.google.com/maps?nav=1&q=" + it.replace(WHITESPACE, "+") }
}

/**
 * The request a navigation car app gets for an intent with [action] and [data],
 * converted. [locationAlias]: the intent has [EXTRA_LOCATION_ALIAS]. [wazeFormat]: the
 * target is Waze at a version that takes its own format (see [AppQuirks.WAZE_NAVIGATION_FORMAT]).
 * Data that isn't a navigation request ([isNavigationData]) is left as it is.
 */
fun toCarAppNavigation(action: String?, data: String?, locationAlias: Boolean, wazeFormat: Boolean): NavigationRequest {
    val unchanged = NavigationRequest(action.orEmpty(), data)
    if (data == null || !isNavigationData(data)) return unchanged
    val alreadyCarAppFormat = if (hasFormat(data, "geo.action")) {
        action == ACTION_VIEW && hasParam(data, "act")
    } else {
        (locationAlias && action == ACTION_NAVIGATE) ||
            ((action == ACTION_NAVIGATE || action == ACTION_VIEW) && hasFormat(data, "geo") && hasQueryOrLocation(data))
    }
    if (alreadyCarAppFormat) return unchanged
    return if (wazeFormat) toWazeFormat(data) else NavigationRequest(ACTION_NAVIGATE, toGeo(data) ?: data)
}

/**
 * Whether [request] is a well-formed Waze request: a home/work navigation ([locationAlias]), a
 * VIEW of a geo: or geo.action: command (act=), or a navigation or search to a geo: place or
 * query. Anything else falls back to the plain geo: navigation intent.
 */
fun isWazeRequest(request: NavigationRequest, locationAlias: Boolean): Boolean {
    if (locationAlias && request.action == ACTION_NAVIGATE) return true
    val data = request.data ?: return false
    return when (request.action) {
        ACTION_VIEW -> (hasFormat(data, "geo") || hasFormat(data, "geo.action")) && hasParam(data, "act")
        ACTION_NAVIGATE, ACTION_SEARCH -> hasFormat(data, "geo") && hasQueryOrLocation(data)
        else -> false
    }
}

private fun toWazeFormat(data: String): NavigationRequest {
    val hierarchical = isHierarchical(data)
    val action = when {
        !hierarchical -> ACTION_NAVIGATE
        // The check looks for "act" anywhere in the URL, which a place name like "Factory Lane"
        // also matches and turns into an empty Waze command: only a real act= parameter counts here.
        hasParam(data, "act") -> ACTION_VIEW
        param(data, "entry") == "sar" -> ACTION_SEARCH
        else -> ACTION_NAVIGATE
    }
    val avoid = if (hierarchical) param(data, "dirflg")?.let { flags -> AVOID_FLAGS.filter { it in flags }.joinToString("") }?.ifEmpty { null } else null
    val converted = if (action == ACTION_VIEW) {
        "geo:0,0?act=" + param(data, "act") + params(data).filter { it.first in WAZE_REPORT_PARAMS }.joinToString("") { "&${it.first}=${it.second}" }
    } else {
        toGeo(data)
    }
    return NavigationRequest(action, converted ?: data, avoid)
}

/** `geo:lat,lng` for a place, `geo:0,0?q=...` for a query, null for neither. */
private fun toGeo(data: String): String? {
    location(data)?.let { (lat, lng) -> return "geo:$lat,$lng" }
    return query(data)?.let { "geo:0,0?q=" + it.replace(WHITESPACE, "+") }
}

private val WHITESPACE = Regex("\\s")

/** The URI's scheme, or null when it has none. */
private fun schemeOf(data: String): String? {
    val end = data.indexOf(':')
    if (end <= 0) return null
    val scheme = data.substring(0, end)
    return scheme.takeIf { s -> s.none { it == '/' || it == '?' || it == '#' } }
}

/** The URI is [scheme]: (its scheme, or the text starts with "scheme:"). */
private fun hasFormat(data: String, scheme: String) = schemeOf(data) == scheme || data.startsWith("$scheme:")

/** Everything after the scheme, without the fragment, still encoded. */
private fun schemeSpecificPart(data: String): String {
    val start = schemeOf(data)?.let { it.length + 1 } ?: 0
    return data.substring(start).substringBefore('#')
}

private fun isHierarchical(data: String) = schemeOf(data) == null || schemeSpecificPart(data).startsWith("/")

/** The q= (or else daddr=) value, still encoded: the place searched for. */
private fun query(data: String): String? {
    val ssp = schemeSpecificPart(data)
    for (key in listOf("q", "daddr")) {
        val at = ssp.indexOf("$key=")
        if (at >= 0) return ssp.substring(at + key.length + 1).substringBefore('&')
    }
    return null
}

/** The coordinates in the query, or the scheme-specific part itself ("48.85,2.29"). */
private fun location(data: String): Pair<Double, Double>? {
    val text = query(data) ?: schemeSpecificPart(data)
    val parts = text.split(',')
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lng = parts[1].toDoubleOrNull() ?: return null
    return lat to lng
}

private fun hasQueryOrLocation(data: String) = 'q' in data || location(data) != null

/** The query's parameters in order, decoded (+ is a space), as Uri.getQueryParameter gives them. */
private fun params(data: String): List<Pair<String, String>> {
    val query = data.substringBefore('#').substringAfter('?', "")
    if (query.isEmpty()) return emptyList()
    return query.split('&').filter { it.isNotEmpty() }.map { part ->
        decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
    }
}

private fun param(data: String, name: String): String? = params(data).firstOrNull { it.first == name }?.second

private fun hasParam(data: String, name: String) = params(data).any { it.first == name }

private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
