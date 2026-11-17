package net.prototux.aare.ui.car

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SideRailFitTest {
    @Test
    fun tallRailShowsEverythingAndARecentApp() {
        assertEquals(SideRailFit(recentSlots = 1, status = true, settings = true), sideRailFit(602.dp))
        assertEquals(SideRailFit(recentSlots = 1, status = true, settings = true), sideRailFit(768.dp))
    }

    @Test
    fun mediumRailKeepsEverythingButTheRecentApp() {
        assertEquals(SideRailFit(recentSlots = 0, status = true, settings = true), sideRailFit(580.dp))
    }

    /** 1280x720 at 213 dpi: about 540 dp tall, 516 inside the padding (issue #68's size class). */
    @Test
    fun shortRailDropsStatusThenSettings() {
        assertEquals(SideRailFit(recentSlots = 0, status = false, settings = true), sideRailFit(516.dp))
        assertEquals(SideRailFit(recentSlots = 0, status = false, settings = false), sideRailFit(470.dp))
        assertEquals(SideRailFit(recentSlots = 0, status = false, settings = false), sideRailFit(300.dp))
    }
}
