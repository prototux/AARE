package net.prototux.aare.ui.car

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import net.prototux.aare.R
import net.prototux.aare.car.CarData
import net.prototux.aare.car.Weather
import net.prototux.aare.car.labelAgenda
import net.prototux.aare.car.usesFahrenheit
import net.prototux.aare.faces.FaceColor
import net.prototux.aare.faces.FaceData
import net.prototux.aare.faces.FaceGradient
import net.prototux.aare.faces.FaceLayer
import net.prototux.aare.faces.FaceLayout
import net.prototux.aare.faces.FaceNeed
import net.prototux.aare.faces.FacePaint
import net.prototux.aare.faces.FaceSpec
import net.prototux.aare.ui.theme.SegmentFont
import java.util.TimeZone

/** A card drawn from its file ([spec]), filling [modifier], with what the car screen knows now. */
@Composable
internal fun FaceView(spec: FaceSpec, state: CarUiState, modifier: Modifier) {
    val data = rememberFaceData(spec, state)
    FaceCanvas(spec, data, state.weather, modifier)
}

/** The time, ticking only as often as it shows: every second, or on the minute. */
@Composable
private fun rememberNow(seconds: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(seconds) {
        val step = if (seconds) 1_000L else 60_000L
        while (true) {
            now = System.currentTimeMillis()
            delay(step - now % step + 5)
        }
    }
    return now
}

@Composable
private fun rememberFaceData(spec: FaceSpec, state: CarUiState): FaceData {
    val context = LocalContext.current
    val needs = spec.needs
    // The song's progress moves with the clock: no need for a finer tick than a second.
    val playing = state.nowPlaying?.playing == true
    val now = rememberNow(FaceNeed.SECONDS in needs || (FaceNeed.MEDIA in needs && playing))
    val base = clockData(now)
    val carData by remember { CarData.state.map { it.faceCar() }.distinctUntilChanged() }.collectAsState(FaceData.Car())
    val phone = if (FaceNeed.BATTERY in needs) rememberPhoneStatus() else null
    val event = if (FaceNeed.CALENDAR in needs) state.nextEvents.firstOrNull() else null
    val item = event?.let { labelAgenda(listOf(it), now, TimeZone.getDefault()).first() }
    val np = state.nowPlaying?.takeIf { FaceNeed.MEDIA in needs }
    return base.copy(
        temperature = state.weather?.temperature,
        temperatureUnit = state.weather?.unit ?: if (usesFahrenheit()) "°F" else "°",
        event = event?.title, noEvents = stringResource(R.string.dash_no_events),
        eventTime = event?.takeIf { !it.allDay }?.let { DateFormat.getTimeFormat(context).format(it.startMs) }.orEmpty(),
        eventDay = item?.let { agendaDay(it, context) }.orEmpty(),
        eventPlace = event?.location.orEmpty(),
        track = np?.title, artist = np?.subtitle.orEmpty(), playing = np?.playing == true,
        song = np?.takeIf { it.durationMs > 0 }?.let { mediaProgress(it.positionMs, it.durationMs, it.playing, it.updatedAtMs, android.os.SystemClock.elapsedRealtime()) },
        battery = phone?.battery, charging = phone?.charging == true,
        car = if (FaceNeed.CAR in needs) carData else FaceData.Car(),
    )
}

