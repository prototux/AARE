package net.prototux.aare.faces

import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * A dashboard card described by a JSON file, like a smartwatch face: a canvas and the layers drawn
 * on it (text, hands, rings, shapes, icons, pictures), tied to the time, the weather and the rest.
 * The format is documented in docs/custom-cards.md. Files come from anywhere, so everything is
 * checked and bounded here; nothing in one can run code or reach the network.
 */

/** A card's file, read and checked. [strip] and [tall] replace [base] on a thin strip and on a tall card. */
class FaceSpec(
    val name: String,
    val author: String,
    val base: FaceLayout,
    val strip: FaceLayout?,
    val tall: FaceLayout?,
    /** What was ignored (unknown keys, mostly a typo), for the editor. */
    val warnings: List<String>,
) {
    /** Everything the card reads, over its layouts: what isn't used isn't fetched, and the seconds only tick for those showing them. */
    val needs: Set<FaceNeed> = listOfNotNull(base, strip, tall).flatMapTo(HashSet()) { it.needs }

    /** The layout for a card of this [width] and [height]. */
    fun layoutFor(width: Float, height: Float): FaceLayout = when {
        height <= 0f -> base
        strip != null && width / height >= STRIP_ASPECT -> strip
        tall != null && width / height <= TALL_ASPECT -> tall
        else -> base
    }

    companion object {
        const val FORMAT = 1
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_LAYERS = 200
        /** From this width/height up a card is a strip; from [TALL_ASPECT] down it is tall. */
        const val STRIP_ASPECT = 3f
        const val TALL_ASPECT = 0.9f

        /** Reads [json]; throws [FaceException] saying what is wrong and where. */
        fun parse(json: String): FaceSpec {
            if (json.length > MAX_BYTES) throw FaceException("the file is larger than ${MAX_BYTES / 1024 / 1024} MB")
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw FaceException("not valid JSON: ${syntaxError(e.message.orEmpty(), json)}")
            } catch (e: StackOverflowError) {
                throw FaceException("nested too deep")
            }
            return FaceParser().face(root)
        }

        /** org.json's message without the file it quotes whole, its position as a line and a column. */
        private fun syntaxError(message: String, json: String): String {
            // "Unterminated object at character 123 of {…" on Android; "… at 123 [character 5 line 7]" off it.
            val what = message.substringBefore(" at ").lineSequence().firstOrNull().orEmpty().take(80).ifBlank { "can't be read" }
            val at = Regex("""at character (\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, json.length) ?: return what
            val before = json.substring(0, at)
            return "$what (line ${before.count { it == '\n' } + 1}, column ${at - before.lastIndexOf('\n')})"
        }
    }
}

class FaceException(message: String) : Exception(message)

/** Something outside the file a card can read. */
enum class FaceNeed { SECONDS, WEATHER, CALENDAR, MEDIA, BATTERY, CAR }

class FaceLayout(val width: Float, val height: Float, val background: FacePaint?, val layers: List<FaceLayer>) {
    val needs: Set<FaceNeed> = layers.flatMapTo(HashSet()) { it.needs }
}

// ---------------------------------------------------------------- colours

sealed interface FacePaint

sealed interface FaceColor : FacePaint {
    /** One of the theme's colours ([NAMES]), so the card follows the car screen's style. */
    data class Theme(val name: String, val alpha: Float) : FaceColor
    data class Fixed(val argb: Int) : FaceColor
    data class DayNight(val day: FaceColor, val night: FaceColor) : FaceColor

    companion object {
        val NAMES = setOf("primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "secondary", "secondaryContainer", "onSecondaryContainer",
            "tertiary", "tertiaryContainer", "onTertiaryContainer", "surface", "onSurface", "onSurfaceVariant",
            "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest", "outline", "outlineVariant", "error")
    }
}

class FaceGradient(val colors: List<FaceColor>, val kind: Kind, val angle: Float) : FacePaint {
    enum class Kind { LINEAR, RADIAL, SWEEP }
}

// ---------------------------------------------------------------- values

/** A number a layer can follow, with the range it runs over unless the file says another. */
enum class FaceValue(val id: String, val min: Float, val max: Float, val need: FaceNeed? = null) {
    SECOND("second", 0f, 60f, FaceNeed.SECONDS),
    MINUTE("minute", 0f, 60f),
    HOUR("hour", 0f, 12f),
    HOUR24("hour24", 0f, 24f),
    /** 0 on Monday. */
    WEEKDAY("weekday", 0f, 7f),
    /** 0 on the 1st. */
    DAY("day", 0f, 31f),
    /** 0 in January. */
    MONTH("month", 0f, 12f),
    BATTERY("battery", 0f, 100f, FaceNeed.BATTERY),
    /** How far the song is, 0 to 1. */
    SONG("song", 0f, 1f, FaceNeed.MEDIA),
    TEMPERATURE("temperature", -20f, 50f, FaceNeed.WEATHER),
    SPEED("speed", 0f, 200f, FaceNeed.CAR),
    SPEED_MPH("speedMph", 0f, 120f, FaceNeed.CAR),
    RPM("rpm", 0f, 8000f, FaceNeed.CAR),
    FUEL("fuel", 0f, 100f, FaceNeed.CAR),
    COMPASS("compass", 0f, 360f, FaceNeed.CAR);

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** A fixed number, or a [value] mapped from [min]..[max] onto [from]..[to]. */
class FaceBinding(val value: FaceValue?, val constant: Float, val min: Float, val max: Float, val from: Float, val to: Float) {
    /** The number for [data], clamped to the range; null when the value isn't known now. */
    fun at(data: FaceData): Float? {
        val v = value ?: return constant
        val raw = data.value(v) ?: return null
        val f = ((raw - min) / (max - min)).coerceIn(0f, 1f)
        return from + f * (to - from)
    }
}

/** What a layer's `show` can ask for. */
enum class FaceCondition(val id: String, val need: FaceNeed? = null) {
    WEATHER("weather", FaceNeed.WEATHER),
    EVENT("event", FaceNeed.CALENDAR),
    MUSIC("music", FaceNeed.MEDIA),
    PLAYING("playing", FaceNeed.MEDIA),
    CHARGING("charging", FaceNeed.BATTERY),
    SPEED("speed", FaceNeed.CAR),
    FUEL("fuel", FaceNeed.CAR),
    DAY("day"),
    NIGHT("night"),
    AM("am"),
    PM("pm");

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

// ---------------------------------------------------------------- layers

/** What every layer has. Angles are in degrees, 0 at the top, clockwise; lengths in the layout's own units. */
class FaceCommon(
    val x: Float,
    val y: Float,
    val paint: FacePaint,
    val alpha: Float,
    val rotate: FaceBinding?,
    /** What [rotate] turns around. */
    val pivotX: Float,
    val pivotY: Float,
    /** All must hold; the flag is whether it must be true. */
    val show: List<Pair<FaceCondition, Boolean>>,
)

sealed class FaceLayer(val common: FaceCommon, own: Set<FaceNeed> = emptySet()) {
    val needs: Set<FaceNeed> = own + listOfNotNull(common.rotate?.value?.need) + common.show.mapNotNull { it.first.need }

    class Text(
        common: FaceCommon, val template: FaceTemplate, val size: Float, val font: Font, val weight: Int, val italic: Boolean,
        val align: Align, val valign: VAlign, val width: Float?, val lines: Int, val letterSpacing: Float, val lineHeight: Float?,
        val upper: Boolean, val timeZone: String?, val glow: FaceColor?, val glowRadius: Float,
    ) : FaceLayer(common, template.needs)

    class Circle(common: FaceCommon, val r: Float, val stroke: Float) : FaceLayer(common)
    class Rect(common: FaceCommon, val w: Float, val h: Float, val corner: Float, val stroke: Float) : FaceLayer(common)
    class Line(common: FaceCommon, val x2: Float, val y2: Float, val stroke: Float, val round: Boolean) : FaceLayer(common)

    /** A ring or part of one, filled as far as [value] says (0..1; all of it without one) over [track]. */
    class Arc(common: FaceCommon, val r: Float, val stroke: Float, val start: Float, val sweep: Float, val value: FaceBinding?, val track: FaceColor?, val round: Boolean) :
        FaceLayer(common, setOfNotNull(value?.value?.need))

    /** [count] marks around a circle of radius [r], each [length] long towards its centre; every [skip]th left out. */
    class Ticks(common: FaceCommon, val count: Int, val r: Float, val length: Float, val stroke: Float, val skip: Int, val start: Float, val sweep: Float, val dots: Boolean, val round: Boolean) : FaceLayer(common)

    /** [values] spread around a circle of radius [r], the first one at [start]. */
    class Numbers(common: FaceCommon, val values: List<String>, val r: Float, val size: Float, val font: Font, val weight: Int, val start: Float, val sweep: Float, val upright: Boolean) : FaceLayer(common)

    class Hand(common: FaceCommon, val value: FaceBinding, val length: Float, val tail: Float, val stroke: Float, val shape: Shape) :
        FaceLayer(common, setOfNotNull(value.value?.need)) {
        enum class Shape { ROUND, FLAT, TAPER, LEAF }
    }

    class Icon(common: FaceCommon, val name: String, val size: Float) :
        FaceLayer(common, if (name == "weather") setOf(FaceNeed.WEATHER) else emptySet())

    /** [bytes]: the picture's file. [tint]: all in the layer's colour. */
    class Image(common: FaceCommon, val bytes: ByteArray, val w: Float, val h: Float, val tint: Boolean) : FaceLayer(common)

    /** [data]: an SVG path, its origin at the layer's x and y. */
    class Path(common: FaceCommon, val data: String, val scale: Float, val stroke: Float, val round: Boolean) : FaceLayer(common)

    enum class Font { DEFAULT, SANS, CONDENSED, SERIF, MONO, CURSIVE, SEGMENT }
    enum class Align { START, CENTER, END }
    enum class VAlign { TOP, MIDDLE, BOTTOM, BASELINE }
}

// ---------------------------------------------------------------- parser

private class FaceParser {
    private val warnings = ArrayList<String>()
    private var layerCount = 0
    private var imageBytes = 0

    fun face(o: JSONObject): FaceSpec {
        val version = o.opt("aareCard") ?: throw FaceException("\"aareCard\": 1 is missing: this isn't a card file")
        if (version !is Int || version < 1) throw FaceException("aareCard: expected ${FaceSpec.FORMAT}")
        if (version > FaceSpec.FORMAT) throw FaceException("made for a newer AARE (aareCard $version)")
        val name = o.opt("name") as? String
        if (name.isNullOrBlank()) throw FaceException("\"name\" is missing")
        val base = layout(o, "", ROOT_KEYS)
        val strip = sub(o, "strip")?.let { layout(it, "strip.", LAYOUT_KEYS) }
        val tall = sub(o, "tall")?.let { layout(it, "tall.", LAYOUT_KEYS) }
        return FaceSpec(name.trim().take(60), (o.opt("author") as? String).orEmpty().trim().take(60), base, strip, tall, warnings)
    }

    private fun sub(o: JSONObject, key: String): JSONObject? = when (val v = o.opt(key)) {
        null, JSONObject.NULL -> null
        is JSONObject -> v
        else -> throw FaceException("$key: expected an object {…}")
    }

    private fun layout(o: JSONObject, at: String, known: Set<String>): FaceLayout {
        unknownKeys(o, at, known)
        val size = o.opt("size") as? JSONArray ?: throw FaceException("${at}size: expected [width, height]")
        if (size.length() != 2) throw FaceException("${at}size: expected [width, height]")
        val (w, h) = (0..1).map { i -> (size.opt(i) as? Number)?.toFloat()?.takeIf { it.isFinite() && it in 1f..10_000f } ?: throw FaceException("${at}size: expected two numbers from 1 to 10000") }
        val background = o.opt("background").takeUnless { it == JSONObject.NULL }?.let { paint(it, "${at}background") }
        val array = o.opt("layers") as? JSONArray ?: throw FaceException("${at}layers: expected a list […]")
        val layers = (0 until array.length()).map { i ->
            if (++layerCount > FaceSpec.MAX_LAYERS) throw FaceException("more than ${FaceSpec.MAX_LAYERS} layers")
            val here = "${at}layers[$i]"
            layer(array.opt(i) as? JSONObject ?: throw FaceException("$here: expected an object {…}"), here, w, h)
        }
        return FaceLayout(w, h, background, layers)
    }

    private fun layer(o: JSONObject, at: String, w: Float, h: Float): FaceLayer {
        val type = o.opt("type") as? String ?: throw FaceException("$at.type is missing")
        val keys = LAYER_KEYS[type] ?: throw FaceException("$at.type: unknown \"${type.take(30)}\" (${LAYER_KEYS.keys.joinToString()})")
        unknownKeys(o, "$at.", COMMON_KEYS + keys)
        // Paths are drawn in the layout's own coordinates unless moved; everything else starts centred.
        val x = num(o, "x", at, if (type == "path") 0f else w / 2)
        val y = num(o, "y", at, if (type == "path") 0f else h / 2)
        val pivot = o.opt("pivot") as? JSONArray
        if (pivot != null && pivot.length() != 2) throw FaceException("$at.pivot: expected [x, y]")
        fun pivot(i: Int, default: Float) = if (pivot == null) default else (pivot.opt(i) as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: throw FaceException("$at.pivot: expected [x, y]")
        val common = FaceCommon(
            x, y,
            paint = if (o.has("color")) paint(o.get("color"), "$at.color") else FaceColor.Theme("onSurface", 1f),
            alpha = num(o, "alpha", at, 1f).coerceIn(0f, 1f),
            rotate = if (o.has("rotate")) binding(o.get("rotate"), "$at.rotate", 0f, 360f) else null,
            pivotX = pivot(0, if (type == "path") w / 2 else x),
            pivotY = pivot(1, if (type == "path") h / 2 else y),
            show = show(o.opt("show"), "$at.show"),
        )
        return when (type) {
            "text" -> {
                val text = o.opt("text") as? String ?: throw FaceException("$at.text is missing")
                val tz = o.opt("timeZone") as? String
                // getTimeZone gives GMT for an id it doesn't know.
                if (tz != null && tz !in java.util.TimeZone.getAvailableIDs()) throw FaceException("$at.timeZone: unknown \"${tz.take(40)}\" (like \"Europe/Madrid\")")
                FaceLayer.Text(
                    common, FaceTemplate.parse(text.take(500), "$at.text"),
                    size = size(o, "size", at, 40f),
                    font = font(o, at), weight = weight(o, at), italic = bool(o, "italic", at, false),
                    align = choice(o, "align", at, FaceLayer.Align.CENTER, FaceLayer.Align.entries),
                    valign = choice(o, "valign", at, FaceLayer.VAlign.MIDDLE, FaceLayer.VAlign.entries),
                    width = if (o.has("width")) size(o, "width", at, 1f) else null,
                    lines = int(o, "lines", at, 1, 1..20),
                    letterSpacing = num(o, "letterSpacing", at, 0f),
                    lineHeight = if (o.has("lineHeight")) num(o, "lineHeight", at, 1f).coerceIn(0.3f, 5f) else null,
                    upper = bool(o, "upper", at, false),
                    timeZone = tz,
                    glow = if (o.has("glow")) color(o.get("glow"), "$at.glow") else null,
                    glowRadius = num(o, "glowRadius", at, 12f).coerceIn(0f, 200f),
                )
            }
            "circle" -> FaceLayer.Circle(common, size(o, "r", at), stroke(o, at, 0f))
            "rect" -> FaceLayer.Rect(common, size(o, "w", at), size(o, "h", at), num(o, "corner", at, 0f).coerceAtLeast(0f), stroke(o, at, 0f))
            "line" -> FaceLayer.Line(common, num(o, "x2", at), num(o, "y2", at), stroke(o, at, 2f), bool(o, "round", at, true))
            "arc" -> FaceLayer.Arc(
                common, size(o, "r", at), stroke(o, at, 8f), num(o, "start", at, 0f), num(o, "sweep", at, 360f).coerceIn(-360f, 360f),
                value = if (o.has("value")) binding(o.get("value"), "$at.value", 0f, 1f) else null,
                track = if (o.has("track")) color(o.get("track"), "$at.track") else null,
                round = bool(o, "round", at, true),
            )
            "ticks" -> FaceLayer.Ticks(
                common, int(o, "count", at, 60, 1..720), size(o, "r", at), num(o, "length", at, 8f), stroke(o, at, 2f),
                skip = int(o, "skip", at, 0, 0..720), start = num(o, "start", at, 0f), sweep = num(o, "sweep", at, 360f).coerceIn(-360f, 360f),
                dots = bool(o, "dots", at, false), round = bool(o, "round", at, true),
            )
            "numbers" -> FaceLayer.Numbers(
                common, numbers(o.opt("values"), "$at.values"), size(o, "r", at), size(o, "size", at, 24f), font(o, at), weight(o, at),
                start = num(o, "start", at, 0f), sweep = num(o, "sweep", at, 360f).coerceIn(-360f, 360f), upright = bool(o, "upright", at, true),
            )
            "hand" -> FaceLayer.Hand(
                common, binding(if (o.has("value")) o.get("value") else throw FaceException("$at.value is missing (like \"hour\", \"minute\" or \"second\")"), "$at.value", 0f, 360f),
                size(o, "length", at), num(o, "tail", at, 0f), stroke(o, at, 6f),
                shape = choice(o, "shape", at, FaceLayer.Hand.Shape.ROUND, FaceLayer.Hand.Shape.entries),
            )
            "icon" -> {
                val name = o.opt("icon") as? String ?: throw FaceException("$at.icon is missing")
                if (name !in ICONS) throw FaceException("$at.icon: unknown \"${name.take(30)}\" (${ICONS.joinToString()})")
                FaceLayer.Icon(common, name, size(o, "size", at, 40f))
            }
            "image" -> {
                val data = o.opt("data") as? String ?: throw FaceException("$at.data is missing: the picture as \"data:image/png;base64,…\"")
                if (!data.startsWith("data:image/") || ";base64," !in data) throw FaceException("$at.data: expected \"data:image/png;base64,…\"")
                val bytes = decodeBase64(data.substringAfter(";base64,")) ?: throw FaceException("$at.data: not valid base64")
                imageBytes += bytes.size
                if (imageBytes > FaceSpec.MAX_BYTES) throw FaceException("the pictures are larger than ${FaceSpec.MAX_BYTES / 1024 / 1024} MB")
                FaceLayer.Image(common, bytes, size(o, "w", at), size(o, "h", at), tint = o.has("color"))
            }
            else -> {
                val d = o.opt("d") as? String ?: throw FaceException("$at.d is missing: an SVG path")
                if (d.length > 20_000) throw FaceException("$at.d: longer than 20000 characters")
                FaceLayer.Path(common, d, num(o, "scale", at, 1f).coerceIn(0.001f, 1000f), stroke(o, at, 0f), bool(o, "round", at, true))
            }
        }
    }

    // ------------------------------------------------------------ parts

    /** Keys starting with "_" or "$" are comments. */
    private fun unknownKeys(o: JSONObject, at: String, known: Set<String>) {
        for (key in o.keys()) if (key !in known && !key.startsWith("_") && !key.startsWith("$") && warnings.size < 20) warnings += "$at${key.take(30)}: not a key of this, ignored"
    }

    private fun num(o: JSONObject, key: String, at: String, default: Float? = null): Float = when (val v = o.opt(key)) {
        null -> default ?: throw FaceException("$at.$key is missing")
        is Number -> v.toFloat().takeIf { it.isFinite() && it in -100_000f..100_000f } ?: throw FaceException("$at.$key: out of range")
        else -> throw FaceException("$at.$key: expected a number")
    }

    private fun size(o: JSONObject, key: String, at: String, default: Float? = null): Float =
        num(o, key, at, default).also { if (it <= 0f || it > 20_000f) throw FaceException("$at.$key: expected a number above 0") }

    private fun stroke(o: JSONObject, at: String, default: Float): Float =
        num(o, "stroke", at, default).also { if (it < 0f) throw FaceException("$at.stroke: can't be below 0") }

    private fun int(o: JSONObject, key: String, at: String, default: Int, range: IntRange): Int = when (val v = o.opt(key)) {
        null -> default
        is Int -> if (v in range) v else throw FaceException("$at.$key: expected ${range.first} to ${range.last}")
        else -> throw FaceException("$at.$key: expected a whole number")
    }

    private fun bool(o: JSONObject, key: String, at: String, default: Boolean): Boolean = when (val v = o.opt(key)) {
        null -> default
        is Boolean -> v
        else -> throw FaceException("$at.$key: expected true or false")
    }

    private fun <T : Enum<T>> choice(o: JSONObject, key: String, at: String, default: T, all: List<T>): T {
        if (!o.has(key)) return default
        val v = o.opt(key) as? String
        return all.firstOrNull { it.name.lowercase(Locale.ROOT) == v } ?: throw FaceException("$at.$key: expected ${all.joinToString { it.name.lowercase(Locale.ROOT) }}")
    }

    private fun font(o: JSONObject, at: String) = choice(o, "font", at, FaceLayer.Font.DEFAULT, FaceLayer.Font.entries)

    private fun weight(o: JSONObject, at: String): Int = when (val v = o.opt("weight")) {
        null -> 400
        is Int -> v.takeIf { it in 1..1000 }
        is String -> WEIGHTS[v]
        else -> null
    } ?: throw FaceException("$at.weight: expected ${WEIGHTS.keys.joinToString()} or 1 to 1000")

    private fun numbers(v: Any?, at: String): List<String> = when (v) {
        null, "12" -> listOf("12") + (1..11).map { "$it" }
        "4" -> listOf("12", "3", "6", "9")
        "24" -> listOf("24") + (1..23).map { "$it" }
        "60" -> listOf("60") + (5..55 step 5).map { "$it" }
        "roman" -> listOf("XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI")
        is JSONArray -> (0 until v.length()).map { v.opt(it)?.toString().orEmpty().take(20) }
            .also { if (it.isEmpty() || it.size > 60) throw FaceException("$at: expected 1 to 60 values") }
        else -> throw FaceException("$at: expected \"12\", \"4\", \"24\", \"60\", \"roman\" or a list")
    }

    private fun show(v: Any?, at: String): List<Pair<FaceCondition, Boolean>> {
        val names = when (v) {
            null -> return emptyList()
            is String -> listOf(v)
            is JSONArray -> (0 until v.length()).map { v.opt(it) as? String ?: throw FaceException("$at: expected names") }
            else -> throw FaceException("$at: expected a name or a list of them")
        }
        return names.map { n ->
            val c = FaceCondition.byId(n.removePrefix("!")) ?: throw FaceException("$at: unknown \"${n.take(30)}\" (${FaceCondition.entries.joinToString { it.id }})")
            c to !n.startsWith("!")
        }
    }

    private fun binding(v: Any, at: String, from: Float, to: Float): FaceBinding = when (v) {
        is Number -> FaceBinding(null, v.toFloat().takeIf { it.isFinite() } ?: throw FaceException("$at: out of range"), 0f, 1f, 0f, 0f)
        is String -> named(v, at).let { FaceBinding(it, 0f, it.min, it.max, from, to) }
        is JSONObject -> {
            unknownKeys(v, "$at.", setOf("value", "min", "max", "from", "to"))
            val value = named(v.opt("value") as? String ?: throw FaceException("$at.value is missing"), "$at.value")
            val min = num(v, "min", at, value.min)
            val max = num(v, "max", at, value.max)
            if (min == max) throw FaceException("$at: min and max are the same")
            FaceBinding(value, 0f, min, max, num(v, "from", at, from), num(v, "to", at, to))
        }
        else -> throw FaceException("$at: expected a number, a name or an object {…}")
    }

    private fun named(id: String, at: String): FaceValue =
        FaceValue.byId(id) ?: throw FaceException("$at: unknown \"${id.take(30)}\" (${FaceValue.entries.joinToString { it.id }})")

    private fun paint(v: Any, at: String): FacePaint {
        if (v !is JSONObject || !v.has("gradient")) return color(v, at)
        unknownKeys(v, "$at.", setOf("gradient", "kind", "angle"))
        val stops = v.opt("gradient") as? JSONArray
        if (stops == null || stops.length() !in 2..12) throw FaceException("$at.gradient: expected a list of 2 to 12 colours")
        return FaceGradient((0 until stops.length()).map { color(stops.get(it), "$at.gradient[$it]") },
            choice(v, "kind", at, FaceGradient.Kind.LINEAR, FaceGradient.Kind.entries), num(v, "angle", at, 180f))
    }

    private fun color(v: Any, at: String): FaceColor = when (v) {
        is String -> named(v) ?: throw FaceException("$at: unknown colour \"${v.take(30)}\" (#RGB, #RRGGBB, #AARRGGBB or ${FaceColor.NAMES.joinToString()})")
        is JSONObject -> {
            unknownKeys(v, "$at.", setOf("day", "night"))
            if (!v.has("day") || !v.has("night")) throw FaceException("$at: expected {\"day\": …, \"night\": …}")
            if (v.get("day") is JSONObject || v.get("night") is JSONObject) throw FaceException("$at: day and night are plain colours")
            FaceColor.DayNight(color(v.get("day"), "$at.day"), color(v.get("night"), "$at.night"))
        }
        else -> throw FaceException("$at: expected a colour")
    }

    /** "#…", or a theme colour with an optional opacity in percent ("primary/40"). */
    private fun named(s: String): FaceColor? {
        if (s.startsWith("#")) {
            val hex = s.drop(1)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val full = when (hex.length) {
                3 -> "ff" + hex.map { "$it$it" }.joinToString("")
                6 -> "ff$hex"
                8 -> hex
                else -> return null
            }
            return FaceColor.Fixed(full.toLong(16).toInt())
        }
        val name = s.substringBefore('/')
        val percent = if ('/' in s) s.substringAfter('/').toIntOrNull()?.takeIf { it in 0..100 } ?: return null else 100
        return if (name in FaceColor.NAMES) FaceColor.Theme(name, percent / 100f) else null
    }

    private companion object {
        val ROOT_KEYS = setOf("aareCard", "name", "author", "size", "background", "layers", "strip", "tall")
        val LAYOUT_KEYS = setOf("size", "background", "layers")
        val COMMON_KEYS = setOf("type", "x", "y", "color", "alpha", "rotate", "pivot", "show")
        val LAYER_KEYS = linkedMapOf(
            "text" to setOf("text", "size", "font", "weight", "italic", "align", "valign", "width", "lines", "letterSpacing", "lineHeight", "upper", "timeZone", "glow", "glowRadius"),
            "circle" to setOf("r", "stroke"),
            "rect" to setOf("w", "h", "corner", "stroke"),
            "line" to setOf("x2", "y2", "stroke", "round"),
            "arc" to setOf("r", "stroke", "start", "sweep", "value", "track", "round"),
            "ticks" to setOf("count", "r", "length", "stroke", "skip", "start", "sweep", "dots", "round"),
            "numbers" to setOf("values", "r", "size", "font", "weight", "start", "sweep", "upright"),
            "hand" to setOf("value", "length", "tail", "stroke", "shape"),
            "icon" to setOf("icon", "size"),
            "image" to setOf("data", "w", "h"),
            "path" to setOf("d", "scale", "stroke", "round"),
        )
        val WEIGHTS = linkedMapOf("thin" to 100, "light" to 300, "normal" to 400, "medium" to 500, "bold" to 700, "black" to 900)
    }
}

/** The icons a card can draw; "weather" follows the sky. */
internal val ICONS = setOf("weather", "calendar", "music", "battery", "bolt", "place", "clock", "car", "fuel", "thermostat", "compass", "heart", "star", "home", "person", "notifications")

/** Plain base64 (line breaks allowed). By hand: java.util.Base64 is API 26 (minSdk is 23), android.util's isn't there in unit tests. */
internal fun decodeBase64(s: String): ByteArray? {
    val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (c in s) {
        val v = when (c) {
            in 'A'..'Z' -> c - 'A'
            in 'a'..'z' -> c - 'a' + 26
            in '0'..'9' -> c - '0' + 52
            '+' -> 62
            '/' -> 63
            '=' -> break
            '\n', '\r', ' ' -> continue
            else -> return null
        }
        buffer = (buffer shl 6) or v
        bits += 6
        if (bits >= 8) { bits -= 8; out.write(buffer shr bits and 0xff) }
    }
    return out.toByteArray()
}

/** Checks a date pattern here, so a wrong one is an error in the editor and not a crash while driving. */
internal fun validPattern(pattern: String): Boolean = runCatching { SimpleDateFormat(pattern, Locale.ROOT) }.isSuccess
