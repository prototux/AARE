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
 * A calendar date, like [java.time.LocalDate]: year/month/day only, equal and ordered by those
 * fields alone, with no zone or time-of-day of its own once computed — two [CalendarDay]s from
 * different zones compare equal exactly when they're the same date, the same as `LocalDate`.
 */
internal data class CalendarDay(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDay> {
    override fun compareTo(other: CalendarDay): Int =
        compareValuesBy(this, other, { it.year }, { it.month }, { it.day })

    /** This date advanced by one calendar day (a plain calendar-field add, like `LocalDate.plusDays(1)`). */
    fun plusOneDay(): CalendarDay = Calendar.getInstance().run {
        clear(); set(year, month, day); add(Calendar.DAY_OF_MONTH, 1)
        CalendarDay(get(Calendar.YEAR), get(Calendar.MONTH), get(Calendar.DAY_OF_MONTH))
    }

    /** This date's [Calendar.DAY_OF_WEEK] (`Calendar.SUNDAY`..`Calendar.SATURDAY`); zone-independent. */
    fun dayOfWeek(): Int = Calendar.getInstance().run { clear(); set(year, month, day); get(Calendar.DAY_OF_WEEK) }
}

/** The calendar date [epochMs] falls on for a reader in [zone]. */
private fun calendarDay(epochMs: Long, zone: TimeZone): CalendarDay = Calendar.getInstance(zone).run {
    timeInMillis = epochMs
    CalendarDay(get(Calendar.YEAR), get(Calendar.MONTH), get(Calendar.DAY_OF_MONTH))
}

/**
 * The calendar day [this] event falls on for a reader in [zone]. All-day events are stored at UTC
 * midnight of their date (CalendarContract), so their date is read in UTC: in local time they
 * would land a day early west of UTC. Timed events are read in [zone].
 */
internal fun NextEvent.day(zone: TimeZone): CalendarDay = calendarDay(startMs, if (allDay) UTC else zone)

/**
 * The events the calendar card shows, from the calendar's rows ([events], any order). One event
 * ([limit] 1): the next timed one of the coming 12 hours, else the first day's (its first timed
 * one, an all-day one only when there is none). More: an agenda in calendar order, day by day, a day's all-day events heading it, then by
 * start time. All-day events of a day already over are dropped: east of UTC, yesterday's one
 * still overlaps the query window until its UTC-midnight end. Pure, like [labelAgenda].
 */
fun selectAgenda(events: List<NextEvent>, limit: Int, nowMs: Long, zone: TimeZone): List<NextEvent> {
    if (limit <= 0) return emptyList()
    val today = calendarDay(nowMs, zone)
    val ordered = events.filter { !it.allDay || it.day(zone) >= today }
        .sortedWith(compareBy<NextEvent>({ it.day(zone) }, { !it.allDay }, { it.startMs }))
    if (limit > 1) return ordered.take(limit)
    ordered.firstOrNull { !it.allDay && it.startMs <= nowMs + 12 * 3_600_000L }?.let { return listOf(it) }
    val day = ordered.firstOrNull()?.day(zone) ?: return emptyList()
    val first = ordered.filter { it.day(zone) == day }
    return listOf(first.firstOrNull { !it.allDay } ?: first.first())
}

/** Days ahead (today not counted) the calendar card's automatic mode looks for an event; its setting's text (dash_calendar_auto) says so too. */
const val AGENDA_SOON_DAYS = 3

/** Whether one of [events] is today or within the next [AGENDA_SOON_DAYS] days. Pure, like [labelAgenda]. */
fun agendaSoon(events: List<NextEvent>, nowMs: Long, zone: TimeZone): Boolean {
    var last = calendarDay(nowMs, zone)
    repeat(AGENDA_SOON_DAYS) { last = last.plusOneDay() }
    return events.any { it.day(zone) <= last }
}

/**
 * Labels [events] for the agenda card ("today", "tomorrow", or the weekday), in the given order.
 * Pure: takes [nowMs] and [zone] instead of reading the real clock, so it's deterministic and
 * testable. All-day events are labelled by their own (UTC) date, see [day].
 */
fun labelAgenda(events: List<NextEvent>, nowMs: Long, zone: TimeZone): List<AgendaItem> {
    val today = calendarDay(nowMs, zone)
    val tomorrow = today.plusOneDay()
    return events.map { event ->
        val day = event.day(zone)
        val label = when {
            day == today -> AgendaDay.Today
            day == tomorrow -> AgendaDay.Tomorrow
            else -> AgendaDay.Later(day.dayOfWeek())
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
     * [limit] <= 0. Looks a week ahead. Blocking.
     */
    fun nextEvents(context: Context, limit: Int): List<NextEvent> {
        if (limit <= 0 || context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val now = System.currentTimeMillis()
        val windowMs = 7 * 24 * 3_600_000L
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
