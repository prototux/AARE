package net.prototux.aare.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunsWhileDrivingTest {
    @Test
    fun navigationAppsRunOn() {
        assertTrue(runsWhileDriving("com.google.android.apps.maps"))
        assertTrue(runsWhileDriving("com.waze"))
    }

    @Test
    fun otherAppsPause() {
        assertFalse(runsWhileDriving("org.wisso.newpipematerial"))
        assertFalse(runsWhileDriving("com.anthropic.claude"))
    }
}
