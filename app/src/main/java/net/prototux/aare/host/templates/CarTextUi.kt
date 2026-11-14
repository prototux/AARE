package net.prototux.aare.host.templates

import android.os.SystemClock
import android.text.Spanned
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIconSpan
import androidx.car.app.model.CarSpan
import androidx.car.app.model.CarText
import androidx.car.app.model.ClickableSpan
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.OnClickDelegate
import androidx.car.app.model.TimerSpan
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.prototux.aare.ui.theme.CarColors
import net.prototux.aare.ui.theme.LocalCarColors

// ---------------------------------------------------------------- colours

/** True when the current theme is the night (dark) one: picks CarColor dark variants. */
internal val ColorScheme.isNight: Boolean get() = background.luminance() < 0.5f

/**
 * Maps a CarColor to the theme. PRIMARY/SECONDARY are the app's own accent colours, from its car
 * theme ([app]), in their day or night variant; where the app doesn't set them, the theme's accent
 * and second accent. DEFAULT, null or unknown types give [default].
 */
internal fun CarColor?.resolve(scheme: ColorScheme, colors: CarColors, default: Color, app: AppThemeColors = AppThemeColors.NONE): Color {
    if (this == null) return default
    val night = scheme.isNight
    return when (type) {
        CarColor.TYPE_PRIMARY -> app.primary?.let { Color(it.pick(night)) } ?: scheme.primary
        CarColor.TYPE_SECONDARY -> app.secondary?.let { Color(it.pick(night)) } ?: scheme.tertiary
        CarColor.TYPE_RED -> colors.red
        CarColor.TYPE_GREEN -> colors.green
        CarColor.TYPE_BLUE -> colors.blue
        CarColor.TYPE_YELLOW -> colors.yellow
        CarColor.TYPE_CUSTOM -> Color(if (night) colorDark else color)
        else -> default
    }
}

/** [CarColor.resolve] against the current MaterialTheme. */
@Composable
@ReadOnlyComposable
internal fun carColor(color: CarColor?, default: Color): Color =
    color.resolve(MaterialTheme.colorScheme, LocalCarColors.current, default, LocalTemplateHost.current.appColors)

/** A CarColor that asks for something other than the default look. */
internal fun CarColor?.isSet(): Boolean = this != null && type != CarColor.TYPE_DEFAULT

/** Readable content colour on top of an arbitrary [background] (app-supplied colours). */
@Composable
@ReadOnlyComposable
internal fun contentColorOn(background: Color): Color =
    if (background.luminance() > 0.45f) LocalCarColors.current.inkOnLight else Color.White

// ---------------------------------------------------------------- text

/** Plain string of a CarText (spans dropped), for content descriptions and text fields. */
internal fun CarText?.plain(): String = this?.toString().orEmpty()

/**
 * Renders a CarText: picks the first variant (the main text, then the app's shorter variants)
 * that fits in [maxLines] at the available width, and renders the car spans: colours, distances,
 * durations, timers, inline icons and clickable ranges.
 *
 * Note: uses BoxWithConstraints when the text has variants, so it must not sit under an
 * intrinsic-size measurement.
 */
@Composable
internal fun CarTextView(
    text: CarText?,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    if (text == null || text.isEmpty) return
    // A variant carrying an action's countdown (see timedActionParamsMillis) is no text to show.
    val candidates: List<CharSequence> = remember(text) { listOf(text.toCharSequence()) + text.variants.filterNot(::isTimedActionParams) }
    if (candidates.size == 1) {
        StyledText(candidates[0], modifier, style, color, maxLines, textAlign)
        return
    }
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxWidth = constraints.maxWidth
        val chosen = remember(text, maxWidth, style, maxLines) {
            if (maxWidth == Constraints.Infinity) return@remember 0
            candidates.indexOfFirst { cs ->
                !measurer.measure(
                    AnnotatedString(cs.toString()),
                    style = style,
                    maxLines = maxLines,
                    constraints = Constraints(maxWidth = maxWidth),
                ).hasVisualOverflow
            }.let { if (it < 0) candidates.lastIndex else it }
        }
        StyledText(candidates[chosen], Modifier, style, color, maxLines, textAlign)
    }
}

