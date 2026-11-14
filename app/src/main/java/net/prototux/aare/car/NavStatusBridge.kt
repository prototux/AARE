package net.prototux.aare.car

import aap_protobuf.service.navigationstatus.message.NavigationCueOuterClass.NavigationCue
import aap_protobuf.service.navigationstatus.message.NavigationCurrentPositionOuterClass.NavigationCurrentPosition
import aap_protobuf.service.navigationstatus.message.NavigationDestinationDistanceOuterClass.NavigationDestinationDistance
import aap_protobuf.service.navigationstatus.message.NavigationDestinationOuterClass.NavigationDestination
import aap_protobuf.service.navigationstatus.message.NavigationDistanceOuterClass.NavigationDistance
import aap_protobuf.service.navigationstatus.message.NavigationLaneOuterClass.NavigationLane
import aap_protobuf.service.navigationstatus.message.NavigationManeuverOuterClass.NavigationManeuver
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnDistanceEventOuterClass.NavigationNextTurnDistanceEvent
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent
import aap_protobuf.service.navigationstatus.message.NavigationRoadOuterClass.NavigationRoad
import aap_protobuf.service.navigationstatus.message.NavigationStateOuterClass.NavigationState
import aap_protobuf.service.navigationstatus.message.NavigationStatusOuterClass.NavigationStatus
import aap_protobuf.service.navigationstatus.message.NavigationStepDistanceOuterClass.NavigationStepDistance
import aap_protobuf.service.navigationstatus.message.NavigationStepOuterClass.NavigationStep
import android.graphics.Bitmap
import android.util.Log
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.Trip
import com.google.protobuf.ByteString
import net.prototux.aare.aap.channels.NavigationStatusChannel
import net.prototux.aare.notify.PhoneGuidance
import java.text.DateFormat
import java.util.Date

/**
 * Sends the navigation app's trip to the head unit (instrument cluster, head-up display, the
 * car's own widgets). The Car App Library's maneuver types and distance units use the same
 * numbering as the protocol, so most of this is copying fields.
 */
