package net.prototux.aare.car

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** One car's overrides of the projection layout, from vehicles.json. All fields are optional. */
data class VehicleOverride(
    val dpi: Int? = null,
    val widthMargin: Int? = null,
    val heightMargin: Int? = null,
    val maxHeight: Int? = null,
    val prefer60fps: Boolean? = null,
)

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
 * Per-vehicle overrides of the projection layout, read from a user-edited `vehicles.json` in the
 * app's external files dir. Lets a specific car's density or visible-area margins be fixed without a
 * new build (some head units misreport them, giving black bars or touches that land off). See #128
 * / #127 / #108 and the car reports.
 */
object VehicleOverrides {
    const val FILE = "vehicles.json"
    private const val TAG = "AareVehicles"

    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE)

    /** The override for this head unit, or null. The file is tiny, so it is read each connection. */
    fun forHeadUnit(context: Context, make: String, model: String, name: String): VehicleOverride? {
        val text = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull() ?: return null
        val rules = runCatching { parse(text) }.onFailure { Log.w(TAG, "vehicles.json: ${it.message}") }.getOrNull() ?: return null
        return rules.firstOrNull { it.matches(make, model, name) }?.override
    }

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
            out += VehicleRule(
                make = str(m, "make"), model = str(m, "model"), name = str(m, "name"),
                override = VehicleOverride(
                    dpi = int(a, "dpi"), widthMargin = int(a, "widthMargin"), heightMargin = int(a, "heightMargin"),
                    maxHeight = int(a, "maxHeight"), prefer60fps = bool(a, "prefer60fps"),
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
              "_comment": "Match a head unit by make/model/display-name substrings (case-insensitive).",
              "match": { "make": "", "model": "", "name": "" },
              "apply": { "dpi": 160, "widthMargin": 0, "heightMargin": 0, "maxHeight": 1080, "prefer60fps": true }
            }
          ]
        }
        """.trimIndent()
}
