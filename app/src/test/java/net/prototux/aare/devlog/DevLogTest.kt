package net.prototux.aare.devlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DevLogTest {
    private val minute = 60_000L

    @Test fun filterAvailableHere() = assertNull(DevLog.filterUnavailable())

    @Test fun filterUnavailableCatchesErrors() {
        val e = ExceptionInInitializerError("pattern rejected")
        assertSame(e, DevLog.filterUnavailable { throw e })
    }

    @Test fun budgetRefusesTheSixthRestartWithinTenMinutes() {
        val budget = RestartBudget()
        for (i in 0 until 5) assertTrue("restart $i", budget.allow(i * 1000L))
        assertFalse(budget.allow(5000))
        assertFalse(budget.allow(9 * minute))
    }

    @Test fun budgetAllowsAgainOnceTheOldestRestartLeavesTheWindow() {
        val budget = RestartBudget()
        for (i in 0 until 5) budget.allow(i * 1000L)
        assertTrue(budget.allow(10 * minute))
        assertFalse(budget.allow(10 * minute + 500))
        assertTrue(budget.allow(10 * minute + 1000))
    }

    @Test fun refusedRestartsDoNotCount() {
        val budget = RestartBudget()
        for (i in 0 until 5) budget.allow(0)
        repeat(10) { assertFalse(budget.allow(minute)) }
        assertTrue(budget.allow(10 * minute))
    }

    private val a = "2026-11-12 10:00:00.250  5443  5443 I Aare: a"
    private val b = "2026-11-12 10:00:01.500  5443  5460 D AareAap: b"

    @Test fun lastTailArgTakesTheLastTimestampedLine() =
        assertEquals("2026-11-12 10:00:01.500", DevLog.lastTailArg("# AARE 1.0.0 (10000)\n$a\n$b\n", wholeFile = true))

    @Test fun lastTailArgSkipsLinesWithoutATimestamp() {
        assertEquals("2026-11-12 10:00:01.500", DevLog.lastTailArg("$a\n$b\n--------- beginning of crash\n", wholeFile = true))
        assertEquals("2026-11-12 10:00:01.500", DevLog.lastTailArg("$a\n$b\n2026-11-1", wholeFile = true))
    }

    @Test fun lastTailArgOfAHeaderOnlyFileIsNull() =
        assertNull(DevLog.lastTailArg("# AARE 1.0.0 (10000)\n# privacy filter: on\n", wholeFile = true))

    private val car500 = LogRedactor(mapOf("500" to "<name>"))

    @Test fun redactLineKeepsTheLogcatPrefix() = assertEquals(
        "2026-11-12 10:00:00.500   500   500 I Aare: connected to <name>",
        DevLog.redactLine("2026-11-12 10:00:00.500   500   500 I Aare: connected to 500", car500::redact),
    )

    @Test fun redactLineWithoutPrefixRedactsItAll() = assertEquals(
        "--------- beginning of <name>",
        DevLog.redactLine("--------- beginning of 500", car500::redact),
    )

    @Test fun redactLineFailsClosed() {
        val failures = mutableListOf<Exception>()
        assertEquals(
            "2026-11-12 10:00:00.250  5443  5443 I <line not written: redaction failed>",
            DevLog.redactLine("2026-11-12 10:00:00.250  5443  5443 I Aare: secret", { throw IllegalStateException() }) { failures += it },
        )
        assertEquals(1, failures.size)
    }

    @Test fun lastTailArgDropsTheFirstLineOfAChunkCutMidLine() {
        assertNull(DevLog.lastTailArg("$a\n", wholeFile = false))
        assertEquals("2026-11-12 10:00:00.250", DevLog.lastTailArg("$a\n", wholeFile = true))
        assertEquals("2026-11-12 10:00:01.500", DevLog.lastTailArg("$a\n$b\n", wholeFile = false))
    }
}
