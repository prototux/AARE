package net.prototux.aare.transport.wireless

import net.prototux.aare.aap.wireless.WppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a head unit whose WPP over TCP failed gets another try. */
class WppTcpConfigsTest {
    private val v45 = WppVersion(4, 5)

    @Test
    fun oneSlowJoinDoesNotTurnTcpOff() {
        assertTrue("never failed", WppTcpConfigs.tcpAllowed(null, v45))
        val once = WppTcpConfigs.nextFailures(null, v45)
        assertEquals(WppTcpConfigs.Failures(v45, 1), once)
        assertTrue("one failure", WppTcpConfigs.tcpAllowed(once, v45))
        assertTrue("two in a row", WppTcpConfigs.tcpAllowed(WppTcpConfigs.nextFailures(once, v45), v45))
    }

    @Test
    fun failuresInARowTurnTcpOffAtThatVersionOnly() {
        var failures: WppTcpConfigs.Failures? = null
        repeat(WppTcpConfigs.MAX_FAILURES) { failures = WppTcpConfigs.nextFailures(failures, v45) }
        assertFalse(WppTcpConfigs.tcpAllowed(failures, v45))
        assertTrue("the head unit was updated", WppTcpConfigs.tcpAllowed(failures, WppVersion(5, 0)))
        assertEquals("another version starts again", WppTcpConfigs.Failures(WppVersion(5, 0), 1),
            WppTcpConfigs.nextFailures(failures, WppVersion(5, 0)))
    }

    @Test
    fun storedFailuresReadBack() {
        assertEquals(WppTcpConfigs.Failures(v45, 2), WppTcpConfigs.parseFailures("4.5/2"))
        assertNull(WppTcpConfigs.parseFailures("4.5"))
        assertNull(WppTcpConfigs.parseFailures("4.5/0"))
        assertNull(WppTcpConfigs.parseFailures("4.5/x"))
        assertNull(WppTcpConfigs.parseFailures("x/1"))
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
