package net.prototux.aare.ui.car

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSplitTest {
    @Test
    fun onlyWideEnoughScreensFitASplit() {
        assertTrue(splitScreenFits(900.dp))
        assertTrue(splitScreenFits(1280.dp))
        assertFalse(splitScreenFits(899.dp))
        assertFalse(splitScreenFits(600.dp))
    }

    @Test
    fun onlyNonHostedScreensCanTakeTheSecondPane() {
        assertTrue(canOpenBeside(CarAppKind.MEDIA))
        assertTrue(canOpenBeside(CarAppKind.PHONE))
        assertTrue(canOpenBeside(CarAppKind.MESSAGING))
        assertTrue(canOpenBeside(CarAppKind.BROWSER))
        assertTrue(canOpenBeside(CarAppKind.CAR_DATA))
        assertTrue(canOpenBeside(CarAppKind.SETTINGS))
        assertTrue(canOpenBeside(CarAppKind.NOTIFICATIONS))
        // Hosted Car App Library apps keep CarAppConnection's one-surface-owner-at-a-time rule.
        assertFalse(canOpenBeside(CarAppKind.NAVIGATION))
        assertFalse(canOpenBeside(CarAppKind.TEMPLATED))
        assertFalse(canOpenBeside(CarAppKind.ASSISTANT))
        // Device-wide, single-instance resources, not doubled up for a first version.
        assertFalse(canOpenBeside(CarAppKind.PHONE_SCREEN))
        assertFalse(canOpenBeside(CarAppKind.STANDALONE))
        assertFalse(canOpenBeside(CarAppKind.EXIT))
    }

    @Test
    fun dragSnapsToTheNearestThirdHalfOrTwoThirds() {
        assertEquals(1f / 3f, snapSplitRatio(0.30f), 0.0001f)
        assertEquals(0.5f, snapSplitRatio(0.46f), 0.0001f)
        assertEquals(2f / 3f, snapSplitRatio(0.70f), 0.0001f)
    }

    @Test
    fun draggingToAnEdgeSnapsToClosingThatPane() {
        assertEquals(0f, snapSplitRatio(0.05f), 0.0001f)
        assertEquals(1f, snapSplitRatio(0.97f), 0.0001f)
    }

    @Test
    fun outOfRangeRatiosAreClampedBeforeSnapping() {
        assertEquals(0f, snapSplitRatio(-5f), 0.0001f)
        assertEquals(1f, snapSplitRatio(5f), 0.0001f)
    }

    @Test
    fun theNarrowerPaneIsReportedAtAnyRatio() {
        // 1920 dp wide, 32 dp divider: 1888 usable.
        assertEquals(944f, minPaneWidth(1920.dp, 0.5f).value, 0.01f)
        assertEquals(944f, minPaneWidth(1920.dp, 0.5f).value, 0.01f)
        assertEquals((1888f / 3f), minPaneWidth(1920.dp, 1f / 3f).value, 0.1f)
        // Symmetric: 2/3 leaves the same minimum (now the secondary pane) as 1/3.
        assertEquals(minPaneWidth(1920.dp, 1f / 3f).value, minPaneWidth(1920.dp, 2f / 3f).value, 0.01f)
    }

    @Test
    fun liveDraggingNeverShrinksAPaneBelowItsFloor() {
        val total = 1000.dp
        // 1000 - 32 = 968 usable; floor fraction = 240 / 968 ≈ 0.248.
        assertEquals(clampDragRatio(total, -1f), clampDragRatio(total, 0f), 0.0001f)
        assertTrue(clampDragRatio(total, 0f) > 0.2f)
        assertTrue(clampDragRatio(total, 1f) < 0.8f)
        // Already inside the safe range: left untouched.
        assertEquals(0.5f, clampDragRatio(total, 0.5f), 0.0001f)
    }

    @Test
    fun theNarrowestSnapRatioStillClearsTheLiveDragFloorAtTheMinimumSplitWidth() {
        // Ties SPLIT_MIN_SCREEN_WIDTH to the live-drag floor: even the thinnest rest point (a
        // third) must stay comfortably above what dragging alone would ever allow.
        assertTrue(minPaneWidth(SPLIT_MIN_SCREEN_WIDTH, 1f / 3f).value > 280f)
    }

    @Test
    fun pointsEitherSideOfTheHandleAreInTheMatchingPane() {
        val total = 1920.dp
        // Divider centre at (1920 - 32) * 0.5 + 16 = 960 dp.
        assertEquals(CarPane.PRIMARY, paneAt(900.dp, total, 0.5f))
        assertEquals(CarPane.PRIMARY, paneAt(959.dp, total, 0.5f))
        assertEquals(CarPane.SECONDARY, paneAt(961.dp, total, 0.5f))
        assertEquals(CarPane.SECONDARY, paneAt(1800.dp, total, 0.5f))
    }
}
