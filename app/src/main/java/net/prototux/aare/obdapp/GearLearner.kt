package net.prototux.aare.obdapp

import kotlin.math.abs

/**
 * Works out the gear from speed and revs, for any car: in a gear, km/h per 1000 rpm is fixed. While
 * driving steadily it gathers those ratios into groups; each group seen often enough is a gear,
 * numbered from the shortest. Nothing to set up, and it keeps what it learned between drives.
 * Clutch down, neutral or an automatic's converter slipping match no group: no gear shown.
 */
class GearLearner(saved: String = "") {
    private class Group(var ratio: Float, var count: Int)

    private val groups = ArrayList<Group>()
    private var last: Float? = null

    init {
        saved.split(';').mapNotNull { it.split(':').takeIf { p -> p.size == 2 } }.forEach { (r, c) ->
            val ratio = r.toFloatOrNull(); val count = c.toIntOrNull()
            if (ratio != null && count != null && ratio > 0f) groups += Group(ratio, count)
        }
        // Saved by an older version, groups may sit too close: join them.
        for (g in groups.sortedByDescending { it.count }) if (g in groups) mergeInto(g)
    }

    /**
     * The gears found, shortest first (km/h per 1000 rpm). First gear is rarely steady (an
     * automatic's converter slips at low speed), so the shortest group counts with fewer readings:
     * without it, second gear would be numbered 1.
     */
    val gears: List<Float> get() {
        val sure = groups.filter { it.count >= MIN_SAMPLES }
        val shortest = groups.filter { it.count >= MIN_FIRST }.minByOrNull { it.ratio }
        val first = shortest?.takeIf { s -> sure.all { s.ratio < it.ratio * (1 - MATCH) } && sure.isNotEmpty() && sure.minOf { it.ratio } / s.ratio >= MIN_STEP }
        return (sure + listOfNotNull(first)).map { it.ratio }.sorted()
    }

    /** Feeds one reading; returns the gear now (1 = shortest) or null. */
    fun feed(kmh: Float?, rpm: Float?): Int? {
        if (kmh == null || rpm == null || kmh < 8f || rpm < 900f) { last = null; return null }
        val ratio = kmh / rpm * 1000f
        val previous = last
        last = ratio
        // Learn only when it held steady since the last reading (not during a shift or a slip).
        if (previous != null && abs(ratio - previous) / ratio < STEADY) learn(ratio)
        return gearOf(ratio)
    }

    fun gearOf(ratio: Float): Int? {
        val g = gears
        val i = g.indexOfFirst { abs(it - ratio) / it < MATCH }
        return if (i < 0) null else i + 1
    }

    private fun learn(ratio: Float) {
        val near = groups.minByOrNull { abs(it.ratio - ratio) }
        if (near != null && abs(near.ratio - ratio) / near.ratio < MATCH) {
            if (near.count < CAP) near.count++
            near.ratio += (ratio - near.ratio) / near.count
            mergeInto(near)
            return
        }
        groups += Group(ratio, 1)
        if (groups.size > MAX_GROUPS) groups.remove(groups.minBy { it.count })
    }

    /** Two groups that drifted close together are one gear: join them (weighted by their readings). */
    private fun mergeInto(g: Group) {
        val close = groups.filter { it !== g && abs(it.ratio - g.ratio) / g.ratio < MATCH }
        for (o in close) {
            val n = g.count + o.count
            g.ratio = (g.ratio * g.count + o.ratio * o.count) / n
            g.count = minOf(n, CAP)
            groups.remove(o)
        }
    }

    fun reset() { groups.clear(); last = null }

    fun save(): String = groups.filter { it.count >= 3 }.joinToString(";") { "%.3f:%d".format(java.util.Locale.US, it.ratio, it.count) }

    companion object {
        /** Two readings this close (share) count as steady. */
        const val STEADY = 0.02f
        /** A ratio this close (share) to a group belongs to it. Gears are at least ~20% apart. */
        const val MATCH = 0.06f
        const val MIN_SAMPLES = 25
        /** The shortest group (first gear) counts from this many readings… */
        const val MIN_FIRST = 8
        /** …if it's clearly shorter than the next gear (real gears are 30% or more apart). */
        const val MIN_STEP = 1.3f
        /** Keeps an old group from freezing: new readings still move it a little. */
        const val CAP = 500
        const val MAX_GROUPS = 16
    }
}
