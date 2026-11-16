package net.prototux.aare.car

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One car's overrides, from vehicles.json: the projection layout, and quirks of how its head unit
 * speaks the protocol. All fields are optional; unset ones keep AARE's default. See docs/vehicles.md.
 */
data class VehicleOverride(
    val dpi: Int? = null,
    val widthMargin: Int? = null,
    val heightMargin: Int? = null,
    val maxHeight: Int? = null,
    val prefer60fps: Boolean? = null,
    /** Services AARE would open that stay closed, by kind (see [matchesKind]). */
    val skipServices: Set<String>? = null,
    /** Services the head unit lists that AARE wouldn't open, opened anyway (nothing is sent on them). */
    val openServices: Set<String>? = null,
    /** Whether to send SETUP on the microphone channel when it opens (AARE's default: yes). */
    val micSetup: Boolean? = null,
) {
    /** These overrides, with [base]'s for every field left unset: a user rule over a built-in one. */
    fun over(base: VehicleOverride?): VehicleOverride = if (base == null) this else VehicleOverride(
        dpi = dpi ?: base.dpi,
        widthMargin = widthMargin ?: base.widthMargin,
        heightMargin = heightMargin ?: base.heightMargin,
        maxHeight = maxHeight ?: base.maxHeight,
        prefer60fps = prefer60fps ?: base.prefer60fps,
        skipServices = skipServices ?: base.skipServices,
        openServices = openServices ?: base.openServices,
        micSetup = micSetup ?: base.micSetup,
    )

    fun skips(kind: String): Boolean = skipServices.orEmpty().any { matchesKind(it, kind) }
    fun opens(kind: String): Boolean = openServices.orEmpty().any { matchesKind(it, kind) }

    companion object {
        /**
         * Whether [pattern] names a service of [kind], as the connection log writes them
         * (`AapSession.serviceKind`: "wifi", "vendor", "mic", "audio:AUDIO_STREAM_MEDIA"…).
         * A pattern without a colon also names every service of that family: "audio" is every
         * audio sink.
         */
        fun matchesKind(pattern: String, kind: String): Boolean =
            pattern.equals(kind, ignoreCase = true) ||
                (':' !in pattern && kind.startsWith("$pattern:", ignoreCase = true))
    }
}

/** A rule: match a head unit by identity substrings, then apply [override]. */
data class VehicleRule(
    val make: String?,
    val model: String?,
    val name: String?,
    val override: VehicleOverride,
) {
    /** Every set match field must be a case-insensitive substring; a rule with no match field never matches. */
    fun matches(make: String, model: String, name: String): Boolean {
        if (this.make.isNullOrBlank() && this.model.isNullOrBlank() && this.name.isNullOrBlank()) return false
        fun ok(pat: String?, value: String) = pat.isNullOrBlank() || value.contains(pat, ignoreCase = true)
        return ok(this.make, make) && ok(this.model, model) && ok(this.name, name)
    }
}

/**
 * Per-vehicle overrides, from two `vehicles.json` files in the same format: the one AARE ships
 * (assets, for head units known to need something), and a user-edited one in the app's external
 * files dir. Lets a specific car's density, visible-area margins or protocol quirks be fixed
 * without a new build (some head units misreport their layout, giving black bars or touches that
 * land off, see #128 / #127 / #108; some need a channel opened or a message left out). For one head
 * unit the user's matching rule wins, field by field, over the built-in one.
 */
object VehicleOverrides {
    const val FILE = "vehicles.json"
    private const val TAG = "AareVehicles"

    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE)

    /** The override for this head unit, or null. The files are tiny, so they are read each connection. */
    fun forHeadUnit(context: Context, make: String, model: String, name: String): VehicleOverride? {
        val user = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()
        val builtIn = runCatching { context.assets.open(FILE).bufferedReader().use { it.readText() } }.getOrNull()
        return resolve(rules(user, "vehicles.json"), rules(builtIn, "built-in vehicles.json"), make, model, name)
    }

    /** The user's matching rule over the built-in one (see [VehicleOverride.over]), or null if neither matches. */
    internal fun resolve(user: List<VehicleRule>, builtIn: List<VehicleRule>, make: String, model: String, name: String): VehicleOverride? {
        val mine = user.firstOrNull { it.matches(make, model, name) }?.override
        val shipped = builtIn.firstOrNull { it.matches(make, model, name) }?.override
        return mine?.over(shipped) ?: shipped
    }

    private fun rules(text: String?, what: String): List<VehicleRule> =
        text?.let { runCatching { parse(it) }.onFailure { e -> Log.w(TAG, "$what: ${e.message}") }.getOrNull() }.orEmpty()

    fun ruleCount(context: Context): Int =
        runCatching { file(context).takeIf { it.exists() }?.readText()?.let { parse(it).size } }.getOrNull() ?: 0

    /** Parses the `rules` array. Lenient: unknown keys ignored, a malformed rule skipped. */
    fun parse(json: String): List<VehicleRule> {
        val arr = JSONObject(json).optJSONArray("rules") ?: return emptyList()
        val out = ArrayList<VehicleRule>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val m = r.optJSONObject("match")
            val a = r.optJSONObject("apply")
            fun str(o: JSONObject?, k: String) = o?.optString(k, "")?.takeIf { it.isNotBlank() }
            fun int(o: JSONObject?, k: String) = o?.takeIf { it.has(k) }?.optInt(k)
            fun bool(o: JSONObject?, k: String) = o?.takeIf { it.has(k) }?.optBoolean(k)
            fun strings(o: JSONObject?, k: String): Set<String>? = (o?.opt(k) as? JSONArray)?.let { list ->
                (0 until list.length()).mapNotNull { list.optString(it, "").trim().takeIf(String::isNotEmpty) }.toSet()
            }
            out += VehicleRule(
                make = str(m, "make"), model = str(m, "model"), name = str(m, "name"),
                override = VehicleOverride(
                    dpi = int(a, "dpi"), widthMargin = int(a, "widthMargin"), heightMargin = int(a, "heightMargin"),
                    maxHeight = int(a, "maxHeight"), prefer60fps = bool(a, "prefer60fps"),
                    skipServices = strings(a, "skipServices"), openServices = strings(a, "openServices"),
                    micSetup = bool(a, "micSetup"),
                ),
            )
        }
        return out
    }

    /** A starter template for the user to edit. */
    fun template(): String =
        """
        {
          "rules": [
            {
              "_comment": "Match a head unit by make/model/display-name substrings (case-insensitive). Remove what you don't need; see docs/vehicles.md.",
              "match": { "make": "", "model": "", "name": "" },
              "apply": {
                "dpi": 160, "widthMargin": 0, "heightMargin": 0, "maxHeight": 1080, "prefer60fps": true,
                "skipServices": [], "openServices": [], "micSetup": true
              }
            }
          ]
        }
        """.trimIndent()
}
