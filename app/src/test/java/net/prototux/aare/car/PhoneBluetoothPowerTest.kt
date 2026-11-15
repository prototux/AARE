package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneBluetoothPowerTest {
    private val java = File(System.getProperty("java.home"), "bin/java").path

    @Test fun aCommandThatSucceedsReportsNothing() = assertNull(PhoneBluetoothPower.run(listOf(java, "-version")))

    @Test fun aCommandThatFailsReportsItsOutput() =
        assertNotNull(PhoneBluetoothPower.run(listOf(java, "-no-such-option")))

    @Test fun aCommandThatCantStartSaysWhy() =
        assertTrue(PhoneBluetoothPower.run(listOf("aare-no-such-command")).orEmpty().isNotBlank())

    @Test fun theCommandCoversAndroid12And13Plus() =
        assertEquals("cmd bluetooth_manager enable || svc bluetooth enable", PhoneBluetoothPower.ENABLE_COMMAND)
}
