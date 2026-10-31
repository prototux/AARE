package net.prototux.aare.host.templates

import android.content.Context
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarText
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBoat
import androidx.compose.material.icons.rounded.ForkLeft
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RampLeft
import androidx.compose.material.icons.rounded.RampRight
import androidx.compose.material.icons.rounded.RoundaboutLeft
import androidx.compose.material.icons.rounded.RoundaboutRight
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material.icons.rounded.UTurnRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.R
import net.prototux.aare.ui.theme.LocalCarDimens
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone

/**
 * NavigationTemplate: the app's map, the turn card (maneuver, distance, cue, lanes, next step)
 * or a message card top start, the trip estimate bottom start, and the action strips.
 */
@Composable
internal fun NavigationTemplateView(t: NavigationTemplate) {
    MapScaffold(t.actionStrip, t.mapActionStrip, t.panModeDelegate) {
        val info = t.navigationInfo
        val estimate = t.destinationTravelEstimate
        val width = if (narrow) maxWidth else panelWidth()
        val top = Modifier.align(Alignment.TopStart).width(width)
        when (info) {
            is RoutingInfo -> RoutingCard(info, t.backgroundColor, top.obstructsMap())
            is MessageInfo -> MessageInfoCard(info, t.backgroundColor, top.obstructsMap())
        }
        if (estimate != null) {
            // narrow screens: leave room for the map action strip at the bottom end
            val w = if (narrow) maxWidth - 96.dp else width
            TravelEstimateCard(estimate, Modifier.align(Alignment.BottomStart).width(w).obstructsMap())
        }
    }
}

/** Colours of the turn card: the app's background colour, or our accent container. */
@Composable
private fun turnCardColors(background: CarColor?): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return if (background.isSet()) {
        val bg = carColor(background, scheme.primaryContainer)
        bg to contentColorOn(bg)
    } else {
        scheme.primaryContainer to scheme.onPrimaryContainer
    }
}

@Composable
private fun RoutingCard(info: RoutingInfo, background: CarColor?, modifier: Modifier) {
    val (bg, fg) = turnCardColors(background)
    val r = LocalCarDimens.current.cardRadius
    Column(modifier.shadow(8.dp, RoundedCornerShape(r)).clip(RoundedCornerShape(r)).background(bg)) {
        val step = info.currentStep
        if (info.isLoading || step == null) {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(48.dp), color = fg, strokeWidth = 5.dp)
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ManeuverIcon(step.maneuver, 76.dp, fg)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                info.currentDistance?.let { d ->
                    Text(
                        CarFormat.distance(d.displayDistance, d.displayUnit),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 42.sp),
                        color = fg,
                        maxLines = 1,
                    )
                }
                val cue = if (!CarText.isNullOrEmpty(step.cue)) step.cue else step.road
                CarTextView(cue, style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 2)
            }
        }
        val dim = Color.Black.copy(alpha = 0.16f)
        LanesStrip(step, fg, dim)
        info.junctionImage?.let { junction ->
            Box(Modifier.fillMaxWidth().background(dim).padding(12.dp), contentAlignment = Alignment.Center) {
                CarIconView(junction, 160.dp, widthFactor = 2f, shape = RoundedCornerShape(16.dp))
            }
        }
        info.nextStep?.let { next -> NextStepStrip(next, fg, dim) }
    }
}

@Composable
private fun LanesStrip(step: Step, fg: Color, bg: Color) {
    val lanesImage = step.lanesImage
    val lanes = step.lanes
    if (lanesImage == null && lanes.isEmpty()) return
    Box(Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (lanesImage != null) {
            CarIconView(lanesImage, 44.dp, widthFactor = 6f, monochromeTint = fg)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { lanes.forEach { LaneIcon(it, fg) } }
        }
    }
}

@Composable
private fun LaneIcon(lane: Lane, fg: Color) {
    val directions = lane.directions
    val recommended = directions.firstOrNull { it.isRecommended }
    val shown = recommended ?: directions.firstOrNull()
    Icon(
        laneVector(shown?.shape ?: LaneDirection.SHAPE_STRAIGHT),
        contentDescription = null,
        tint = if (recommended != null) fg else fg.copy(alpha = 0.35f),
        modifier = Modifier.size(40.dp),
    )
}

