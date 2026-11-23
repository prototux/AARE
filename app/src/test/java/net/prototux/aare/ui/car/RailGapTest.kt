package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Test

class RailGapTest {
    @Test
    fun aCompactRailIsTighterAndABottomBarWider() {
        assertEquals(8, railGapDp(compact = false, bottom = false))
        assertEquals(12, railGapDp(compact = false, bottom = true))
        assertEquals(6, railGapDp(compact = true, bottom = false))
        assertEquals(10, railGapDp(compact = true, bottom = true))
    }
}
