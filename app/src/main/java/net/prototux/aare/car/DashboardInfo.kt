package net.prototux.aare.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.provider.CalendarContract
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** The weather where the car is, for the rail. */
data class Weather(val temperature: Int, val unit: String, val code: Int, val day: Boolean)

/** The next calendar event, for the dashboard. */
/** [key] tells this occurrence apart from the event's others, for [DashboardInfo.dismiss]. */
data class NextEvent(val title: String, val startMs: Long, val allDay: Boolean, val location: String, val key: String = "")

/**
 * What Android Auto shows around the map: the weather and the next calendar event. The weather
 * comes from Open-Meteo (no account, no Google), asked for an approximate position only.
 */
object DashboardInfo {
    private const val TAG = "AareDashboard"

    /** Blocking: call off the main thread. */
    fun weather(location: Location): Weather? = runCatching {
        // ~10 km is plenty for the weather, and says less about where the car is.
        val lat = "%.1f".format(Locale.ROOT, location.latitude)
        val lon = "%.1f".format(Locale.ROOT, location.longitude)
        val fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM")
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day" + if (fahrenheit) "&temperature_unit=fahrenheit" else "")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        try {
            val current = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            Weather(Math.round(current.getDouble("temperature_2m")).toInt(), if (fahrenheit) "°F" else "°", current.getInt("weather_code"),
                current.optInt("is_day", 1) == 1)
        } finally {
            c.disconnect()
        }
    }.onFailure { Log.i(TAG, "weather: ${it.message}") }.getOrNull()

    /** The phone's last known position, when the car doesn't send one. */
    @SuppressLint("MissingPermission")
    fun phoneLocation(context: Context): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java)
        return listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** The next event in the coming 12 hours (timed events first), else today's all-day event, or null. Blocking. */
    /** Hides [event] from the dashboard; the next one shows instead. Kept for two days, then forgotten. */
    fun dismiss(context: Context, event: NextEvent) {
        val prefs = dismissed(context)
        val old = System.currentTimeMillis() - 2 * 86_400_000L
        val keep = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty()
            .filter { (it.substringAfter(':').toLongOrNull() ?: 0) > old }.toSet()
        prefs.edit().putStringSet(KEY_DISMISSED, keep + event.key).apply()
    }

    private fun dismissed(context: Context) = context.applicationContext.getSharedPreferences("dashboard", Context.MODE_PRIVATE)
    private const val KEY_DISMISSED = "dismissed_events"

    fun nextEvent(context: Context): NextEvent? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val window = EventWindow(System.currentTimeMillis(), ZoneId.systemDefault())
        val hidden = dismissed(context).getStringSet(KEY_DISMISSED, emptySet()).orEmpty()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, window.queryFrom)
            ContentUris.appendId(it, window.queryTo)
        }.build()
        return runCatching {
            context.contentResolver.query(uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_ID),
                "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.ALL_DAY} ASC, ${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(2) == 1
                    if (!window.shows(c.getLong(1), c.getLong(4), allDay)) continue
                    val key = "${c.getLong(5)}:${c.getLong(1)}" // event id and this occurrence's start
                    if (key in hidden) continue
                    return@use NextEvent(c.getString(0).orEmpty().ifBlank { "Event" }, c.getLong(1), allDay, c.getString(3).orEmpty(), key)
                }
                null
            }
        }.getOrNull()
    }
}

/**
 * Which calendar instances count as next at [now]: timed events of the coming 12 hours, and
 * all-day events of today's date. The calendar stores an all-day event from UTC midnight to UTC
 * midnight whatever the phone's time zone, so it is matched by its date, not by the clock: west of
 * UTC tomorrow's all-day event "begins" this evening (a holiday showed up a day early as today's),
 * east of it yesterday's "ends" this morning.
 */
internal class EventWindow(now: Long, zone: ZoneId) {
    private val from = now - 15 * 60_000
    private val to = now + 12 * 3_600_000
    /** Today's date here, as the UTC midnight an all-day event of that date begins at. */
    private val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The range to ask the calendar for: wide enough for both kinds, narrowed by [shows]. */
    val queryFrom = minOf(from, today)
    val queryTo = maxOf(to, today + 24 * 3_600_000)

    fun shows(begin: Long, end: Long, allDay: Boolean): Boolean =
        if (allDay) begin <= today && (end > today || begin == today) else begin <= to && end >= from
}
