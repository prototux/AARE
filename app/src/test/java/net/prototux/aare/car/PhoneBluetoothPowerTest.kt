package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun turnsBluetoothOnByItselfAtMostThreeTimesInFiveMinutes() {
        val times = ArrayList<Long>()
        repeat(PhoneBluetoothPower.MAX_AUTO) { i ->
            assertTrue(PhoneBluetoothPower.mayTurnOnAgain(times, 1_000L * i))
            times += 1_000L * i
        }
        assertFalse(PhoneBluetoothPower.mayTurnOnAgain(times, 10_000))
        // Once the first attempts are older than the window, it may again.
        assertTrue(PhoneBluetoothPower.mayTurnOnAgain(times, PhoneBluetoothPower.AUTO_WINDOW_MS + 2_001))
    }

    @Test fun theCommandCoversAndroid12And13Plus() =
        assertEquals("cmd bluetooth_manager enable || svc bluetooth enable", PhoneBluetoothPower.ENABLE_COMMAND)
}