@Composable
private fun StyledText(
    cs: CharSequence,
    modifier: Modifier,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    textAlign: TextAlign?,
) {
    val host = LocalTemplateHost.current
    val scheme = MaterialTheme.colorScheme
    val content = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val hasTimer = remember(cs) { cs is Spanned && cs.getSpans(0, cs.length, TimerSpan::class.java).isNotEmpty() }
    val now = elapsedTicker(hasTimer)
    val colors = LocalCarColors.current
    val app = host.appColors
    val styled = remember(cs, scheme, colors, app, content, now) {
        styleCarText(cs, scheme, colors, app, content, now) { host.click(it) }
    }
    val iconSize = with(LocalDensity.current) { (if (style.fontSize.isSpecified) style.fontSize else 24.sp).toDp() * 1.15f }
    val inline = styled.icons.associate { (id, span) ->
        val align = when (span.alignment) {
            CarIconSpan.ALIGN_BOTTOM -> PlaceholderVerticalAlign.TextBottom
            CarIconSpan.ALIGN_BASELINE -> PlaceholderVerticalAlign.AboveBaseline
            else -> PlaceholderVerticalAlign.TextCenter
        }
        id to InlineTextContent(Placeholder(1.25.em, 1.25.em, align)) {
            CarIconView(span.icon, iconSize, monochromeTint = content)
        }
    }
    Text(
        styled.text,
        modifier = modifier,
        color = content,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign ?: TextAlign.Unspecified,
        inlineContent = inline,
    )
}

/** SystemClock.elapsedRealtime, ticking every second only when [enabled] (TimerSpan). */
@Composable
private fun elapsedTicker(enabled: Boolean): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    if (enabled) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000 - now % 1_000)
                now = SystemClock.elapsedRealtime()
            }
        }
    }
    return now
}

private class StyledCarText(val text: AnnotatedString, val icons: List<Pair<String, CarIconSpan>>)

/**
 * Converts a spanned CharSequence from CarText into an AnnotatedString. Distance, duration,
 * timer and icon spans *replace* the characters they cover (that is their contract); colour and
 * clickable spans style their range, mapped through those replacements.
 */
private fun styleCarText(
    cs: CharSequence,
    scheme: ColorScheme,
    colors: CarColors,
    app: AppThemeColors,
    default: Color,
    nowElapsed: Long,
    onClick: (OnClickDelegate) -> Unit,
): StyledCarText {
    val src = cs.toString()
    if (cs !is Spanned) return StyledCarText(AnnotatedString(src), emptyList())
    val spans = cs.getSpans(0, cs.length, CarSpan::class.java)
    if (spans.isEmpty()) return StyledCarText(AnnotatedString(src), emptyList())

    class Range(val start: Int, val end: Int, val span: CarSpan)
    val replacements = spans
        .filter { it is DistanceSpan || it is DurationSpan || it is TimerSpan || it is CarIconSpan }
        .map { Range(cs.getSpanStart(it), cs.getSpanEnd(it), it) }
        .filter { it.start >= 0 && it.end <= src.length && it.start <= it.end }
        .sortedBy { it.start }

    val b = AnnotatedString.Builder()
    val icons = mutableListOf<Pair<String, CarIconSpan>>()
    // outIndex[i] = position in the output of source character i (and of the end, at src.length)
    val outIndex = IntArray(src.length + 1)
    var pos = 0
    fun copyUntil(end: Int) {
        while (pos < end) {
            outIndex[pos] = b.length
            b.append(src[pos])
            pos++
        }
    }
    for (r in replacements) {
        if (r.start < pos) continue // overlapping replacement: keep the first
        copyUntil(r.start)
        for (i in r.start until r.end) outIndex[i] = b.length
        when (val s = r.span) {
            is DistanceSpan -> {
                val d = s.distance
                b.append(CarFormat.distance(d.displayDistance, d.displayUnit))
            }
            is DurationSpan -> b.append(CarFormat.duration(s.durationSeconds).orEmpty())
            is TimerSpan -> b.append(CarFormat.timer(nowElapsed - s.elapsedRealtimeMillis))
            is CarIconSpan -> {
                val id = "caricon${icons.size}"
                icons += id to s
                b.appendInlineContent(id, src.substring(r.start, r.end).ifEmpty { " " })
            }
        }
        pos = r.end
    }
    copyUntil(src.length)
    outIndex[src.length] = b.length

    for ((i, s) in spans.withIndex()) {
        val st = cs.getSpanStart(s)
        val en = cs.getSpanEnd(s)
        if (st < 0 || en > src.length || st >= en) continue
        val a = outIndex[st]
        val e = outIndex[en]
        if (a >= e) continue
        when (s) {
            is ForegroundCarColorSpan -> b.addStyle(SpanStyle(color = s.color.resolve(scheme, colors, default, app)), a, e)
            is ClickableSpan -> {
                val delegate = s.onClickDelegate
                b.addLink(
                    LinkAnnotation.Clickable(
                        tag = "click$i",
                        styles = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { onClick(delegate) },
                    ),
                    a, e,
                )
            }
        }
    }
    return StyledCarText(b.toAnnotatedString(), icons)
}
