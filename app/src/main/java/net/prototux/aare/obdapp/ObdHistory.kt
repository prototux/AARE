package net.prototux.aare.obdapp

import java.io.File

/**
 * Results kept on the phone (obd_history.tsv): trips and track results, newest last, the latest
 * [MAX] only. A line: time, kind, value, second value.
 * Only readings from a real adapter are kept: the pretend adapter's don't become records.
 */
class ObdHistory(private val file: File) {
    enum class Kind(val lowerIsBetter: Boolean) {
        TRIP(false), // value: km, second: km/L (0 = unknown)
        ZERO_100(true), // s
        KMH_80_120(true), // s
        QUARTER_MILE(true), // s, second: km/h at the line
        BRAKE_100_0(true), // m
        LAP(true), // s
        DYNO(false), // hp at the wheels, second: at rpm
    }

    data class Entry(val timeMs: Long, val kind: Kind, val value: Float, val second: Float = 0f)

    @Synchronized
    fun add(e: Entry) {
        val lines = read().plus(e).takeLast(MAX)
        runCatching { file.writeText(lines.joinToString("") { "${it.timeMs}\t${it.kind.name}\t${it.value}\t${it.second}\n" }) }
    }

    @Synchronized
    fun read(): List<Entry> = runCatching {
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) return@mapNotNull null
            val kind = runCatching { Kind.valueOf(p[1]) }.getOrNull() ?: return@mapNotNull null
            Entry(p[0].toLongOrNull() ?: return@mapNotNull null, kind, p[2].toFloatOrNull() ?: return@mapNotNull null, p[3].toFloatOrNull() ?: 0f)
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun clear() { runCatching { file.delete() } }

    companion object {
        const val MAX = 500

        /** The best of each kind (trips have no best). */
        fun records(entries: List<Entry>): Map<Kind, Entry> = entries.filter { it.kind != Kind.TRIP }.groupBy { it.kind }
            .mapValues { (k, list) -> if (k.lowerIsBetter) list.minBy { it.value } else list.maxBy { it.value } }
    }
}
