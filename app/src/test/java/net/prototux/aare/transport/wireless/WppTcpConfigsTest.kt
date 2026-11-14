package net.prototux.aare.transport.wireless

import net.prototux.aare.aap.wireless.WppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a head unit whose WPP over TCP failed gets another try. */
class WppTcpConfigsTest {
    @Test
    fun aFailureBlocksTcpOnlyAtTheVersionItHappenedAt() {
        assertTrue("never failed", WppTcpConfigs.tcpAllowed(null, WppVersion(4, 5)))
        assertFalse(WppTcpConfigs.tcpAllowed(WppVersion(4, 5), WppVersion(4, 5)))
        assertTrue("the head unit was updated", WppTcpConfigs.tcpAllowed(WppVersion(4, 2), WppVersion(4, 5)))
        assertTrue(WppTcpConfigs.tcpAllowed(WppVersion(4, 5), WppVersion(5, 0)))
    }

    @Test
    fun storedVersionsReadBack() {
        assertEquals(WppVersion(4, 5), WppTcpConfigs.parseVersion(WppVersion(4, 5).toString()))
        assertEquals(WppVersion(0, 0), WppTcpConfigs.parseVersion("0.0"))
        assertNull(WppTcpConfigs.parseVersion("4"))
        assertNull(WppTcpConfigs.parseVersion("4.x"))
        assertNull(WppTcpConfigs.parseVersion(""))
    }
}
