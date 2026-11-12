package net.prototux.aare.devlog

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal object DevLogFiles {
    const val MAX_FILE_BYTES = 5L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    const val MAX_FILES = 100
    private const val MAX_AHEAD_MS = 5_000L

    private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val TAIL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val LOG_NAME = Regex("""aare-\d{8}-\d{6}(-raw)?\.log""")
    private val LINE_START = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\b.*""")

    fun fileName(epochMs: Long, zone: ZoneId, raw: Boolean): String {
        val stamp = NAME_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))
        return "aare-$stamp${if (raw) "-raw" else ""}.log"
    }

    /** [fileName], a second later each time its raw or filtered form is [taken], so names sort in time. */
    fun newFileName(epochMs: Long, zone: ZoneId, raw: Boolean, taken: (String) -> Boolean): String {
        var t = epochMs
        while (taken(fileName(t, zone, true)) || taken(fileName(t, zone, false))) t += 1000
        return fileName(t, zone, raw)
    }

    fun isLog(name: String): Boolean = LOG_NAME.matches(name)

    fun isRaw(name: String): Boolean = isLog(name) && name.endsWith("-raw.log")

    /** The oldest files to delete before a new one is opened, so that with it at most [maxFiles] remain. */
    fun toDelete(
        files: List<Pair<String, Long>>,
        keepAtMost: Long = MAX_TOTAL_BYTES - MAX_FILE_BYTES,
        maxFiles: Int = MAX_FILES,
    ): List<String> {
        val logs = files.filter { isLog(it.first) }.sortedBy { it.first }
        var total = logs.sumOf { it.second }
        var count = logs.size
        val doomed = mutableListOf<String>()
        for ((name, size) in logs) {
            if (total <= keepAtMost && count < maxFiles) break
            doomed += name
            total -= size
            count--
        }
        return doomed
    }

    /**
     * The logcat `-T` value for a line written with `-v threadtime -v year`, year included (logcat
     * reads `MM-DD …` in the current year). It is local time: a resume across a DST change may skip or
     * repeat up to an hour.
     */
    fun tailArg(line: String): String? = LINE_START.matchEntire(line)?.groupValues?.get(1)

    enum class Resume { FROM_TAIL, AHEAD, UNREADABLE }

    /**
     * Whether logcat may resume after [tail]: logd skips every line up to it, so a tail [AHEAD][Resume.AHEAD]
     * of the clock (by more than 5 s) would hide the lines until then.
     */
    fun resume(tail: String, nowMs: Long, zone: ZoneId): Resume {
        val at = try {
            LocalDateTime.parse(tail, TAIL_FORMAT).atZone(zone).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            return Resume.UNREADABLE
        }
        return if (at <= nowMs + MAX_AHEAD_MS) Resume.FROM_TAIL else Resume.AHEAD
    }
}