@Composable
private fun NextStepStrip(next: Step, fg: Color, bg: Color) {
    Row(
        Modifier.fillMaxWidth().background(bg).heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.nav_next_step_then), style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = 0.8f))
        Spacer(Modifier.width(12.dp))
        ManeuverIcon(next.maneuver, 32.dp, fg)
        Spacer(Modifier.width(12.dp))
        CarTextView(if (!CarText.isNullOrEmpty(next.cue)) next.cue else next.road, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** The app's maneuver icon, or a Material arrow for the maneuver type (with roundabout exit). */
@Composable
private fun ManeuverIcon(maneuver: Maneuver?, size: Dp, fg: Color) {
    val icon = maneuver?.icon
    if (icon != null) {
        CarIconView(icon, size, monochromeTint = fg)
        return
    }
    Box(Modifier.size(size)) {
        Icon(maneuverVector(maneuver?.type ?: Maneuver.TYPE_UNKNOWN), null, tint = fg, modifier = Modifier.size(size))
        val exit = maneuver?.roundaboutExitNumber ?: 0
        if (exit > 0 && size >= 48.dp) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(size * 0.38f).clip(CircleShape).background(fg),
                contentAlignment = Alignment.Center,
            ) {
                Text(exit.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = contentColorOn(fg))
            }
        }
    }
}

internal fun maneuverVector(type: Int): ImageVector = when (type) {
    Maneuver.TYPE_STRAIGHT, Maneuver.TYPE_NAME_CHANGE -> Icons.Rounded.Straight
    Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_TURN_SLIGHT_LEFT -> Icons.Rounded.TurnSlightLeft
    Maneuver.TYPE_KEEP_RIGHT, Maneuver.TYPE_TURN_SLIGHT_RIGHT -> Icons.Rounded.TurnSlightRight
    Maneuver.TYPE_TURN_NORMAL_LEFT -> Icons.Rounded.TurnLeft
    Maneuver.TYPE_TURN_NORMAL_RIGHT -> Icons.Rounded.TurnRight
    Maneuver.TYPE_TURN_SHARP_LEFT -> Icons.Rounded.TurnSharpLeft
    Maneuver.TYPE_TURN_SHARP_RIGHT -> Icons.Rounded.TurnSharpRight
    Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_ON_RAMP_U_TURN_LEFT -> Icons.Rounded.UTurnLeft
    Maneuver.TYPE_U_TURN_RIGHT, Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT -> Icons.Rounded.UTurnRight
    Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT, Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, Maneuver.TYPE_ON_RAMP_SHARP_LEFT,
    Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT,
    -> Icons.Rounded.RampLeft
    Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT, Maneuver.TYPE_ON_RAMP_SHARP_RIGHT,
    Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
    -> Icons.Rounded.RampRight
    Maneuver.TYPE_FORK_LEFT -> Icons.Rounded.ForkLeft
    Maneuver.TYPE_FORK_RIGHT -> Icons.Rounded.ForkRight
    Maneuver.TYPE_MERGE_LEFT, Maneuver.TYPE_MERGE_RIGHT, Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED -> Icons.Rounded.Merge
    // clockwise roundabouts are driven on the left (UK, Japan...)
    Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
    Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CW,
    -> Icons.Rounded.RoundaboutLeft
    Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE,
    Maneuver.TYPE_ROUNDABOUT_ENTER_CCW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW,
    -> Icons.Rounded.RoundaboutRight
    Maneuver.TYPE_FERRY_BOAT, Maneuver.TYPE_FERRY_BOAT_LEFT, Maneuver.TYPE_FERRY_BOAT_RIGHT -> Icons.Rounded.DirectionsBoat
    Maneuver.TYPE_FERRY_TRAIN, Maneuver.TYPE_FERRY_TRAIN_LEFT, Maneuver.TYPE_FERRY_TRAIN_RIGHT -> Icons.Rounded.Train
    Maneuver.TYPE_DESTINATION, Maneuver.TYPE_DESTINATION_STRAIGHT, Maneuver.TYPE_DESTINATION_LEFT,
    Maneuver.TYPE_DESTINATION_RIGHT,
    -> Icons.Rounded.Place
    else -> Icons.Rounded.Navigation // DEPART, UNKNOWN
}

