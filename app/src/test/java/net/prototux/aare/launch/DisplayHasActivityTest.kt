package net.prototux.aare.launch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayHasActivityTest {
    private val dump = """
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  * Task{1 #1 type=standard A=10159:com.example.launcher U=0 visible=true}
    * Hist  #0: ActivityRecord{2 u0 com.example.launcher/.Home t1}
Display #12 (activities from top to bottom):
  * Task{3 #2 type=standard A=10200:com.example.player U=0 visible=true}
    * Hist  #0: ActivityRecord{4 u0 com.example.player/.Player t2}
Display #13 (activities from top to bottom):
"""

    @Test
    fun aDisplayWithThePlayerHasAnApp() {
        assertTrue(displayHasActivity(dump, 12))
    }

    @Test
    fun anEmptyOrUnknownDisplayHasNone() {
        // Back closed the player on its first screen: the picture would stay empty.
        assertFalse(displayHasActivity(dump, 13))
        assertFalse(displayHasActivity(dump, 1))
    }
}
