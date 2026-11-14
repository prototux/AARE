package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardOrderTest {
    @Test
    fun defaultKeepsTheMapFirst() {
        assertEquals(listOf("map", "media"), dashboardOrder(false, "map", "media"))
    }

    @Test
    fun mediaFirstPutsTheMusicFirst() {
        assertEquals(listOf("media", "map"), dashboardOrder(true, "map", "media"))
    }
}