private fun laneVector(shape: Int): ImageVector = when (shape) {
    LaneDirection.SHAPE_SLIGHT_LEFT -> Icons.Rounded.TurnSlightLeft
    LaneDirection.SHAPE_SLIGHT_RIGHT -> Icons.Rounded.TurnSlightRight
    LaneDirection.SHAPE_NORMAL_LEFT -> Icons.Rounded.TurnLeft
    LaneDirection.SHAPE_NORMAL_RIGHT -> Icons.Rounded.TurnRight
    LaneDirection.SHAPE_SHARP_LEFT -> Icons.Rounded.TurnSharpLeft
    LaneDirection.SHAPE_SHARP_RIGHT -> Icons.Rounded.TurnSharpRight
    LaneDirection.SHAPE_U_TURN_LEFT -> Icons.Rounded.UTurnLeft
    LaneDirection.SHAPE_U_TURN_RIGHT -> Icons.Rounded.UTurnRight
    else -> Icons.Rounded.Straight
}

@Composable
private fun MessageInfoCard(info: MessageInfo, background: CarColor?, modifier: Modifier) {
    val (bg, fg) = turnCardColors(background)
    val r = LocalCarDimens.current.cardRadius
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(r)).clip(RoundedCornerShape(r)).background(bg).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        info.image?.let {
            CarIconView(it, 64.dp, monochromeTint = fg)
            Spacer(Modifier.width(18.dp))
        }
        Column(Modifier.weight(1f)) {
            CarTextView(info.title, style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 2)
            CarTextView(info.text, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.85f), maxLines = 3)
        }
    }
}

/** Arrival time, remaining time and distance, trip text: one calm line of glanceable facts. */
@Composable
private fun TravelEstimateCard(estimate: TravelEstimate, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val r = LocalCarDimens.current.cardRadius
    val arrival = remember(estimate.arrivalTimeAtDestination) { estimate.arrivalTimeAtDestination?.let { arrivalText(it, context) } }
    val remaining = CarFormat.duration(estimate.remainingTimeSeconds)
    val distance = estimate.remainingDistance?.let { CarFormat.distance(it.displayDistance, it.displayUnit) }
    Row(
        modifier.shadow(8.dp, RoundedCornerShape(r)).clip(RoundedCornerShape(r)).background(scheme.surfaceContainer)
            .heightIn(min = LocalCarDimens.current.touchTarget).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (arrival != null) {
                Text(arrival, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (remaining != null) {
                    Text(remaining, style = MaterialTheme.typography.titleMedium, color = carColor(estimate.remainingTimeColor, scheme.primary), maxLines = 1)
                }
                if (remaining != null && distance != null) {
                    Text("  ·  ", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                }
                if (distance != null) {
                    Text(distance, style = MaterialTheme.typography.titleMedium, color = carColor(estimate.remainingDistanceColor, scheme.onSurfaceVariant), maxLines = 1)
                }
            }
        }
        val tripText = estimate.tripText
        val tripIcon = estimate.tripIcon
        if (tripIcon != null || tripText != null) {
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                tripIcon?.let { CarIconView(it, 32.dp, monochromeTint = scheme.onSurfaceVariant) }
                if (tripIcon != null && tripText != null) Spacer(Modifier.width(8.dp))
                CarTextView(tripText, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

/** "14:32", or "14:32 CET" when the destination is in another time zone than the phone. */
private fun arrivalText(dt: DateTimeWithZone, context: Context): String {
    val millis = dt.timeSinceEpochMillis
    val zone = TimeZone.getTimeZone(ZoneOffset.ofTotalSeconds(dt.zoneOffsetSeconds))
    val format = android.text.format.DateFormat.getTimeFormat(context).apply { timeZone = zone }
    val text = format.format(Date(millis))
    val local = TimeZone.getDefault().getOffset(millis) == dt.zoneOffsetSeconds * 1000
    return if (local) text else "$text ${dt.zoneShortName}"
}
