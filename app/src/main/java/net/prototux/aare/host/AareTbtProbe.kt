package net.prototux.aare.host

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import net.prototux.aare.BuildConfig
import net.prototux.aare.Settings
import net.prototux.aare.DiagLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Optional, debug-only navigation diagnostics for the AndroidX Car App host.
 *
 * Observes app -> AARE INavigationHost.updateTrip (not phone pixels or private APK internals).
 * Raw AndroidX enums / distance display-units are preserved: never assume they are KNRGCode,
 * NAVER raw TBT codes, or metres. No coordinates, keys, destination names or bitmap pixels.
 *
 * Explicitly enable in Advanced settings on a DEBUG APK. Full route text stays private to the app.
 * Stored JSONL can be retrieved via
 * "adb exec-out run-as net.prototux.aare cat files/aare-tbt-probe.jsonl".
 * WARNING: records may include road and cue text; never attach them unredacted to issues.
 */
internal object AareTbtProbe {
    private const val TAG = "AareTbt"
    private const val MAX_BYTES = 1_048_576L
    private val sequence = AtomicLong(0)
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aare-tbt-writer").apply { isDaemon = true }
    }
    private var lastSummaryKey = ""
    private var lastSummaryAt = 0L
    private var lastRoutingSummaryKey = ""
    private var lastRoutingSummaryAt = 0L

    @Synchronized
    fun lifecycle(context: Context, packageName: String, event: String) {
        if (!enabled(context)) return
        val row = base(packageName, event)
        emit(context, row)
        lastSummaryKey = ""
        lastRoutingSummaryKey = ""
        DiagLog.add("TBT $packageName $event (AareTbt / private JSONL)")
    }

    @Synchronized
    fun trip(context: Context, packageName: String, trip: Trip) {
        if (!enabled(context)) return
        runCatching {
            val row = base(packageName, "trip")
            row.put("loading", trip.isLoading)
            row.put("currentRoad", text(trip.currentRoad))
            val travel = trip.stepTravelEstimates
            val steps = JSONArray()
            for ((index, step) in trip.steps.take(2).withIndex()) {
                val stepJson = JSONObject()
                stepJson.put("index", index)
                stepJson.put("cue", text(step.cue))
                stepJson.put("road", text(step.road))
                stepJson.put("maneuverType", step.maneuver?.type ?: JSONObject.NULL)
                stepJson.put("maneuverIconPresent", step.maneuver?.icon != null)
                stepJson.put("lanesImagePresent", step.lanesImage != null)
                stepJson.put("estimate", estimate(travel.getOrNull(index)))
                val lanes = JSONArray()
                for (lane in step.lanes.take(8)) {
                    val directions = JSONArray()
                    for (direction in lane.directions.take(5)) {
                        directions.put(JSONObject()
                            .put("shape", direction.shape)
                            .put("recommended", direction.isRecommended))
                    }
                    lanes.put(directions)
                }
                stepJson.put("lanes", lanes)
                stepJson.put("laneCount", step.lanes.size)
                steps.put(stepJson)
            }
            row.put("steps", steps)
            row.put("stepCount", trip.steps.size)
            row.put("destinationEstimate", estimate(trip.destinationTravelEstimates.firstOrNull()))
            emit(context, row)

            // Advanced -> Connection log: compact, throttled summary for phone-only diagnosis.
            // Full per-update records are in the private JSONL and the AareTbt logcat tag.
            val first = trip.steps.firstOrNull()
            val firstEstimate = travel.firstOrNull()?.remainingDistance
            val code = first?.maneuver?.type?.toString() ?: "unknown"
            val dist = if (firstEstimate == null) "unknown" else
                "${firstEstimate.displayDistance}/unit${firstEstimate.displayUnit}"
            val now = SystemClock.elapsedRealtime()
            val key = "$packageName:$code:${first?.lanes?.size ?: 0}"
            if (key != lastSummaryKey || now - lastSummaryAt >= 5_000L) {
                lastSummaryKey = key
                lastSummaryAt = now
                DiagLog.add("TBT $packageName code=$code dist=$dist lanes=${first?.lanes?.size ?: 0}")
            }
        }.onFailure {
            Log.w(TAG, "probe error in $packageName: ${it.javaClass.simpleName}")
        }
    }

    /**
     * Observe the very NavigationTemplate that AARE is about to render.
     *
     * Navigation apps are free to send RoutingInfo without updateTrip(), or update the
     * two at different frequencies. Keep this stream independent from the Trip observer.
     * Do not scrape an encoded display, audio stream or navigation app's map Surface.
     */
    @Synchronized
    fun routing(context: Context, packageName: String, template: NavigationTemplate) {
        if (!enabled(context)) return
        runCatching {
            val info = template.navigationInfo as? RoutingInfo ?: return
            val row = base(packageName, "routing_template")
            row.put("isLoading", info.isLoading)
            row.put("currentDistance", info.currentDistance?.let { d ->
                JSONObject()
                    .put("displayDistance", d.displayDistance)
                    .put("displayUnit", d.displayUnit)
            } ?: JSONObject.NULL)
            row.put("currentStep", routingStep(info.currentStep))
            row.put("nextStep", routingStep(info.nextStep))
            row.put("junctionImagePresent", info.junctionImage != null)
            row.put("destinationEstimate", estimate(template.destinationTravelEstimate))
            emit(context, row)

            val first = info.currentStep
            val code = first?.maneuver?.type?.toString() ?: "unknown"
            val current = info.currentDistance
            val dist = if (current == null) "unknown" else
                "${current.displayDistance}/unit${current.displayUnit}"
            val now = SystemClock.elapsedRealtime()
            val key = "$packageName:$code:${first?.lanes?.size ?: 0}:${info.junctionImage != null}"
            if (key != lastRoutingSummaryKey || now - lastRoutingSummaryAt >= 5_000L) {
                lastRoutingSummaryKey = key
                lastRoutingSummaryAt = now
                DiagLog.add("TBT TEMPLATE $packageName code=$code dist=$dist lanes=${first?.lanes?.size ?: 0}")
            }
        }.onFailure {
            Log.w(TAG, "routing template probe error in $packageName: ${it.javaClass.simpleName}")
        }
    }

    private fun routingStep(step: Step?): Any {
        if (step == null) return JSONObject.NULL
        val lanes = JSONArray()
        for (lane in step.lanes.take(8)) {
            val directions = JSONArray()
            for (dir in lane.directions.take(5)) {
                directions.put(JSONObject()
                    .put("shape", dir.shape)
                    .put("recommended", dir.isRecommended))
            }
            lanes.put(directions)
        }
        return JSONObject()
            .put("cue", text(step.cue))
            .put("road", text(step.road))
            .put("maneuverType", step.maneuver?.type ?: JSONObject.NULL)
            .put("maneuverIconPresent", step.maneuver?.icon != null)
            .put("lanesImagePresent", step.lanesImage != null)
            .put("laneCount", step.lanes.size)
            .put("lanes", lanes)
    }

    private fun enabled(context: Context): Boolean =
        BuildConfig.DEBUG && Settings(context.applicationContext).navigationDiagnostics

    private fun base(packageName: String, event: String): JSONObject = JSONObject()
        .put("schema", "aare-tbt/1")
        .put("event", event)
        .put("package", packageName)
        .put("seq", sequence.incrementAndGet())
        .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        .put("wallMs", System.currentTimeMillis())

    private fun text(value: Any?): String? = value?.toString()?.take(96)

    private fun estimate(value: TravelEstimate?): Any {
        if (value == null) return JSONObject.NULL
        val distance = value.remainingDistance
        return JSONObject()
            .put("displayDistance", distance?.displayDistance)
            .put("displayUnit", distance?.displayUnit)
            .put("remainingTimeSec", value.remainingTimeSeconds)
    }

    private fun emit(context: Context, row: JSONObject) {
        val json = row.toString()
        // Do not put road names or navigation text into logcat, even with diagnostics enabled.
        // The full JSONL is kept only in the app-private, size-limited file.
        val appContext = context.applicationContext
        writer.execute {
            runCatching {
                val target = File(appContext.filesDir, "aare-tbt-probe.jsonl")
                if (target.exists() && target.length() >= MAX_BYTES) {
                    val previous = File(appContext.filesDir, "aare-tbt-probe.prev.jsonl")
                    if (previous.exists()) previous.delete()
                    if (!target.renameTo(previous)) target.writeText("")
                }
                target.appendText(json + "\n", Charsets.UTF_8)
            }.onFailure {
                Log.w(TAG, "private log write failed: ${it.javaClass.simpleName}")
            }
        }
    }
}
