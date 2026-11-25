package net.prototux.aare.faces

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** What cards show, at one moment. Pure: the clock, zone and locale are given, so it can be tested. */
data class FaceData(
    val nowMs: Long,
    val zone: TimeZone = TimeZone.getDefault(),
    val locale: Locale = Locale.getDefault(),
    val hours24: Boolean = true,
    /** Localized date patterns: "Wednesday 25 November", and the same shortened. */
    val longDate: String = "EEEE d MMMM",
    val shortDate: String = "EEE d MMM",
    /** The car screen is in its night colours. */
    val night: Boolean = true,
    val temperature: Int? = null,
    val temperatureUnit: String = "°",
    val event: String? = null,
    /** What {event} says with no event coming up. */
    val noEvents: String = "",
    val eventTime: String = "",
    val eventDay: String = "",
    val eventPlace: String = "",
    val track: String? = null,
    val artist: String = "",
    val playing: Boolean = false,
    val song: Float? = null,
    val battery: Int? = null,
    val charging: Boolean = false,
    val car: Car = Car(),
) {
    /** From the car; null: it doesn't say. */
    data class Car(val kmh: Float? = null, val rpm: Float? = null, val fuel: Int? = null, val rangeKm: Int? = null, val outsideC: Float? = null, val compass: Float? = null)

    private val calendar: Calendar = Calendar.getInstance(zone, locale).apply { timeInMillis = nowMs }

    fun value(v: FaceValue): Float? {
        val second = calendar.get(Calendar.SECOND).toFloat()
        val minute = calendar.get(Calendar.MINUTE) + second / 60
        val hour = calendar.get(Calendar.HOUR_OF_DAY) + minute / 60
        return when (v) {
            FaceValue.SECOND -> second
            FaceValue.MINUTE -> minute
            FaceValue.HOUR -> hour % 12
            FaceValue.HOUR24 -> hour
            FaceValue.WEEKDAY -> (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7f
            FaceValue.DAY -> calendar.get(Calendar.DAY_OF_MONTH) - 1f
            FaceValue.MONTH -> calendar.get(Calendar.MONTH).toFloat()
            FaceValue.BATTERY -> battery?.toFloat()
            FaceValue.SONG -> song
            FaceValue.TEMPERATURE -> temperature?.toFloat()
            FaceValue.SPEED -> car.kmh
            FaceValue.SPEED_MPH -> car.kmh?.div(KM_PER_MILE)
            FaceValue.RPM -> car.rpm
            FaceValue.FUEL -> car.fuel?.toFloat()
            FaceValue.COMPASS -> car.compass
        }
    }

    fun holds(c: FaceCondition): Boolean = when (c) {
        FaceCondition.WEATHER -> temperature != null
        FaceCondition.EVENT -> event != null
        FaceCondition.MUSIC -> track != null
        FaceCondition.PLAYING -> track != null && playing
        FaceCondition.CHARGING -> charging
        FaceCondition.SPEED -> car.kmh != null
        FaceCondition.FUEL -> car.fuel != null
        FaceCondition.DAY -> !night
        FaceCondition.NIGHT -> night
        FaceCondition.AM -> calendar.get(Calendar.AM_PM) == Calendar.AM
        FaceCondition.PM -> calendar.get(Calendar.AM_PM) == Calendar.PM
    }

    fun shows(layer: FaceLayer): Boolean = layer.common.show.all { (c, wanted) -> holds(c) == wanted }

    fun time(pattern: String, timeZone: String?): String = SimpleDateFormat(pattern, locale)
        .apply { this.timeZone = timeZone?.let(TimeZone::getTimeZone) ?: zone }.format(nowMs)

    companion object {
        const val KM_PER_MILE = 1.609344f
        /** For a number that isn't known now. */
        const val UNKNOWN = "–"
    }
}

/** A text with `{tokens}`, e.g. "{time:HH:mm} · {temp}". "{{" is a plain brace. */
class FaceTemplate private constructor(private val parts: List<Part>) {
    private class Part(val text: String, val token: FaceToken?, val argument: String?)

    val needs: Set<FaceNeed> = parts.mapNotNullTo(HashSet()) { p ->
        // A pattern that shows seconds (outside quotes) makes the card tick with them.
        if (p.token == FaceToken.TIME && p.argument != null && 's' in p.argument.replace(Regex("'[^']*'"), "")) FaceNeed.SECONDS else p.token?.need
    }

    fun text(data: FaceData, timeZone: String? = null): String = buildString {
        for (p in parts) append(p.token?.text(data, p.argument, timeZone) ?: p.text)
    }

    companion object {
        fun parse(text: String, at: String): FaceTemplate {
            val parts = ArrayList<Part>()
            val plain = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    (c == '{' || c == '}') && text.getOrNull(i + 1) == c -> { plain.append(c); i += 2 }
                    c == '{' -> {
                        val end = text.indexOf('}', i)
                        if (end < 0) throw FaceException("$at: a { without its }")
                        val body = text.substring(i + 1, end)
                        val name = body.substringBefore(':')
                        val argument = if (':' in body) body.substringAfter(':') else null
                        val token = FaceToken.byId(name) ?: throw FaceException("$at: unknown {${name.take(30)}} (${FaceToken.entries.joinToString { it.id }})")
                        if (argument != null && !token.pattern) throw FaceException("$at: {$name} takes no format")
                        if (argument != null && (argument.isBlank() || !validPattern(argument))) throw FaceException("$at: not a date format: \"${argument.take(40)}\"")
                        if (plain.isNotEmpty()) { parts += Part(plain.toString(), null, null); plain.clear() }
                        parts += Part("", token, argument)
                        i = end + 1
                    }
                    else -> { plain.append(c); i++ }
                }
            }
            if (plain.isNotEmpty()) parts += Part(plain.toString(), null, null)
            return FaceTemplate(parts)
        }
    }
}

