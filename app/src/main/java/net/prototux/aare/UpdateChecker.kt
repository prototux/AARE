package net.prototux.aare

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.prototux.aare.service.ProjectionService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Looks on GitHub for a newer AARE release and, if there is one, surfaces it as a warning on the
 * home screen (no popup). On by default; switched off in Connection settings it never touches the
 * network. The check is one unauthenticated HTTPS request to the public releases API, at most once
 * a day, never while a car is connected, and silent on any failure. The result is cached so the
 * warning shows at once on the next start without asking again.
 */
object UpdateChecker {
    private const val TAG = "AareUpdate"
    private const val LATEST = "https://api.github.com/repos/prototux/AARE/releases/latest"
    private const val RELEASES = "https://github.com/prototux/AARE/releases/latest"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** A release newer than the installed one. [version] has no leading "v". */
    data class Update(val version: String, val url: String)

    private val _available = MutableStateFlow<Update?>(null)
    val available: StateFlow<Update?> = _available.asStateFlow()

    @Volatile private var checking = false

    /**
     * Refreshes the home-screen warning. Returns at once: it shows any cached result and, if due,
     * asks GitHub on a background thread. Safe to call on every app open.
     */
    fun refresh(context: Context) {
        val settings = Settings(context.applicationContext)
        if (!settings.checkUpdates || BuildConfig.DEBUG) { _available.value = null; return }
        _available.value = cached(settings)
        // Don't add traffic while a car session is up; the cached warning is enough.
        if (ProjectionService.state.value.connected) return
        val now = System.currentTimeMillis()
        if (checking || now - settings.updateLastCheckMs < DAY_MS) return
        checking = true
        thread(isDaemon = true, name = "aare-update-check") {
            try {
                fetchLatest()?.let { latest ->
                    settings.updateLastCheckMs = System.currentTimeMillis()
                    settings.updateLatestVersion = latest.version
                    settings.updateLatestUrl = latest.url
                    _available.value = cached(settings)
                }
            } finally {
                checking = false
            }
        }
    }

    /** Stops showing the current update; it comes back only for a version newer than this one. */
    fun dismiss(context: Context) {
        val settings = Settings(context.applicationContext)
        settings.updateLatestVersion?.let { settings.updateDismissedVersion = it }
        _available.value = null
    }

    /** The warning to show now, from the cache, the installed version and what the user dismissed. */
    private fun cached(settings: Settings): Update? {
        val latest = settings.updateLatestVersion ?: return null
        if (!isNewer(latest, BuildConfig.VERSION_NAME)) return null
        if (latest == settings.updateDismissedVersion) return null
        return Update(latest, settings.updateLatestUrl ?: RELEASES)
    }

    private fun fetchLatest(): Update? = runCatching {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "AARE")
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v").ifBlank { return null }
            Update(tag, json.optString("html_url").ifBlank { RELEASES })
        } finally {
            c.disconnect()
        }
    }.onFailure { Log.i(TAG, "update check: ${it.message}") }.getOrNull()

    /**
     * True when [latest] is a strictly higher semantic version than [installed], comparing numbers
     * so "1.10.0" beats "1.9.0". A missing part counts as 0 ("1.2" == "1.2.0").
     */
    internal fun isNewer(latest: String, installed: String): Boolean {
        val a = parts(latest)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").takeWhile { it.isDigit() || it == '.' }
            .split('.').mapNotNull { it.toIntOrNull() }
}
