package net.prototux.aare.ui.car.obd

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import net.prototux.aare.ui.theme.LocalCarColors
import net.prototux.aare.ui.theme.LocalCarTheme
import net.prototux.aare.ui.theme.SegmentFont
import kotlin.math.cos
import kotlin.math.sin

/** Red for the red zone, alerts and the shift light: the car theme's own red. */
internal val ObdRed: Color @Composable get() = LocalCarColors.current.red

/**
 * A tile, chip or panel in the car theme's style: its [shape] (the theme's corners), its [color],
 * and the theme's outline when it draws cards as outlines.
 */
@Composable
internal fun Modifier.themed(shape: Shape, color: Color): Modifier {
    val border = LocalCarTheme.current.cardBorder
    return clip(shape).background(color).let { if (border > 0.dp) it.border(border, MaterialTheme.colorScheme.outline, shape) else it }
}

/** The font for a big number: the seven-segment one on themes with a digital-dash clock, else the theme's. */
internal val numberFont: FontFamily? @Composable get() = if (LocalCarTheme.current.segmentClock) SegmentFont else null

/**
 * How much bigger or smaller than the reference screen (about 1000 × 600 dp of app area) this
 * one is. Every page fills its area with weights and nothing scrolls, so text and gaps scale with
 * this to fit any head unit: small 800×480, wide 1920×720, tall portrait ones.
 */
internal val LocalObdScale = compositionLocalOf { 1f }

/** dp scaled to this screen. */
@Composable internal fun sdp(v: Float): Dp = (v * LocalObdScale.current).dp

/** A text size scaled to this screen (in dp, so the phone's font size setting doesn't break the layout). */
@Composable internal fun ssp(v: Float): TextUnit = with(LocalDensity.current) { maxOf(v * LocalObdScale.current, minOf(v, MIN_TEXT)).dp.toSp() }

/** No text smaller than this (dp): small screens get fewer words (ellipsis), not unreadable ones. */
private const val MIN_TEXT = 13f

/** A text size from a measured size (a fraction of a tile's height, say). */
@Composable internal fun dpText(d: Dp): TextUnit = with(LocalDensity.current) { d.toSp() }

/**
 * A round gauge: a 240° arc from [min] to [max], a red zone from [redFrom], a needle, and the value
 * in the arc's open bottom. Square, as big as the space it's given; its text follows its size.
 */
