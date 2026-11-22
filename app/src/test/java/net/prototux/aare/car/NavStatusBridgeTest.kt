package net.prototux.aare.car

import aap_protobuf.service.navigationstatus.message.NavigationLaneOuterClass.NavigationLane.LaneDirection.Shape
import aap_protobuf.service.navigationstatus.message.NavigationManeuverOuterClass.NavigationManeuver.NavigationType
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent.NextTurnEnum
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent.TurnSide
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStatusBridgeTest {

    @Test
    fun maneuverTypesPassThroughUnchanged() {
        // 0..29 and 32..46 are sent as they are.
        for (type in (0..29) + (32..46)) assertEquals(type, NavStatusBridge.protocolType(type)?.number)
        assertEquals(NavigationType.ROUNDABOUT_ENTER_CW, NavStatusBridge.protocolType(Maneuver.TYPE_ROUNDABOUT_ENTER_CW))
        assertEquals(NavigationType.ROUNDABOUT_EXIT_CCW, NavStatusBridge.protocolType(Maneuver.TYPE_ROUNDABOUT_EXIT_CCW))
    }

    @Test
    fun ferriesWithASideGoAsThePlainFerry() {
        assertEquals(NavigationType.FERRY_BOAT, NavStatusBridge.protocolType(Maneuver.TYPE_FERRY_BOAT_LEFT))
        assertEquals(NavigationType.FERRY_BOAT, NavStatusBridge.protocolType(Maneuver.TYPE_FERRY_BOAT_RIGHT))
        assertEquals(NavigationType.FERRY_TRAIN, NavStatusBridge.protocolType(Maneuver.TYPE_FERRY_TRAIN_LEFT))
        assertEquals(NavigationType.FERRY_TRAIN, NavStatusBridge.protocolType(Maneuver.TYPE_FERRY_TRAIN_RIGHT))
    }

    @Test
    fun numbersTheProtocolLacksHaveNoType() {
        assertNull(NavStatusBridge.protocolType(30))
        assertNull(NavStatusBridge.protocolType(31))
        assertNull(NavStatusBridge.protocolType(51))
        assertNull(NavStatusBridge.protocolType(-1))
    }

    @Test
    fun roundaboutEnterAndExitKeepTheirLegacyEvent() {
        assertEquals(NextTurnEnum.ROUNDABOUT_ENTER, NavStatusBridge.legacyEvent(Maneuver.TYPE_ROUNDABOUT_ENTER_CW))
        assertEquals(NextTurnEnum.ROUNDABOUT_ENTER, NavStatusBridge.legacyEvent(Maneuver.TYPE_ROUNDABOUT_ENTER_CCW))
        assertEquals(NextTurnEnum.ROUNDABOUT_EXIT, NavStatusBridge.legacyEvent(Maneuver.TYPE_ROUNDABOUT_EXIT_CW))
        assertEquals(NextTurnEnum.ROUNDABOUT_EXIT, NavStatusBridge.legacyEvent(Maneuver.TYPE_ROUNDABOUT_EXIT_CCW))
        assertEquals(NextTurnEnum.ROUNDABOUT_ENTER_AND_EXIT, NavStatusBridge.legacyEvent(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE))
        assertEquals(NextTurnEnum.FERRY_BOAT, NavStatusBridge.legacyEvent(Maneuver.TYPE_FERRY_BOAT_RIGHT))
        assertEquals(NextTurnEnum.FERRY_TRAIN, NavStatusBridge.legacyEvent(Maneuver.TYPE_FERRY_TRAIN_LEFT))
        assertEquals(NextTurnEnum.DESTINATION, NavStatusBridge.legacyEvent(Maneuver.TYPE_DESTINATION_LEFT))
        assertEquals(NextTurnEnum.UNKNOWN, NavStatusBridge.legacyEvent(Maneuver.TYPE_UNKNOWN))
    }

    @Test
    fun sides() {
        assertEquals(TurnSide.LEFT, NavStatusBridge.sideOf(Maneuver.TYPE_TURN_NORMAL_LEFT))
        assertEquals(TurnSide.RIGHT, NavStatusBridge.sideOf(Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT))
        // Clockwise roundabouts are driven on the left.
        assertEquals(TurnSide.LEFT, NavStatusBridge.sideOf(Maneuver.TYPE_ROUNDABOUT_EXIT_CW))
        assertEquals(TurnSide.RIGHT, NavStatusBridge.sideOf(Maneuver.TYPE_ROUNDABOUT_ENTER_CCW))
        assertEquals(TurnSide.RIGHT, NavStatusBridge.sideOf(Maneuver.TYPE_FERRY_BOAT_RIGHT))
        assertEquals(TurnSide.UNSPECIFIED, NavStatusBridge.sideOf(Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED))
        assertEquals(TurnSide.UNSPECIFIED, NavStatusBridge.sideOf(Maneuver.TYPE_STRAIGHT))
    }

    @Test
    fun keepingToASideIsASlightTurnToThatSide() {
        assertEquals(NextTurnEnum.SLIGHT_TURN, NavStatusBridge.legacyEvent(Maneuver.TYPE_KEEP_LEFT))
        assertEquals(TurnSide.LEFT, NavStatusBridge.sideOf(Maneuver.TYPE_KEEP_LEFT))
        assertEquals(NextTurnEnum.SLIGHT_TURN, NavStatusBridge.legacyEvent(Maneuver.TYPE_KEEP_RIGHT))
        assertEquals(TurnSide.RIGHT, NavStatusBridge.sideOf(Maneuver.TYPE_KEEP_RIGHT))
    }

    @Test
    fun legacyTurnLeavesOutWhatIsNotKnown() {
        val none = NavStatusBridge.legacyTurn("", null, 0, -1).build()
        assertTrue(none.hasRoad())
        assertEquals("", none.road)
        assertFalse(none.hasEvent() || none.hasTurnSide() || none.hasTurnNumber() || none.hasTurnAngle())

        val turn = NavStatusBridge.legacyTurn("Main St", Maneuver.TYPE_TURN_NORMAL_LEFT, 0, 0).build()
        assertEquals(NextTurnEnum.TURN, turn.event)
        assertEquals(TurnSide.LEFT, turn.turnSide)
        assertFalse(turn.hasTurnNumber() || turn.hasTurnAngle())

        val roundabout = NavStatusBridge.legacyTurn("Main St", Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, 2, 135).build()
        assertEquals(TurnSide.RIGHT, roundabout.turnSide)
        assertEquals(2, roundabout.turnNumber)
        assertEquals(135, roundabout.turnAngle)
    }

    @Test
    fun lanesKeepShapeAndRecommendation() {
        val lane = Lane.Builder()
            .addDirection(LaneDirection.create(LaneDirection.SHAPE_NORMAL_LEFT, true))
            .addDirection(LaneDirection.create(LaneDirection.SHAPE_STRAIGHT, false))
            .addDirection(LaneDirection.create(LaneDirection.SHAPE_U_TURN_RIGHT, false))
            .build()
        val sent = NavStatusBridge.laneOf(lane).laneDirectionsList
        assertEquals(listOf(Shape.NORMAL_LEFT, Shape.STRAIGHT, Shape.U_TURN_RIGHT), sent.map { it.shape })
        assertTrue(sent[0].isHighlighted)
        assertFalse(sent[1].isHighlighted)
    }
}