/** What can be between braces in a text. [pattern]: takes a date format after a colon. */
enum class FaceToken(val id: String, val need: FaceNeed? = null, val pattern: Boolean = false) {
    TIME("time", pattern = true),
    HOUR("hour"),
    MINUTE("minute"),
    SECOND("second", FaceNeed.SECONDS),
    AMPM("ampm"),
    DATE("date", pattern = true),
    SHORT_DATE("shortDate"),
    WEEKDAY("weekday"),
    DAY("day"),
    MONTH("month"),
    YEAR("year"),
    TEMP("temp", FaceNeed.WEATHER),
    OUTSIDE("outside", FaceNeed.CAR),
    EVENT("event", FaceNeed.CALENDAR),
    EVENT_TIME("eventTime", FaceNeed.CALENDAR),
    EVENT_DAY("eventDay", FaceNeed.CALENDAR),
    EVENT_PLACE("eventPlace", FaceNeed.CALENDAR),
    TRACK("track", FaceNeed.MEDIA),
    ARTIST("artist", FaceNeed.MEDIA),
    BATTERY("battery", FaceNeed.BATTERY),
    SPEED("speed", FaceNeed.CAR),
    SPEED_MPH("speedMph", FaceNeed.CAR),
    RPM("rpm", FaceNeed.CAR),
    FUEL("fuel", FaceNeed.CAR),
    RANGE("range", FaceNeed.CAR);

    fun text(d: FaceData, argument: String?, timeZone: String?): String = when (this) {
        TIME -> d.time(argument ?: if (d.hours24) "H:mm" else "h:mm", timeZone)
        HOUR -> d.time(if (d.hours24) "HH" else "h", timeZone)
        MINUTE -> d.time("mm", timeZone)
        SECOND -> d.time("ss", timeZone)
        AMPM -> if (d.hours24) "" else d.time("a", timeZone)
        DATE -> d.time(argument ?: d.longDate, timeZone).capitalized(d.locale)
        SHORT_DATE -> d.time(d.shortDate, timeZone).capitalized(d.locale)
        WEEKDAY -> d.time("EEEE", timeZone).capitalized(d.locale)
        DAY -> d.time("d", timeZone)
        MONTH -> d.time("LLLL", timeZone).capitalized(d.locale)
        YEAR -> d.time("yyyy", timeZone)
        TEMP -> d.temperature?.let { "$it${d.temperatureUnit}" } ?: FaceData.UNKNOWN
        OUTSIDE -> d.car.outsideC?.let { c -> if (d.temperatureUnit == "°F") "${Math.round(c * 9 / 5 + 32)}°F" else "${Math.round(c)}°" } ?: FaceData.UNKNOWN
        EVENT -> d.event ?: d.noEvents
        EVENT_TIME -> d.eventTime
        EVENT_DAY -> d.eventDay
        EVENT_PLACE -> d.eventPlace
        TRACK -> d.track.orEmpty()
        ARTIST -> d.artist
        BATTERY -> d.battery?.toString() ?: FaceData.UNKNOWN
        SPEED -> number(d.car.kmh)
        SPEED_MPH -> number(d.car.kmh?.div(FaceData.KM_PER_MILE))
        RPM -> number(d.car.rpm)
        FUEL -> d.car.fuel?.toString() ?: FaceData.UNKNOWN
        RANGE -> d.car.rangeKm?.toString() ?: FaceData.UNKNOWN
    }

    private fun number(v: Float?): String = v?.let { Math.round(it).toString() } ?: FaceData.UNKNOWN

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** Weekdays and months are lower case in many languages; on a card they start a line. */
private fun String.capitalized(locale: Locale): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