@Composable
internal fun Gauge(
    value: Float?,
    min: Float,
    max: Float,
    redFrom: Float?,
    text: String,
    label: String,
    modifier: Modifier = Modifier,
    peak: Float? = null,
    subtext: String? = null,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    val needle = MaterialTheme.colorScheme.onSurface
    val red = ObdRed
    val hot = value != null && redFrom != null && value >= redFrom
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = maxHeight < maxWidth), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.minDimension
                val stroke = w * 0.07f
                val topLeft = Offset((size.width - w) / 2 + stroke / 2, (size.height - w) / 2 + stroke / 2)
                val arc = Size(w - stroke, w - stroke)
                fun angle(v: Float) = 150f + 240f * ((v - min) / (max - min)).coerceIn(0f, 1f)
                drawArc(track, 150f, 240f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                if (redFrom != null && redFrom < max) {
                    val a = angle(redFrom)
                    drawArc(red.copy(alpha = 0.55f), a, 390f - a, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                }
                val c = Offset(size.width / 2, size.height / 2)
                if (value != null) {
                    val zero = angle(if (min < 0f && max > 0f) 0f else min)
                    val a = angle(value)
                    drawArc(if (hot) red else fill, minOf(zero, a), kotlin.math.abs(a - zero), false, topLeft, arc, style = Stroke(stroke * 0.55f, cap = StrokeCap.Round))
                    val r = (w - stroke) / 2 * 0.72f
                    val rad = Math.toRadians(a.toDouble())
                    drawLine(needle, c, Offset(c.x + (r * cos(rad)).toFloat(), c.y + (r * sin(rad)).toFloat()), strokeWidth = w * 0.025f, cap = StrokeCap.Round)
                    drawCircle(needle, w * 0.035f, c)
                }
                if (peak != null) {
                    val rad = Math.toRadians(angle(peak).toDouble())
                    val r1 = (w - stroke) / 2 - stroke; val r2 = (w - stroke) / 2 + stroke / 2
                    drawLine(red, Offset(c.x + (r1 * cos(rad)).toFloat(), c.y + (r1 * sin(rad)).toFloat()),
                        Offset(c.x + (r2 * cos(rad)).toFloat(), c.y + (r2 * sin(rad)).toFloat()), strokeWidth = w * 0.02f)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text, fontSize = dpText(side * 0.12f), fontWeight = FontWeight.SemiBold, maxLines = 1,
                    color = if (hot) ObdRed else MaterialTheme.colorScheme.onSurface)
                Text(label, fontSize = dpText(side * 0.06f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (subtext != null) Text(subtext, fontSize = dpText(side * 0.05f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/**
 * A tile with a label and a number, filling the space it's given: the number's size follows the
 * tile's height and width, so it fits whatever the screen.
 */
@Composable
internal fun Stat(label: String, value: String, modifier: Modifier = Modifier, unit: String = "", alert: Boolean = false, onClick: (() -> Unit)? = null, footer: String? = null) {
    BoxWithConstraints(
        modifier.themed(MaterialTheme.shapes.small, MaterialTheme.colorScheme.surfaceContainerHigh)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = sdp(14f), vertical = sdp(6f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Label + number (+ footer) stacked: the number takes what's left of the height, and must fit the width.
        val small = minOf(maxHeight * (if (footer != null) 0.17f else 0.2f), sdp(20f)).coerceAtLeast(sdp(10f))
        val left = maxHeight - small * (if (footer != null) 2.6f else 1.35f)
        val big = minOf(left * 0.8f, maxWidth / (value.length * 0.6f + unit.length * 0.3f + 0.5f), sdp(64f)).coerceAtLeast(small)
        Column(verticalArrangement = Arrangement.Center) {
            Text(label, fontSize = dpText(small), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = dpText(big), fontWeight = FontWeight.SemiBold, maxLines = 1,
                    color = when { alert -> ObdRed; value == DASH -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.onSurface })
                if (unit.isNotEmpty() && value != DASH) Text(" $unit", fontSize = dpText(small), maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = big * 0.12f))
            }
            if (footer != null) Text(footer, fontSize = dpText(small * 0.9f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Puts [items] in a grid of [columns] that fills the space: rows share the height, a short last row keeps the tile width. */
@Composable
internal fun TileGrid(items: List<@Composable (Modifier) -> Unit>, columns: Int, modifier: Modifier = Modifier) {
    val gap = sdp(10f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** A tab, a choice or a small button: a rounded chip whose size follows the screen. */
@Composable
internal fun Chip(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.themed(MaterialTheme.shapes.large, if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = sdp(12f), vertical = sdp(8f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = ssp(18f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

/** A setting with − and + buttons (and a bigger step on the outer ones when [bigStep] is set). */
@Composable
internal fun Stepper(title: String, value: Int, unit: String, step: Int, range: IntRange, bigStep: Int = 0, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = ssp(17f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = sdp(6f)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sdp(6f))) {
            if (bigStep > 0) Chip("−$bigStep") { onChange((value - bigStep).coerceIn(range)) }
            Chip("  −  ") { onChange((value - step).coerceIn(range)) }
            Text("$value $unit", fontSize = ssp(20f), maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = sdp(6f)))
            Chip("  +  ") { onChange((value + step).coerceIn(range)) }
            if (bigStep > 0) Chip("+$bigStep") { onChange((value + bigStep).coerceIn(range)) }
        }
    }
}

/** A wide button. */
@Composable
internal fun ObdButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.themed(MaterialTheme.shapes.large, if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = sdp(20f), vertical = sdp(10f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = ssp(18f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
    }
}

/** Small explanatory text. */
@Composable
internal fun Note(text: String, modifier: Modifier = Modifier, maxLines: Int = 2, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, fontSize = ssp(14f), color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

internal const val DASH = "--"

internal fun fmt(v: Float?, pattern: String) = v?.let { pattern.format(it) } ?: DASH
