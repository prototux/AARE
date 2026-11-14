package net.prototux.aare.car

import aap_protobuf.service.navigationstatus.message.NavigationManeuverOuterClass.NavigationManeuver.NavigationType
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent.NextTurnEnum
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent.TurnSide
import androidx.car.app.navigation.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStatusBridgeTest {

    @Test
    fun maneuverTypesPassThroughUnchanged() {
        // 0..29 and 32..50 are accepted and the number is sent as is.
        for (type in (0..29) + (32..50)) assertEquals(type, NavStatusBridge.protocolType(type)?.number)
        assertEquals(NavigationType.ROUNDABOUT_ENTER_CW, NavStatusBridge.protocolType(Maneuver.TYPE_ROUNDABOUT_ENTER_CW))
        assertEquals(NavigationType.FERRY_TRAIN_RIGHT, NavStatusBridge.protocolType(Maneuver.TYPE_FERRY_TRAIN_RIGHT))
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
    fun keepingToASideIsStraightWithoutSide() {
        for (type in listOf(Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_KEEP_RIGHT)) {
            assertEquals(NextTurnEnum.STRAIGHT, NavStatusBridge.legacyEvent(type))
            assertEquals(TurnSide.UNSPECIFIED, NavStatusBridge.sideOf(type))
        }
    }

    @Test
    fun legacyTurnFillsEveryField() {
        val turn = NavStatusBridge.legacyTurn("", Maneuver.TYPE_UNKNOWN, 0, -1).build()
        assertTrue(turn.hasRoad() && turn.hasEvent() && turn.hasTurnSide() && turn.hasTurnNumber() && turn.hasTurnAngle())
        assertEquals("", turn.road)
        assertEquals(NextTurnEnum.UNKNOWN, turn.event)
        assertEquals(TurnSide.UNSPECIFIED, turn.turnSide)
        assertEquals(0, turn.turnNumber)
        assertEquals(0, turn.turnAngle)

        val roundabout = NavStatusBridge.legacyTurn("Main St", Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE, 2, 135).build()
        assertEquals(TurnSide.RIGHT, roundabout.turnSide)
        assertEquals(2, roundabout.turnNumber)
        assertEquals(135, roundabout.turnAngle)
    }
}
