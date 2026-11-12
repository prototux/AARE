package net.prototux.aare.devlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class DevLogFilesTest {
    private val mb = 1024L * 1024

    @Test fun fileNameEpochZero() =
        assertEquals("aare-19700101-000000.log", DevLogFiles.fileName(0, ZoneOffset.UTC, false))

    @Test fun fileNameRawSuffix() =
        assertEquals("aare-20251112-180924-raw.log", DevLogFiles.fileName(1_762_970_964_000, ZoneOffset.UTC, true))

    @Test fun fileNameUsesTheGivenZone() =
        assertEquals("aare-20251112-190924.log", DevLogFiles.fileName(1_762_970_964_000, ZoneOffset.ofHours(1), false))

    @Test fun isRawAndIsLog() {
        assertTrue(DevLogFiles.isLog("aare-20251112-180924.log"))
        assertTrue(DevLogFiles.isLog("aare-20251112-180924-raw.log"))
        assertFalse(DevLogFiles.isLog("connection.log"))
        assertFalse(DevLogFiles.isLog("aare-20251112-180924.log.tmp"))
        assertFalse(DevLogFiles.isLog("aare-2025-180924.log"))
        assertTrue(DevLogFiles.isRaw("aare-20251112-180924-raw.log"))
        assertFalse(DevLogFiles.isRaw("aare-20251112-180924.log"))
    }

    @Test fun deletesTheOldestWhenOverBudget() {
        val files = listOf(
            "aare-20251112-120000.log" to 10 * mb,
            "aare-20251110-120000.log" to 20 * mb,
            "aare-20251111-120000.log" to 20 * mb,
        )
        assertEquals(listOf("aare-20251110-120000.log"), DevLogFiles.toDelete(files))
    }

    @Test fun deletesNothingUnderBudget() {
        assertEquals(emptyList<String>(), DevLogFiles.toDelete(listOf("aare-20251112-120000.log" to 10 * mb)))
        assertEquals(emptyList<String>(), DevLogFiles.toDelete(emptyList()))
    }

    @Test fun ignoresNonLogNames() {
        val files = listOf("connection.log" to 100 * mb, "aare-20251112-120000.log" to 1 * mb)
        assertEquals(emptyList<String>(), DevLogFiles.toDelete(files))
    }

    @Test fun tailArgKeepsTheYear() =
        assertEquals("2026-11-12 18:09:24.123", DevLogFiles.tailArg("2026-11-12 18:09:24.123  5443  5443 I Aare: x"))

    @Test fun keepsAtMostMaxFilesWithTheNewOne() {
        val files = (1..4).map { "aare-20251112-12000$it.log" to 1L }
        assertEquals(listOf("aare-20251112-120001.log", "aare-20251112-120002.log"), DevLogFiles.toDelete(files, maxFiles = 3))
        assertEquals(emptyList<String>(), DevLogFiles.toDelete(files.take(2), maxFiles = 3))
    }

    @Test fun defaultFileCapIsOneHundred() {
        val files = (0 until 100).map { "aare-20251112-12%04d.log".format(it) to 1L }
        assertEquals(listOf("aare-20251112-120000.log"), DevLogFiles.toDelete(files))
    }

    @Test fun newFileNameIsFreeInBothForms() {
        val taken = setOf("aare-19700101-000000-raw.log", "aare-19700101-000001.log")
        assertEquals("aare-19700101-000002.log", DevLogFiles.newFileName(0, ZoneOffset.UTC, false) { it in taken })
        assertEquals("aare-19700101-000002-raw.log", DevLogFiles.newFileName(0, ZoneOffset.UTC, true) { it in taken })
        assertEquals("aare-19700101-000000.log", DevLogFiles.newFileName(0, ZoneOffset.UTC, false) { false })
    }

    private val tail = "2026-11-12 18:09:24.123"
    private val tailMs = 1_794_506_964_123 // 2026-11-12 18:09:24.123 UTC

    @Test fun resumesUpToFiveSecondsAhead() {
        assertEquals(DevLogFiles.Resume.FROM_TAIL, DevLogFiles.resume(tail, tailMs + 60_000, ZoneOffset.UTC))
        assertEquals(DevLogFiles.Resume.FROM_TAIL, DevLogFiles.resume(tail, tailMs - 5_000, ZoneOffset.UTC))
        assertEquals(DevLogFiles.Resume.AHEAD, DevLogFiles.resume(tail, tailMs - 5_001, ZoneOffset.UTC))
    }

    @Test fun resumeReadsTheTimeInTheGivenZone() {
        assertEquals(DevLogFiles.Resume.AHEAD, DevLogFiles.resume(tail, tailMs, ZoneOffset.ofHours(-1)))
        assertEquals(DevLogFiles.Resume.FROM_TAIL, DevLogFiles.resume(tail, tailMs, ZoneOffset.ofHours(1)))
    }

    @Test fun unparsableTailIsNeitherResumedNorAhead() =
        assertEquals(DevLogFiles.Resume.UNREADABLE, DevLogFiles.resume("2026-13-40 18:09:24.123", tailMs, ZoneOffset.UTC))

    @Test fun tailArgRejectsHeadersAndGarbage() {
        assertNull(DevLogFiles.tailArg("# AARE 1.4.0"))
        assertNull(DevLogFiles.tailArg("garbage"))
        assertNull(DevLogFiles.tailArg(""))
    }
}
