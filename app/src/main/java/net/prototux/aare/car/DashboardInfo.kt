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
import java.util.Locale

/** The weather where the car is, for the rail. */
data class Weather(val temperature: Int, val unit: String, val code: Int, val day: Boolean)

/** The next calendar event, for the dashboard. */
data class NextEvent(val title: String, val startMs: Long, val allDay: Boolean, val location: String)

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

    /** The next event in the coming 12 hours (timed events first), or null. Blocking. */
    fun nextEvent(context: Context): NextEvent? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 15 * 60_000)
            ContentUris.appendId(it, now + 12 * 3_600_000)
        }.build()
        return runCatching {
            context.contentResolver.query(uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.EVENT_LOCATION),
                "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.ALL_DAY} ASC, ${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                if (c.moveToFirst()) NextEvent(c.getString(0).orEmpty().ifBlank { "Event" }, c.getLong(1), c.getInt(2) == 1, c.getString(3).orEmpty()) else null
            }
        }.getOrNull()
    }
}
