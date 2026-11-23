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
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The weather where the car is, for the rail. */
data class Weather(val temperature: Int, val unit: String, val code: Int, val day: Boolean)

/** The next calendar event, for the dashboard. */
data class NextEvent(val title: String, val startMs: Long, val allDay: Boolean, val location: String)

/** A day in the agenda card, relative to when it's shown: today, tomorrow, or a later weekday. */
sealed interface AgendaDay {
    data object Today : AgendaDay
    data object Tomorrow : AgendaDay
    /** [dayOfWeek] is a [Calendar.DAY_OF_WEEK] value (`Calendar.SUNDAY`..`Calendar.SATURDAY`). */
    data class Later(val dayOfWeek: Int) : AgendaDay
}

/** One row of the agenda card: an event with its day label. */
data class AgendaItem(val event: NextEvent, val day: AgendaDay)

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

/**
 * The epoch millis of local midnight, in [zone], for the day [epochMs] falls on: a day, as a
 * comparable and orderable instant rather than a java.time LocalDate.
 */
private fun dayStart(epochMs: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
    timeInMillis = epochMs
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** [this] (a [dayStart]) advanced by one calendar day in [zone], DST-correct like [dayStart] itself. */
private fun Long.plusOneDay(zone: TimeZone): Long = Calendar.getInstance(zone).apply {
    timeInMillis = this@plusOneDay
    add(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

/** The [Calendar.DAY_OF_WEEK] of the day starting at [dayStartMs] (a [dayStart]) in [zone]. */
private fun dayOfWeek(dayStartMs: Long, zone: TimeZone): Int = Calendar.getInstance(zone).apply { timeInMillis = dayStartMs }.get(Calendar.DAY_OF_WEEK)

/**
 * The calendar day [this] event falls on for a reader in [zone], as its [dayStart]. All-day
 * events are stored at UTC midnight of their date (CalendarContract), so their date is read in
 * UTC: in local time they would land a day early west of UTC. Timed events are read in [zone].
 */
internal fun NextEvent.day(zone: TimeZone): Long = dayStart(startMs, if (allDay) UTC else zone)

/**
 * The events the calendar card shows, from the calendar's rows ([events], any order). One event
 * ([limit] 1, the card as it always was): the next timed one, an all-day one only when there is
 * none. More: an agenda in calendar order, day by day, a day's all-day events heading it, then by
 * start time. All-day events of a day already over are dropped: east of UTC, yesterday's one
 * still overlaps the query window until its UTC-midnight end. Pure, like [labelAgenda].
 */
fun selectAgenda(events: List<NextEvent>, limit: Int, nowMs: Long, zone: TimeZone): List<NextEvent> {
    if (limit <= 0) return emptyList()
    val today = dayStart(nowMs, zone)
    val ordered = events.filter { !it.allDay || it.day(zone) >= today }
        .sortedWith(compareBy<NextEvent>({ it.day(zone) }, { !it.allDay }, { it.startMs }))
    return if (limit == 1) listOfNotNull(ordered.firstOrNull { !it.allDay } ?: ordered.firstOrNull())
    else ordered.take(limit)
}

/**
 * Labels [events] for the agenda card ("today", "tomorrow", or the weekday), in the given order.
 * Pure: takes [nowMs] and [zone] instead of reading the real clock, so it's deterministic and
 * testable. All-day events are labelled by their own (UTC) date, see [day].
 */
fun labelAgenda(events: List<NextEvent>, nowMs: Long, zone: TimeZone): List<AgendaItem> {
    val today = dayStart(nowMs, zone)
    val tomorrow = today.plusOneDay(zone)
    return events.map { event ->
        val day = event.day(zone)
        val label = when {
            day == today -> AgendaDay.Today
            day == tomorrow -> AgendaDay.Tomorrow
            else -> AgendaDay.Later(dayOfWeek(day, zone))
        }
        AgendaItem(event, label)
    }
}

/** Whether temperatures are shown in Fahrenheit: the countries that use it, by the phone's locale. */
internal fun usesFahrenheit(locale: Locale = Locale.getDefault()): Boolean = locale.country in setOf("US", "LR", "MM")

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
        val fahrenheit = usesFahrenheit()
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

    /**
     * The next [limit] events, ordered by [selectAgenda]; empty without the calendar permission or
     * [limit] <= 0. A single event still looks only 12 hours ahead, as before; asking
     * for more widens the window to a week, so day labels like "tomorrow" have something to show.
     * Blocking.
     */
    fun nextEvents(context: Context, limit: Int): List<NextEvent> {
        if (limit <= 0 || context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val now = System.currentTimeMillis()
        val windowMs = if (limit <= 1) 12 * 3_600_000L else 7 * 24 * 3_600_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 15 * 60_000)
            ContentUris.appendId(it, now + windowMs)
        }.build()
        return runCatching {
            context.contentResolver.query(uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.EVENT_LOCATION),
                "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                // All rows of the window, ordered here: SQL can't sort all-day events (UTC midnight)
                // into the right local day. A week of events is small; the cap only guards odd calendars.
                val events = ArrayList<NextEvent>()
                while (events.size < 500 && c.moveToNext()) {
                    events += NextEvent(c.getString(0).orEmpty().ifBlank { "Event" }, c.getLong(1), c.getInt(2) == 1, c.getString(3).orEmpty())
                }
                selectAgenda(events, limit, now, TimeZone.getDefault())
            }
        }.getOrNull() ?: emptyList()
    }
}
