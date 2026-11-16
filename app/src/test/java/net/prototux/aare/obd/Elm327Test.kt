package net.prototux.aare.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327Test {
    @Test
    fun formulas() {
        assertEquals(2_000f, Elm327.value(Elm327.RPM, intArrayOf(0x1F, 0x40))!!, 0.01f)
        assertEquals(92f, Elm327.value(Elm327.COOLANT, intArrayOf(132))!!, 0.01f)
        assertEquals(14.1f, Elm327.value(Elm327.BATTERY, intArrayOf(0x37, 0x14))!!, 0.01f)
        assertEquals(100f, Elm327.value(Elm327.FUEL, intArrayOf(255))!!, 0.01f)
        assertNull("a cut rpm answer is no number", Elm327.value(Elm327.RPM, intArrayOf(0x1F)))
    }

    @Test
    fun answersWithNoiseAndTwoModules() {
        val answer = "SEARCHING...\r410C1F40\r410C1F80\r"
        assertEquals(2, Elm327.allAnswers(answer, Elm327.RPM).size)
        assertEquals(0x1F40, Elm327.bytes(answer, Elm327.RPM)!!.let { it[0] * 256 + it[1] })
        assertNull(Elm327.bytes("NODATA", Elm327.RPM))
    }

    @Test
    fun supportedBitmap() {
        // 0x0C and 0x0D: bits 12 and 13 of the first block.
        val pids = Elm327.supported(mapOf(0x00 to "4100" + "%08X".format((1L shl (32 - 0x0C)) or (1L shl (32 - 0x0D)))))
        assertEquals(setOf(0x0C, 0x0D), pids)
    }

    @Test
    fun thePretendCarDrives() {
        var now = 0L
        val link = FakeObdLink(clock = { now }, delayMs = 0)
        val supported = Elm327.prepare(link)
        assertTrue(Elm327.READ.all { it in supported })
        val poller = ObdPoller(link, supported)
        now = 18_000 // cruising at 100 km/h
        val r = poller.readRound()
        assertEquals(100f, r.speed!!, 0.5f)
        assertEquals(2_300f, r.rpm!!, 1f)
        assertEquals(92f, r.coolant!!, 0.5f)
        assertEquals(62f, r.fuel!!, 0.5f)
        assertEquals(14.1f, r.battery!!, 0.01f)
        assertEquals(35f, r.load!!, 0.5f)
        // manifold 95 kPa against 92 kPa outside: a little boost
        assertEquals(0.03f, r.boost!!, 0.005f)
    }

    @Test
    fun anAdapterThatGoesQuietIsDropped() {
        val quiet = object : ObdLink {
            override val name = "quiet"
            override fun send(command: String, waitMs: Long) = ObdLink.NO_ANSWER
            override fun close() = Unit
        }
        val poller = ObdPoller(quiet, setOf(Elm327.RPM, Elm327.SPEED))
        assertThrows(java.io.IOException::class.java) { repeat(10) { poller.readRound() } }
    }
}
