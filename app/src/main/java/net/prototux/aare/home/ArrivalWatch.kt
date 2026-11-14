package net.prototux.aare.home

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides when the car arrives at a place: when it comes within the place's radius after having
 * been seen clearly outside it during this drive. Two radii (enter at the radius, leave beyond
 * [exitRadius]) keep a car parked at the edge, or a jumpy fix, from firing again and again; and a
 * drive that starts inside the place (leaving home) doesn't count as an arrival.
 */
class ArrivalWatch {
    /** Per place id: inside at the last fix (true), clearly outside (false), or not known yet (absent). */
    private val inside = HashMap<String, Boolean>()

    /**
     * The ids of the places just arrived at, given a fix at [latitude], [longitude] with
     * [accuracyM] (null: unknown) and the [places] to watch.
     */
    fun update(latitude: Double, longitude: Double, accuracyM: Float?, places: Map<String, Arrival>): List<String> {
        inside.keys.retainAll(places.keys)
        val arrived = ArrayList<String>()
        for ((id, place) in places) {
            // A fix vaguer than the place itself can't tell inside from outside.
            if (accuracyM != null && accuracyM > max(place.radiusM.toFloat(), MAX_ACCURACY_M)) continue
            val d = distanceM(latitude, longitude, place.latitude, place.longitude)
            when {
                d <= place.radiusM -> {
                    if (inside[id] == false) arrived += id
                    inside[id] = true
                }
                d > exitRadius(place.radiusM) -> inside[id] = false
                // Between the two radii: nothing changes.
            }
        }
        return arrived
    }

    fun reset() = inside.clear()

    companion object {
        /** Fixes vaguer than this (or than the radius, if bigger) are skipped. */
        const val MAX_ACCURACY_M = 100f

        /** How far the car must go before it can arrive again: half the radius more, at least 100 m. */
        fun exitRadius(radiusM: Int): Double = max(radiusM * 1.5, radiusM + 100.0)

        /** Great-circle distance in metres. */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
