package net.prototux.aare.ui.car

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.R
import net.prototux.aare.car.calibration.CalState
import net.prototux.aare.car.calibration.CalibrationHub
import kotlin.math.min
import kotlin.math.roundToInt

private val Mark = Color(0xFFFFC107)
private val Ok = Color(0xFF4CAF50)
private val Line = Color(0x99FFFFFF)

/**
 * The calibration tool on the car screen, over the whole video frame (margins included). Nothing
 * here is touchable on purpose: the phone drives the tool, the car screen only shows where to tap.
 */
@Composable
fun CalibrationOverlay(modifier: Modifier = Modifier) {
    val state by CalibrationHub.state.collectAsState()
    val s = state
    if (s !is CalState.Area && s !is CalState.Target && s !is CalState.Verify) return
    BoxWithConstraints(modifier.background(Color.Black)) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val short = min(w, h)
        when (s) {
            is CalState.Area -> {
                // Fractions of the frame, so this holds whatever size the display composes at.
                val l = s.margins[0] * w / s.width; val t = s.margins[1] * h / s.height
                val r = w - s.margins[2] * w / s.width; val b = h - s.margins[3] * h / s.height
                Canvas(Modifier.fillMaxSize()) { area(l, t, r, b, short * 0.12f, short * 0.012f) }
                Message(stringResource(R.string.calibration_car_area), Modifier.align(Alignment.Center))
            }
            is CalState.Target -> {
                val c = Offset((s.point.first * w).toFloat(), (s.point.second * h).toFloat())
                Canvas(Modifier.fillMaxSize()) { target(c, short * 0.06f, Mark) }
                Number("${s.index + 1}", c, short * 0.06f)
                val text = if (s.retry) stringResource(R.string.calibration_car_target_retry, s.index + 1)
                    else stringResource(R.string.calibration_car_target, s.index + 1)
                Message(text, Modifier.align(Alignment.Center))
            }
            is CalState.Verify -> {
                val c = Offset((s.point.first * w).toFloat(), (s.point.second * h).toFloat())
                Canvas(Modifier.fillMaxSize()) { target(c, short * 0.06f, Ok) }
                // Above the centre target, not on it.
                Message(stringResource(R.string.calibration_car_verify, s.secondsLeft),
                    Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (c.y - short * 0.25f).roundToInt().coerceAtLeast(0)) })
            }
            else -> Unit
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    Text(text, modifier.fillMaxWidth(0.6f).padding(16.dp), color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
}

@Composable
private fun Number(n: String, centre: Offset, radius: Float) {
    val d = LocalDensity.current
    Box(
        Modifier.offset { IntOffset((centre.x - radius).roundToInt(), (centre.y - radius).roundToInt()) }
            .size(with(d) { (radius * 2).toDp() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(n, color = Color.Black, fontSize = with(d) { (radius * 0.9f).toSp() }, fontWeight = FontWeight.Bold)
    }
}

/** The visible area's outline, with an L in each corner to line up with the screen's corners. */
private fun DrawScope.area(l: Float, t: Float, r: Float, b: Float, arm: Float, stroke: Float) {
    drawRect(Line, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), style = Stroke(stroke / 3))
    val half = stroke / 2
    for ((x, y, dx, dy) in listOf(
        listOf(l, t, 1f, 1f), listOf(r, t, -1f, 1f), listOf(r, b, -1f, -1f), listOf(l, b, 1f, -1f),
    )) {
        // Drawn inside the area, so a mark that is cut off means that edge is too far out.
        drawLine(Mark, Offset(x + dx * half, y + dy * half), Offset(x + dx * arm, y + dy * half), stroke)
        drawLine(Mark, Offset(x + dx * half, y + dy * half), Offset(x + dx * half, y + dy * arm), stroke)
    }
}

/** A filled disc with a cross through its centre: tap the cross. */
private fun DrawScope.target(c: Offset, radius: Float, color: Color) {
    drawCircle(color, radius, c)
    drawCircle(Color.White, radius * 1.6f, c, style = Stroke(radius * 0.08f))
    val arm = radius * 2.2f
    drawLine(Color.White, Offset(c.x - arm, c.y), Offset(c.x - radius * 1.1f, c.y), radius * 0.06f)
    drawLine(Color.White, Offset(c.x + radius * 1.1f, c.y), Offset(c.x + arm, c.y), radius * 0.06f)
    drawLine(Color.White, Offset(c.x, c.y - arm), Offset(c.x, c.y - radius * 1.1f), radius * 0.06f)
    drawLine(Color.White, Offset(c.x, c.y + radius * 1.1f), Offset(c.x, c.y + arm), radius * 0.06f)
}