class NavStatusBridge(
    private val channel: NavigationStatusChannel,
    private val turnImage: (Maneuver, Int, Int) -> ByteArray?,
    /** A bitmap (the phone navigation app's turn arrow) as a PNG of the given size. */
    private val png: (Bitmap, Int, Int) -> ByteArray?,
    /** AARE's own arrow for a maneuver type, as a PNG of the given size and colour depth. */
    private val typeIcon: (type: Int, width: Int, height: Int, colourDepthBits: Int) -> ByteArray?,
) {
    private var active = false
    /** REROUTING was sent: the next guidance sends ACTIVE again. */
    private var rerouting = false
    /** A hosted navigation app is guiding: it wins over [phone] guidance. */
    private var hosted = false
    /** The hosted app has sent steps: only then is a loading trip a reroute, not the first route. */
    private var hostedSteps = false
    private var phone: PhoneGuidance? = null

    /**
     * Never throws: this runs on the main thread from the navigation app's callbacks, and a bad
     * message (a missing required field) must not take the whole app down.
     */
    fun update(trip: Trip?) {
        runCatching { send(trip) }.onFailure { Log.w(TAG, "navigation status not sent", it) }
    }

    private fun send(trip: Trip?) {
        if (trip != null && trip.isLoading) {
            // The app is working out a route, and a loading trip can't carry steps (the library
            // forbids it): still the hosted app's guidance, not its end, so the cluster keeps it
            // and the phone app's guidance doesn't take over meanwhile. Before its first route
            // there is nothing to reroute: wait for the steps.
            hosted = true
            if (!hostedSteps) return
            if (!active) {
                active = true
                channel.status(NavigationStatus.NavigationStatusEnum.ACTIVE)
            }
            if (!rerouting) {
                rerouting = true
                channel.status(NavigationStatus.NavigationStatusEnum.REROUTING)
            }
            return
        }
        if (trip == null || trip.steps.isEmpty()) {
            hosted = false
            hostedSteps = false
            if (phone != null) { sendPhone(phone!!); return }
            if (active) {
                active = false
                rerouting = false
                channel.status(NavigationStatus.NavigationStatusEnum.INACTIVE)
            }
            return
        }
        hosted = true
        hostedSteps = true
        activate()
        val step = trip.steps.first()
        val maneuver = step.maneuver
        val stepEstimate = trip.stepTravelEstimates.firstOrNull()
        val destEstimate = trip.destinationTravelEstimates.firstOrNull()
        val road = step.road?.toString() ?: trip.currentRoad?.toString() ?: ""

        val state = NavigationState.newBuilder()
        trip.steps.take(3).forEach { state.addSteps(stepOf(it)) }
        trip.destinations.forEach { d ->
            state.addDestinations(NavigationDestination.newBuilder().setAddress(listOfNotNull(d.name, d.address).joinToString(", ")))
        }

        val position = NavigationCurrentPosition.newBuilder()
        stepEstimate?.let { e ->
            position.setStepDistance(NavigationStepDistance.newBuilder().apply {
                e.remainingDistance?.let { setDistance(distanceOf(it)) }
                // An unknown (-1) or zero time is left out.
                if (e.remainingTimeSeconds > 0) setTimeToStepSeconds(e.remainingTimeSeconds)
            })
        }
        destEstimate?.let { e ->
            position.addDestinationDistances(NavigationDestinationDistance.newBuilder()
                .apply { e.remainingDistance?.let { setDistance(distanceOf(it)) } }
                .setTimeToArrivalSeconds(e.remainingTimeSeconds)
                .setEstimatedTimeAtArrival(e.arrivalTimeAtDestination?.let {
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.timeSinceEpochMillis))
                } ?: ""))
        }
        trip.currentRoad?.let { position.setCurrentRoad(NavigationRoad.newBuilder().setName(it.toString())) }

        // The older events most head units still use for their cluster.
        val turn = legacyTurn(road, maneuver?.type, maneuver?.roundaboutExitNumber ?: 0, maneuver?.roundaboutExitAngle ?: 0)
        image(maneuver?.type ?: Maneuver.TYPE_UNKNOWN) { w, h -> maneuver?.let { turnImage(it, w, h) } }?.let { turn.setImage(it) }
        val distance = stepEstimate?.let { e ->
            val d = e.remainingDistance ?: return@let null
            NavigationNextTurnDistanceEvent.newBuilder()
                .setDistanceMeters(metersOf(d).toInt())
                .setTimeToTurnSeconds(e.remainingTimeSeconds.toInt().coerceAtLeast(0))
                .setDisplayDistanceE3((d.displayDistance * 1000).toInt())
                .setDisplayDistanceUnit(NavigationNextTurnDistanceEvent.DistanceUnits.forNumber(d.displayUnit)
                    ?: NavigationNextTurnDistanceEvent.DistanceUnits.UNKNOWN_DISTANCE_UNIT)
                .build()
        }
        channel.update(state.build(), position.build(), turn.build(), distance)
    }

    /**
     * Guidance from a navigation app running on the phone (Google Maps, Waze…), read from its
     * notification: only text, a distance and the app's arrow image, no maneuver type.
     */
    fun updatePhone(g: PhoneGuidance?) {
        phone = g
        if (hosted) return
        runCatching {
            if (g != null) sendPhone(g)
            else if (active) {
                active = false
                rerouting = false
                channel.status(NavigationStatus.NavigationStatusEnum.INACTIVE)
            }
        }.onFailure { Log.w(TAG, "phone guidance not sent", it) }
    }

    /** ACTIVE when guidance starts, and again when it comes back after a reroute. */
    private fun activate() {
        if (active && !rerouting) return
        active = true
        rerouting = false
        channel.status(NavigationStatus.NavigationStatusEnum.ACTIVE)
    }

    private fun sendPhone(g: PhoneGuidance) {
        activate()
        val state = NavigationState.newBuilder()
            .addSteps(NavigationStep.newBuilder().setCue(NavigationCue.newBuilder().addAlternateText(g.instruction)))
        val position = NavigationCurrentPosition.newBuilder()
        g.distance?.let { d ->
            position.setStepDistance(NavigationStepDistance.newBuilder().setDistance(NavigationDistance.newBuilder()
                .setMeters(d.meters).setDisplayValue(d.display)
                .setDisplayUnits(NavigationDistance.DistanceUnits.forNumber(unitNumber(d)) ?: NavigationDistance.DistanceUnits.UNKNOWN_DISTANCE_UNIT)))
        }
        val turn = legacyTurn(g.instruction, null, 0, 0)
        image(Maneuver.TYPE_UNKNOWN) { w, h -> g.arrow?.let { png(it, w, h) } }?.let { turn.setImage(it) }
        val distance = g.distance?.let { d ->
            NavigationNextTurnDistanceEvent.newBuilder()
                .setDistanceMeters(d.meters)
                // Required by the message; the notification gives no time to the turn.
                .setTimeToTurnSeconds(0)
                .setDisplayDistanceE3(((d.display.toDoubleOrNull() ?: 0.0) * 1000).toInt())
                .setDisplayDistanceUnit(NavigationNextTurnDistanceEvent.DistanceUnits.forNumber(unitNumber(d))
                    ?: NavigationNextTurnDistanceEvent.DistanceUnits.UNKNOWN_DISTANCE_UNIT)
                .build()
        }
        channel.update(state.build(), position.build(), turn.build(), distance)
    }

    /**
     * The turn picture for an image cluster: the app's [own], else AARE's arrow for the maneuver
     * type, drawn by default when the app gives none.
     */
    private fun image(type: Int, own: (Int, Int) -> ByteArray?): ByteString? {
        if (!channel.wantsImages) return null
        val o = channel.imageOptions
        val w = o.width.takeIf { it > 0 } ?: 64
        val h = o.height.takeIf { it > 0 } ?: 64
        return (own(w, h) ?: typeIcon(type, w, h, o.colourDepthBits))?.let { ByteString.copyFrom(it) }
    }

    /** The protocol's distance unit number (the same in both distance messages). */
    private fun unitNumber(d: PhoneGuidance.Distance): Int = when (d.unit) {
        PhoneGuidance.DistanceUnit.METERS -> 1
        PhoneGuidance.DistanceUnit.KILOMETERS -> if ('.' in d.display) 3 else 2
        PhoneGuidance.DistanceUnit.MILES -> if ('.' in d.display) 5 else 4
        PhoneGuidance.DistanceUnit.FEET -> 6
        PhoneGuidance.DistanceUnit.YARDS -> 7
    }

    /** A step with its lanes, sent when the navigation app gives them. */
    private fun stepOf(s: Step): NavigationStep = NavigationStep.newBuilder().apply {
        s.maneuver?.let { setManeuver(maneuverOf(it)) }
        s.road?.let { setRoad(NavigationRoad.newBuilder().setName(it.toString())) }
        s.lanes.forEach { addLanes(laneOf(it)) }
        s.cue?.let { setCue(NavigationCue.newBuilder().addAlternateText(it.toString())) }
    }.build()

    /** A type the protocol doesn't have leaves the maneuver empty. */
    private fun maneuverOf(m: Maneuver): NavigationManeuver = NavigationManeuver.newBuilder().apply {
        val type = protocolType(m.type) ?: return@apply
        setType(type)
        if (m.roundaboutExitNumber > 0) setRoundaboutExitNumber(m.roundaboutExitNumber)
        if (m.roundaboutExitAngle > 0) setRoundaboutExitAngle(m.roundaboutExitAngle)
    }.build()

    private fun distanceOf(d: Distance): NavigationDistance = NavigationDistance.newBuilder()
        .setMeters(metersOf(d).toInt())
        .setDisplayValue(formatDisplay(d))
        .setDisplayUnits(NavigationDistance.DistanceUnits.forNumber(d.displayUnit) ?: NavigationDistance.DistanceUnits.UNKNOWN_DISTANCE_UNIT)
        .build()

    companion object {
        private const val TAG = "AareNavStatus"

        /**
         * The protocol's NavigationType for a Maneuver.TYPE_*: the same numbers, roundabout enter
         * and exit (43..46) included. Ferries with a side (47..50) go as the plain ferry (37, 38),
         * as AARE always sent them: head units that work with that today keep showing a ferry.
         * Null for a number the protocol doesn't have.
         */
        internal fun protocolType(type: Int): NavigationManeuver.NavigationType? = when (type) {
            Maneuver.TYPE_FERRY_BOAT_LEFT, Maneuver.TYPE_FERRY_BOAT_RIGHT -> NavigationManeuver.NavigationType.FERRY_BOAT
            Maneuver.TYPE_FERRY_TRAIN_LEFT, Maneuver.TYPE_FERRY_TRAIN_RIGHT -> NavigationManeuver.NavigationType.FERRY_TRAIN
            else -> NavigationManeuver.NavigationType.forNumber(type)
        }

        /** The library's lane shapes are the protocol's plus one (its SHAPE_UNKNOWN is 1). */
        internal fun laneOf(lane: Lane): NavigationLane = NavigationLane.newBuilder().apply {
            lane.directions.forEach { d ->
                addLaneDirections(NavigationLane.LaneDirection.newBuilder()
                    .setShape(NavigationLane.LaneDirection.Shape.forNumber(d.shape - 1) ?: NavigationLane.LaneDirection.Shape.UNKNOWN)
                    .setIsHighlighted(d.isRecommended))
            }
        }.build()

        fun metersOf(d: Distance): Double = d.displayDistance * when (d.displayUnit) {
            Distance.UNIT_KILOMETERS, Distance.UNIT_KILOMETERS_P1 -> 1000.0
            Distance.UNIT_MILES, Distance.UNIT_MILES_P1 -> 1609.344
            Distance.UNIT_FEET -> 0.3048
            Distance.UNIT_YARDS -> 0.9144
            else -> 1.0
        }

        fun formatDisplay(d: Distance): String = when (d.displayUnit) {
            Distance.UNIT_KILOMETERS_P1, Distance.UNIT_MILES_P1 -> "%.1f".format(d.displayDistance)
            else -> d.displayDistance.toLong().toString()
        }

        /** The turn's side: clockwise roundabouts (driven on the left) LEFT, counter-clockwise RIGHT. */
        private val LEFT_TYPES = setOf(
            Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_TURN_SLIGHT_LEFT, Maneuver.TYPE_TURN_NORMAL_LEFT,
            Maneuver.TYPE_TURN_SHARP_LEFT, Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT,
            Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, Maneuver.TYPE_ON_RAMP_SHARP_LEFT, Maneuver.TYPE_ON_RAMP_U_TURN_LEFT,
            Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT, Maneuver.TYPE_FORK_LEFT,
            Maneuver.TYPE_MERGE_LEFT, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW,
            Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE, Maneuver.TYPE_DESTINATION_LEFT,
            Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CW, Maneuver.TYPE_FERRY_BOAT_LEFT,
            Maneuver.TYPE_FERRY_TRAIN_LEFT,
        )
        private val RIGHT_TYPES = setOf(
            Maneuver.TYPE_KEEP_RIGHT, Maneuver.TYPE_TURN_SLIGHT_RIGHT, Maneuver.TYPE_TURN_NORMAL_RIGHT,
            Maneuver.TYPE_TURN_SHARP_RIGHT, Maneuver.TYPE_U_TURN_RIGHT, Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT,
            Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT, Maneuver.TYPE_ON_RAMP_SHARP_RIGHT, Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT,
            Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT, Maneuver.TYPE_FORK_RIGHT,
            Maneuver.TYPE_MERGE_RIGHT, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW,
            Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, Maneuver.TYPE_DESTINATION_RIGHT,
            Maneuver.TYPE_ROUNDABOUT_ENTER_CCW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW, Maneuver.TYPE_FERRY_BOAT_RIGHT,
            Maneuver.TYPE_FERRY_TRAIN_RIGHT,
        )

        internal fun sideOf(type: Int): NavigationNextTurnEvent.TurnSide = when (type) {
            in LEFT_TYPES -> NavigationNextTurnEvent.TurnSide.LEFT
            in RIGHT_TYPES -> NavigationNextTurnEvent.TurnSide.RIGHT
            else -> NavigationNextTurnEvent.TurnSide.UNSPECIFIED
        }

        /**
         * The deprecated event as head units below protocol 1.6 get it today: always the road ("" if
         * unknown); the kind of turn and its side only with a maneuver ([type] null without one);
         * the exit number and angle only when there is one.
         */
        internal fun legacyTurn(road: String, type: Int?, exitNumber: Int, exitAngle: Int): NavigationNextTurnEvent.Builder =
            NavigationNextTurnEvent.newBuilder().setRoad(road).apply {
                if (type != null) setEvent(legacyEvent(type)).setTurnSide(sideOf(type))
                if (exitNumber > 0) setTurnNumber(exitNumber)
                if (exitAngle > 0) setTurnAngle(exitAngle)
            }

        /** The deprecated event's coarser kind of turn for a Maneuver.TYPE_* (the side goes apart). */
        internal fun legacyEvent(type: Int): NavigationNextTurnEvent.NextTurnEnum = when (type) {
            Maneuver.TYPE_DEPART -> NavigationNextTurnEvent.NextTurnEnum.DEPART
            Maneuver.TYPE_NAME_CHANGE -> NavigationNextTurnEvent.NextTurnEnum.NAME_CHANGE
            Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_KEEP_RIGHT,
            Maneuver.TYPE_TURN_SLIGHT_LEFT, Maneuver.TYPE_TURN_SLIGHT_RIGHT,
            -> NavigationNextTurnEvent.NextTurnEnum.SLIGHT_TURN
            Maneuver.TYPE_TURN_NORMAL_LEFT, Maneuver.TYPE_TURN_NORMAL_RIGHT -> NavigationNextTurnEvent.NextTurnEnum.TURN
            Maneuver.TYPE_TURN_SHARP_LEFT, Maneuver.TYPE_TURN_SHARP_RIGHT -> NavigationNextTurnEvent.NextTurnEnum.SHARP_TURN
            Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_RIGHT -> NavigationNextTurnEvent.NextTurnEnum.U_TURN
            in Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT..Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT -> NavigationNextTurnEvent.NextTurnEnum.ON_RAMP
            in Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT..Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT -> NavigationNextTurnEvent.NextTurnEnum.OFF_RAMP
            Maneuver.TYPE_FORK_LEFT, Maneuver.TYPE_FORK_RIGHT -> NavigationNextTurnEvent.NextTurnEnum.FORK
            Maneuver.TYPE_MERGE_LEFT, Maneuver.TYPE_MERGE_RIGHT, Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED,
            -> NavigationNextTurnEvent.NextTurnEnum.MERGE
            Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_CCW,
            -> NavigationNextTurnEvent.NextTurnEnum.ROUNDABOUT_ENTER
            Maneuver.TYPE_ROUNDABOUT_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW,
            -> NavigationNextTurnEvent.NextTurnEnum.ROUNDABOUT_EXIT
            in Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW..Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE,
            -> NavigationNextTurnEvent.NextTurnEnum.ROUNDABOUT_ENTER_AND_EXIT
            Maneuver.TYPE_STRAIGHT -> NavigationNextTurnEvent.NextTurnEnum.STRAIGHT
            Maneuver.TYPE_FERRY_BOAT, Maneuver.TYPE_FERRY_BOAT_LEFT, Maneuver.TYPE_FERRY_BOAT_RIGHT,
            -> NavigationNextTurnEvent.NextTurnEnum.FERRY_BOAT
            Maneuver.TYPE_FERRY_TRAIN, Maneuver.TYPE_FERRY_TRAIN_LEFT, Maneuver.TYPE_FERRY_TRAIN_RIGHT,
            -> NavigationNextTurnEvent.NextTurnEnum.FERRY_TRAIN
            in Maneuver.TYPE_DESTINATION..Maneuver.TYPE_DESTINATION_RIGHT -> NavigationNextTurnEvent.NextTurnEnum.DESTINATION
            else -> NavigationNextTurnEvent.NextTurnEnum.UNKNOWN
        }
    }
}
