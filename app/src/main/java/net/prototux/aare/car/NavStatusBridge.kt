package net.prototux.aare.car

import aap_protobuf.service.navigationstatus.message.NavigationCueOuterClass.NavigationCue
import aap_protobuf.service.navigationstatus.message.NavigationCurrentPositionOuterClass.NavigationCurrentPosition
import aap_protobuf.service.navigationstatus.message.NavigationDestinationDistanceOuterClass.NavigationDestinationDistance
import aap_protobuf.service.navigationstatus.message.NavigationDestinationOuterClass.NavigationDestination
import aap_protobuf.service.navigationstatus.message.NavigationDistanceOuterClass.NavigationDistance
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
import androidx.car.app.navigation.model.Maneuver
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
        trip.steps.take(3).forEach { s ->
            state.addSteps(NavigationStep.newBuilder().apply {
                s.maneuver?.let { setManeuver(maneuverOf(it)) }
                s.road?.let { setRoad(NavigationRoad.newBuilder().setName(it.toString())) }
                s.cue?.let { setCue(NavigationCue.newBuilder().addAlternateText(it.toString())) }
            })
        }
        trip.destinations.forEach { d ->
            state.addDestinations(NavigationDestination.newBuilder().setAddress(listOfNotNull(d.name, d.address).joinToString(", ")))
        }

        val position = NavigationCurrentPosition.newBuilder()
        stepEstimate?.let { e ->
            position.setStepDistance(NavigationStepDistance.newBuilder().apply {
                e.remainingDistance?.let { setDistance(distanceOf(it)) }
                setTimeToStepSeconds(e.remainingTimeSeconds)
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
        val turn = NavigationNextTurnEvent.newBuilder().setRoad(road)
        maneuver?.let { m ->
            turn.setEvent(legacyEvent(protocolType(m.type))).setTurnSide(sideOf(m.type))
            if (m.roundaboutExitNumber > 0) turn.setTurnNumber(m.roundaboutExitNumber)
            if (m.roundaboutExitAngle > 0) turn.setTurnAngle(m.roundaboutExitAngle)
            if (channel.wantsImages) {
                val o = channel.imageOptions
                turnImage(m, o.width.takeIf { it > 0 } ?: 64, o.height.takeIf { it > 0 } ?: 64)?.let { turn.setImage(ByteString.copyFrom(it)) }
            }
        }
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
        val turn = NavigationNextTurnEvent.newBuilder().setRoad(g.instruction)
        val arrow = g.arrow
        if (arrow != null && channel.wantsImages) {
            val o = channel.imageOptions
            png(arrow, o.width.takeIf { it > 0 } ?: 64, o.height.takeIf { it > 0 } ?: 64)?.let { turn.setImage(ByteString.copyFrom(it)) }
        }
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

    /** The protocol's distance unit number (the same in both distance messages). */
    private fun unitNumber(d: PhoneGuidance.Distance): Int = when (d.unit) {
        PhoneGuidance.DistanceUnit.METERS -> 1
        PhoneGuidance.DistanceUnit.KILOMETERS -> if ('.' in d.display) 3 else 2
        PhoneGuidance.DistanceUnit.MILES -> if ('.' in d.display) 5 else 4
        PhoneGuidance.DistanceUnit.FEET -> 6
        PhoneGuidance.DistanceUnit.YARDS -> 7
    }

    private fun maneuverOf(m: Maneuver): NavigationManeuver = NavigationManeuver.newBuilder().apply {
        setType(NavigationManeuver.NavigationType.forNumber(protocolType(m.type)) ?: NavigationManeuver.NavigationType.UNKNOWN)
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
         * Car App Library maneuver types newer than the protocol's list (43..50: roundabout enter
         * or exit with a direction, ferries with a side), by their library name.
         */
        private val NEWER_TYPES = mapOf(
            43 to "ROUNDABOUT_ENTER_CW", 44 to "ROUNDABOUT_EXIT_CW",
            45 to "ROUNDABOUT_ENTER_CCW", 46 to "ROUNDABOUT_EXIT_CCW",
            47 to "FERRY_BOAT_LEFT", 48 to "FERRY_BOAT_RIGHT",
            49 to "FERRY_TRAIN_LEFT", 50 to "FERRY_TRAIN_RIGHT",
        )

        /** The protocol's NavigationType number for a Maneuver.TYPE_* (the newer ones lose their side). */
        private fun protocolType(type: Int): Int {
            val n = NEWER_TYPES[type] ?: return type
            return when {
                n.startsWith("ROUNDABOUT_ENTER") -> NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_VALUE
                n.startsWith("ROUNDABOUT_EXIT") -> NavigationManeuver.NavigationType.ROUNDABOUT_EXIT_VALUE
                n.startsWith("FERRY_BOAT") -> NavigationManeuver.NavigationType.FERRY_BOAT_VALUE
                else -> NavigationManeuver.NavigationType.FERRY_TRAIN_VALUE
            }
        }

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

        private fun sideOf(type: Int): NavigationNextTurnEvent.TurnSide {
            val name = maneuverName(type)
            return when {
                "LEFT" in name || "_CW" in name && "CCW" !in name -> NavigationNextTurnEvent.TurnSide.LEFT
                "RIGHT" in name || "CCW" in name -> NavigationNextTurnEvent.TurnSide.RIGHT
                else -> NavigationNextTurnEvent.TurnSide.UNSPECIFIED
            }
        }

        private fun legacyEvent(type: Int): NavigationNextTurnEvent.NextTurnEnum {
            val n = maneuverName(type)
            return when {
                n == "DEPART" -> NavigationNextTurnEvent.NextTurnEnum.DEPART
                n == "NAME_CHANGE" -> NavigationNextTurnEvent.NextTurnEnum.NAME_CHANGE
                n.startsWith("KEEP") || n.startsWith("TURN_SLIGHT") -> NavigationNextTurnEvent.NextTurnEnum.SLIGHT_TURN
                n.startsWith("TURN_NORMAL") -> NavigationNextTurnEvent.NextTurnEnum.TURN
                n.startsWith("TURN_SHARP") -> NavigationNextTurnEvent.NextTurnEnum.SHARP_TURN
                n.startsWith("U_TURN") -> NavigationNextTurnEvent.NextTurnEnum.U_TURN
                n.startsWith("ON_RAMP") -> NavigationNextTurnEvent.NextTurnEnum.ON_RAMP
                n.startsWith("OFF_RAMP") -> NavigationNextTurnEvent.NextTurnEnum.OFF_RAMP
                n.startsWith("FORK") -> NavigationNextTurnEvent.NextTurnEnum.FORK
                n.startsWith("MERGE") -> NavigationNextTurnEvent.NextTurnEnum.MERGE
                n == "ROUNDABOUT_ENTER" -> NavigationNextTurnEvent.NextTurnEnum.ROUNDABOUT_ENTER
                n == "ROUNDABOUT_EXIT" -> NavigationNextTurnEvent.NextTurnEnum.ROUNDABOUT_EXIT
                n.startsWith("ROUNDABOUT") -> NavigationNextTurnEvent.NextTurnEnum.ROUNDABOUT_ENTER_AND_EXIT
                n == "STRAIGHT" -> NavigationNextTurnEvent.NextTurnEnum.STRAIGHT
                n == "FERRY_BOAT" -> NavigationNextTurnEvent.NextTurnEnum.FERRY_BOAT
                n == "FERRY_TRAIN" -> NavigationNextTurnEvent.NextTurnEnum.FERRY_TRAIN
                n.startsWith("DESTINATION") -> NavigationNextTurnEvent.NextTurnEnum.DESTINATION
                else -> NavigationNextTurnEvent.NextTurnEnum.UNKNOWN
            }
        }

        /**
         * Maneuver.TYPE_* and the protocol's NavigationType share numbers up to 42; use the
         * protocol's names, and the library's for the newer ones (they carry the turn side).
         */
        private fun maneuverName(type: Int): String =
            NEWER_TYPES[type] ?: NavigationManeuver.NavigationType.forNumber(type)?.name ?: "UNKNOWN"
    }
}
