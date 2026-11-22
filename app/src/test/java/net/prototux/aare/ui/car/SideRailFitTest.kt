package net.prototux.aare.ui.car

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SideRailFitTest {
    @Test
    fun tallRailShowsEverythingAndARecentApp() {
        assertEquals(SideRailFit(recentSlots = 1, status = true, settings = true), sideRailFit(768.dp))
    }

    @Test
    fun mediumRailKeepsEverythingButTheRecentApp() {
        assertEquals(SideRailFit(recentSlots = 0, status = true, settings = true), sideRailFit(700.dp))
    }

    /** 1280x720 at 213 dpi: about 540 dp tall, 508 inside the padding (issue #68's size class). */
    @Test
    fun shortRailDropsStatusThenSettings() {
        assertEquals(SideRailFit(recentSlots = 0, status = false, settings = true), sideRailFit(600.dp))
        assertEquals(SideRailFit(recentSlots = 0, status = false, settings = false), sideRailFit(508.dp))
        assertEquals(SideRailFit(recentSlots = 0, status = false, settings = false, phone = false), sideRailFit(300.dp))
    }

    /** #269: an 800x480 unit leaves 448 dp; navigation, media and Phone need 476, so Phone goes. */
    @Test
    fun phoneLeavesOnA480dpScreen() {
        assertEquals(SideRailFit(recentSlots = 0, status = false, settings = false, phone = false), sideRailFit(448.dp))
        assertEquals(true, sideRailFit(476.dp).phone)
    }
}