/** The time with this phone's formats, and the car screen's day or night. */
@Composable
private fun clockData(now: Long): FaceData {
    val context = LocalContext.current
    val locale = java.util.Locale.getDefault()
    val long = remember(locale) { DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM") }
    val short = remember(locale) { DateFormat.getBestDateTimePattern(locale, "EEEdMMM") }
    return FaceData(now, hours24 = DateFormat.is24HourFormat(context), longDate = long, shortDate = short,
        night = MaterialTheme.colorScheme.onSurface.luminance() > 0.5f)
}

/** Rounded, so a card isn't redrawn for what it doesn't show. */
private fun CarData.State.faceCar() = FaceData.Car(
    kmh = speed?.value?.metersPerSecond?.let { abs(it * 3.6f).roundToInt().toFloat() },
    rpm = rpm?.value?.let { (it / 20).roundToInt() * 20f },
    fuel = fuel?.value?.levelPercent,
    rangeKm = fuel?.value?.rangeKm,
    outsideC = environment?.value?.temperatureC?.let { it.roundToInt().toFloat() },
    compass = compass?.value?.bearingDeg?.roundToInt()?.toFloat(),
)

/** [spec] with made-up weather, event, music and car values where needed, for the phone's previews. The time is real. */
@Composable
internal fun FacePreview(spec: FaceSpec, modifier: Modifier) {
    val now = rememberNow(FaceNeed.SECONDS in spec.needs)
    val data = clockData(now).copy(
        temperature = if (usesFahrenheit()) 70 else 21, temperatureUnit = if (usesFahrenheit()) "°F" else "°",
        event = stringResource(R.string.faces_sample_event), eventTime = DateFormat.getTimeFormat(LocalContext.current).format(now + 3_600_000),
        eventDay = stringResource(R.string.dash_event_today), eventPlace = "",
        track = stringResource(R.string.faces_sample_song), artist = stringResource(R.string.faces_sample_artist), playing = true, song = 0.4f,
        battery = 72, car = FaceData.Car(80f, 2200f, 64, 410, 19f, 45f),
    )
    FaceCanvas(spec, data, Weather(21, "°", 1, true), modifier)
}

// ---------------------------------------------------------------- drawing

/** What drawing needs ready made: decoded pictures and parsed paths, per layer. */
private class FaceAssets(val images: Map<FaceLayer.Image, ImageBitmap>, val paths: Map<FaceLayer.Path, Path>)

private const val MAX_IMAGE_PX = 1024

private fun decode(layer: FaceLayer.Image): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(layer.bytes, 0, layer.bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_PX) sample *= 2
    BitmapFactory.decodeByteArray(layer.bytes, 0, layer.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

private fun assets(spec: FaceSpec): FaceAssets {
    val layers = listOfNotNull(spec.base, spec.strip, spec.tall).flatMap { it.layers }
    return FaceAssets(
        layers.filterIsInstance<FaceLayer.Image>().mapNotNull { l -> decode(l)?.let { l to it } }.toMap(),
        // Not a path: nothing drawn, rather than a crash.
        layers.filterIsInstance<FaceLayer.Path>().mapNotNull { l -> runCatching { l to PathParser().parsePathString(l.data).toPath() }.getOrNull() }.toMap(),
    )
}

@Composable
internal fun FaceCanvas(spec: FaceSpec, data: FaceData, weather: Weather?, modifier: Modifier) {
    val assets = remember(spec) { assets(spec) }
    val icons = remember(spec) { listOfNotNull(spec.base, spec.strip, spec.tall).flatMap { it.layers }.filterIsInstance<FaceLayer.Icon>().map { it.name }.distinct() }
    val painters = icons.associateWith { key(it) { rememberVectorPainter(faceIcon(it, weather)) } }
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val scheme = MaterialTheme.colorScheme
    val themeFont = MaterialTheme.typography.displaySmall.fontFamily ?: FontFamily.Default
    val description = data.time(if (data.hours24) "H:mm" else "h:mm a", null)
    Canvas(modifier.clipToBounds().semantics { contentDescription = "${spec.name}, $description" }) {
        val layout = spec.layoutFor(size.width, size.height)
        FaceRenderer(this, layout, data, scheme, themeFont, measurer, assets, painters).draw()
    }
}

private fun faceIcon(name: String, weather: Weather?): ImageVector = when (name) {
    "weather" -> weather?.let(::weatherIcon) ?: Icons.Rounded.DeviceThermostat
    "calendar" -> Icons.Rounded.Event
    "music" -> Icons.Rounded.MusicNote
    "battery" -> Icons.Rounded.BatteryFull
    "bolt" -> Icons.Rounded.Bolt
    "place" -> Icons.Rounded.Place
    "car" -> Icons.Rounded.DirectionsCar
    "fuel" -> Icons.Rounded.LocalGasStation
    "thermostat" -> Icons.Rounded.DeviceThermostat
    "compass" -> Icons.Rounded.Explore
    "heart" -> Icons.Rounded.Favorite
    "star" -> Icons.Rounded.Star
    "home" -> Icons.Rounded.Home
    "person" -> Icons.Rounded.Person
    "notifications" -> Icons.Rounded.Notifications
    else -> Icons.Rounded.Schedule
}

private class FaceRenderer(
    private val scope: DrawScope,
    private val layout: FaceLayout,
    private val data: FaceData,
    private val scheme: ColorScheme,
    private val themeFont: FontFamily,
    private val measurer: TextMeasurer,
    private val assets: FaceAssets,
    private val painters: Map<String, Painter>,
) {
    /** The layout is scaled whole, as big as it fits, and centred; only its background fills the card. */
    private val s = min(scope.size.width / layout.width, scope.size.height / layout.height)
    private val left = (scope.size.width - layout.width * s) / 2
    private val top = (scope.size.height - layout.height * s) / 2

    private fun px(x: Float) = left + x * s
    private fun py(y: Float) = top + y * s
    /** A point at [angle] (0 at the top, clockwise) and [r] from a centre already in pixels. */
    private fun polar(cx: Float, cy: Float, r: Float, angle: Float): Offset {
        val a = angle * PI / 180
        return Offset(cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat())
    }

    fun draw() {
        if (s <= 0f || !s.isFinite()) return
        layout.background?.let { scope.drawRect(brush(it, Rect(Offset.Zero, scope.size))) }
        for (layer in layout.layers) {
            if (!data.shows(layer)) continue
            val c = layer.common
            val angle = c.rotate?.let { it.at(data) ?: continue } ?: 0f
            if (angle == 0f) draw(layer) else scope.rotate(angle, Offset(px(c.pivotX), py(c.pivotY))) { draw(layer) }
        }
    }

    private fun draw(layer: FaceLayer) {
        val c = layer.common
        val x = px(c.x)
        val y = py(c.y)
        when (layer) {
            is FaceLayer.Text -> text(layer)
            is FaceLayer.Circle -> {
                val r = layer.r * s
                scope.drawCircle(brush(c.paint, Rect(Offset(x, y), r)), r, Offset(x, y), c.alpha, style(layer.stroke))
            }
            is FaceLayer.Rect -> {
                val size = Size(layer.w * s, layer.h * s)
                val at = Offset(x - size.width / 2, y - size.height / 2)
                scope.drawRoundRect(brush(c.paint, Rect(at, size)), at, size, CornerRadius(layer.corner * s), c.alpha, style(layer.stroke))
            }
            is FaceLayer.Line -> {
                val end = Offset(px(layer.x2), py(layer.y2))
                scope.drawLine(brush(c.paint, Rect(min(x, end.x), min(y, end.y), maxOf(x, end.x), maxOf(y, end.y))), Offset(x, y), end,
                    layer.stroke * s, cap(layer.round), alpha = c.alpha)
            }
            is FaceLayer.Arc -> arc(layer, x, y)
            is FaceLayer.Ticks -> {
                val brush = brush(c.paint, Rect(Offset(x, y), layer.r * s))
                around(layer.count, layer.start, layer.sweep) { i, angle ->
                    if (layer.skip > 0 && i % layer.skip == 0) return@around
                    if (layer.dots) scope.drawCircle(brush, layer.stroke * s / 2, polar(x, y, layer.r * s, angle), c.alpha)
                    else scope.drawLine(brush, polar(x, y, layer.r * s, angle), polar(x, y, (layer.r - layer.length) * s, angle), layer.stroke * s, cap(layer.round), alpha = c.alpha)
                }
            }
            is FaceLayer.Numbers -> {
                val style = TextStyle(brush = brush(c.paint, Rect(Offset(x, y), layer.r * s)), alpha = c.alpha, fontSize = sp(layer.size),
                    fontFamily = family(layer.font), fontWeight = FontWeight(layer.weight), fontFeatureSettings = "tnum")
                around(layer.values.size, layer.start, layer.sweep) { i, angle ->
                    if (layer.values[i].isEmpty()) return@around
                    val at = polar(x, y, layer.r * s, angle)
                    val m = measurer.measure(layer.values[i], style, softWrap = false, maxLines = 1)
                    val corner = Offset(at.x - m.size.width / 2f, at.y - m.size.height / 2f)
                    if (layer.upright) scope.drawText(m, topLeft = corner) else scope.rotate(angle, at) { drawText(m, topLeft = corner) }
                }
            }
            is FaceLayer.Hand -> hand(layer, x, y)
            is FaceLayer.Icon -> {
                val size = Size(layer.size * s, layer.size * s)
                val painter = painters[layer.name] ?: return
                scope.translate(x - size.width / 2, y - size.height / 2) { with(painter) { draw(size, c.alpha, ColorFilter.tint(flat(c.paint))) } }
            }
            is FaceLayer.Image -> {
                val image = assets.images[layer] ?: return
                val w = (layer.w * s).roundToInt()
                val h = (layer.h * s).roundToInt()
                scope.drawImage(image, dstOffset = IntOffset((x - w / 2f).roundToInt(), (y - h / 2f).roundToInt()), dstSize = IntSize(w, h),
                    alpha = c.alpha, colorFilter = if (layer.tint) ColorFilter.tint(flat(c.paint)) else null)
            }
            is FaceLayer.Path -> {
                val path = Path().apply {
                    addPath(assets.paths[layer] ?: return)
                    transform(Matrix().apply { translate(x, y); scale(s * layer.scale, s * layer.scale) })
                }
                scope.drawPath(path, brush(c.paint, path.getBounds()), c.alpha, style(layer.stroke, layer.round))
            }
        }
    }

    /** [count] places over [sweep] degrees from [start]: a full circle doesn't repeat the first at its end. */
    private inline fun around(count: Int, start: Float, sweep: Float, at: (Int, Float) -> Unit) {
        val step = if (abs(sweep) >= 360f || count < 2) sweep / count else sweep / (count - 1)
        for (i in 0 until count) at(i, start + step * i)
    }

    private fun arc(layer: FaceLayer.Arc, x: Float, y: Float) {
        val r = layer.r * s
        val box = Rect(Offset(x, y), r)
        val stroke = Stroke(layer.stroke * s, cap = cap(layer.round))
        // Canvas angles start at 3 o'clock.
        layer.track?.let { scope.drawArc(color(it), layer.start - 90, layer.sweep, false, box.topLeft, box.size, layer.common.alpha, stroke) }
        val f = if (layer.value == null) 1f else (layer.value.at(data) ?: return).coerceIn(0f, 1f)
        if (f > 0f) scope.drawArc(brush(layer.common.paint, box), layer.start - 90, layer.sweep * f, false, box.topLeft, box.size, layer.common.alpha, stroke)
    }

    private fun hand(layer: FaceLayer.Hand, x: Float, y: Float) {
        val angle = layer.value.at(data) ?: return
        val length = layer.length * s
        val tail = layer.tail * s
        val w = layer.stroke * s
        val brush = brush(layer.common.paint, Rect(x - w / 2, y - length, x + w / 2, y + tail))
        scope.rotate(angle, Offset(x, y)) {
            when (layer.shape) {
                FaceLayer.Hand.Shape.ROUND, FaceLayer.Hand.Shape.FLAT ->
                    drawLine(brush, Offset(x, y + tail), Offset(x, y - length), w, cap(layer.shape == FaceLayer.Hand.Shape.ROUND), alpha = layer.common.alpha)
                FaceLayer.Hand.Shape.TAPER -> drawPath(Path().apply {
                    moveTo(x - w / 2, y + tail); lineTo(x + w / 2, y + tail); lineTo(x + w / 8, y - length); lineTo(x - w / 8, y - length); close()
                }, brush, layer.common.alpha)
                FaceLayer.Hand.Shape.LEAF -> drawPath(Path().apply {
                    moveTo(x, y + tail)
                    quadraticTo(x - w, y - length * 0.35f, x, y - length)
                    quadraticTo(x + w, y - length * 0.35f, x, y + tail)
                    close()
                }, brush, layer.common.alpha)
            }
        }
    }

    private fun text(layer: FaceLayer.Text) {
        val c = layer.common
        val value = layer.template.text(data, layer.timeZone).let { if (layer.upper) it.uppercase(data.locale) else it }
            // The segment font only has these digits, not a language's own (Arabic's).
            .let { if (layer.font == FaceLayer.Font.SEGMENT) it.map { c -> if (c.isDigit()) '0' + Character.digit(c, 10) else c }.joinToString("") else it }
        if (value.isBlank()) return
        val align = when (layer.align) { FaceLayer.Align.START -> TextAlign.Left; FaceLayer.Align.CENTER -> TextAlign.Center; FaceLayer.Align.END -> TextAlign.Right }
        val lines = maxOf(layer.lines, value.count { it == '\n' } + 1)
        val limit = layer.width?.let { ceil(it * s).toInt().coerceAtLeast(1) }
        fun measure(size: Float): TextLayoutResult {
            val height = layer.size * s
            val around = Rect(px(c.x) - (limit ?: 0) / 2f, py(c.y) - height / 2, px(c.x) + (limit ?: 0) / 2f, py(c.y) + height / 2)
            val style = TextStyle(
                brush = brush(c.paint, Rect(0f, 0f, around.width.coerceAtLeast(1f), height)), alpha = c.alpha,
                fontSize = sp(size), fontFamily = family(layer.font), fontWeight = FontWeight(layer.weight),
                fontStyle = if (layer.italic) FontStyle.Italic else FontStyle.Normal, letterSpacing = (layer.letterSpacing / layer.size).em,
                lineHeight = layer.lineHeight?.em ?: TextUnit.Unspecified, textAlign = align,
                // Digits as wide as each other: the time doesn't shift as it ticks.
                fontFeatureSettings = "tnum",
                shadow = layer.glow?.let { Shadow(color(it), Offset.Zero, (layer.glowRadius * s).coerceAtLeast(0.1f)) },
            )
            return measurer.measure(value, style, TextOverflow.Ellipsis, softWrap = limit != null && layer.lines > 1, maxLines = lines,
                constraints = if (limit != null && (layer.lines > 1 || size < layer.size)) Constraints(maxWidth = limit) else Constraints())
        }
        var m = measure(layer.size)
        // One line wider than its room: smaller, down to half, then cut.
        if (limit != null && layer.lines == 1 && m.size.width > limit) m = measure(layer.size * (limit.toFloat() / m.size.width * 0.98f).coerceIn(0.5f, 0.98f))
        val x = px(c.x) - when (layer.align) { FaceLayer.Align.START -> 0f; FaceLayer.Align.CENTER -> m.size.width / 2f; FaceLayer.Align.END -> m.size.width.toFloat() }
        val y = py(c.y) - when (layer.valign) {
            FaceLayer.VAlign.TOP -> 0f
            FaceLayer.VAlign.MIDDLE -> m.size.height / 2f
            FaceLayer.VAlign.BOTTOM -> m.size.height.toFloat()
            FaceLayer.VAlign.BASELINE -> m.firstBaseline
        }
        scope.drawText(m, topLeft = Offset(x, y))
    }

    // ------------------------------------------------------------ paint

    /** A size in the layout's units as text: not affected by the text size setting, like the rest of the drawing. */
    private fun sp(units: Float): TextUnit = with(scope) { (units * s).coerceAtLeast(1f).toSp() }

    private fun cap(round: Boolean) = if (round) StrokeCap.Round else StrokeCap.Butt

    private fun style(stroke: Float, round: Boolean = false) =
        if (stroke <= 0f) Fill else Stroke(stroke * s, cap = cap(round), join = if (round) StrokeJoin.Round else StrokeJoin.Miter)

    private fun family(f: FaceLayer.Font): FontFamily = when (f) {
        FaceLayer.Font.DEFAULT -> themeFont
        FaceLayer.Font.SANS -> FontFamily.SansSerif
        FaceLayer.Font.CONDENSED -> CONDENSED
        FaceLayer.Font.SERIF -> FontFamily.Serif
        FaceLayer.Font.MONO -> FontFamily.Monospace
        FaceLayer.Font.CURSIVE -> FontFamily.Cursive
        FaceLayer.Font.SEGMENT -> SegmentFont
    }

    private fun flat(p: FacePaint): Color = when (p) {
        is FaceColor -> color(p)
        is FaceGradient -> color(p.colors.first())
    }

    private fun brush(p: FacePaint, box: Rect): Brush = when (p) {
        is FaceColor -> SolidColor(color(p))
        is FaceGradient -> {
            val colors = p.colors.map(::color)
            when (p.kind) {
                FaceGradient.Kind.RADIAL -> Brush.radialGradient(colors, box.center, (maxOf(box.width, box.height) / 2).coerceAtLeast(1f))
                // From the top, like every other angle here; the platform's starts at 3 o'clock.
                FaceGradient.Kind.SWEEP -> object : ShaderBrush() {
                    override fun createShader(size: Size) = SweepGradientShader(box.center, colors + colors.first()).apply {
                        setLocalMatrix(android.graphics.Matrix().apply { setRotate(p.angle - 90, box.center.x, box.center.y) })
                    }
                }
                FaceGradient.Kind.LINEAR -> {
                    // Like CSS: 180 runs from top to bottom, 90 from left to right, corner to corner.
                    val a = p.angle * PI / 180
                    val dx = sin(a).toFloat()
                    val dy = -cos(a).toFloat()
                    val half = (abs(box.width * dx) + abs(box.height * dy)) / 2
                    Brush.linearGradient(colors, Offset(box.center.x - dx * half, box.center.y - dy * half), Offset(box.center.x + dx * half, box.center.y + dy * half))
                }
            }
        }
    }

    private fun color(c: FaceColor): Color = when (c) {
        is FaceColor.Fixed -> Color(c.argb)
        is FaceColor.DayNight -> color(if (data.night) c.night else c.day)
        is FaceColor.Theme -> when (c.name) {
            "primary" -> scheme.primary
            "onPrimary" -> scheme.onPrimary
            "primaryContainer" -> scheme.primaryContainer
            "onPrimaryContainer" -> scheme.onPrimaryContainer
            "secondary" -> scheme.secondary
            "secondaryContainer" -> scheme.secondaryContainer
            "onSecondaryContainer" -> scheme.onSecondaryContainer
            "tertiary" -> scheme.tertiary
            "tertiaryContainer" -> scheme.tertiaryContainer
            "onTertiaryContainer" -> scheme.onTertiaryContainer
            "surface" -> scheme.surface
            "onSurfaceVariant" -> scheme.onSurfaceVariant
            "surfaceContainer" -> scheme.surfaceContainer
            "surfaceContainerHigh" -> scheme.surfaceContainerHigh
            "surfaceContainerHighest" -> scheme.surfaceContainerHighest
            "outline" -> scheme.outline
            "outlineVariant" -> scheme.outlineVariant
            "error" -> scheme.error
            else -> scheme.onSurface
        }.let { if (c.alpha < 1f) it.copy(alpha = it.alpha * c.alpha) else it }
    }

    private companion object {
        val CONDENSED = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    }
}
