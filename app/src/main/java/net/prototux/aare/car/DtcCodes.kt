package net.prototux.aare.car

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import org.json.JSONObject

/**
 * Standard OBD-II trouble codes (SAE J2012 / ISO 15031-6), decoded from the raw bytes the car's
 * diagnostics sensor sends ([CarData.State.diagnostics]) into the familiar 5-character codes
 * (P/C/B/U + 4 digits), with a plain-language description for the generic (SAE-defined) ones from
 * the catalogue AARE ships (`app/src/main/assets/dtc/generic.json`).
 *
 * The wire format is a guess: AAP's `DiagnosticsData.dtc` is undocumented beyond "bytes", so this
 * assumes the usual OBD-II layout, two bytes per code: byte 1 bits 7-6 pick the system letter,
 * bits 5-4 the first digit (0-3), bits 3-0 the second digit; byte 2's high and low nibbles are the
 * third and fourth digits. Each digit is a hex nibble (0-9, occasionally A-F). As it is a guess,
 * the viewers show each code's raw bytes next to it. A zero pair (P0000) is padding, not a code.
 */
object DtcCodes {
    private const val TAG = "AareDtc"
    private val SYSTEMS = charArrayOf('P', 'C', 'B', 'U')
    private val FAMILIES = mapOf('P' to "powertrain", 'C' to "chassis", 'B' to "body", 'U' to "network")
    private val MANUFACTURER_DESCRIPTIONS = mapOf(
        "powertrain" to R.string.car_data_dtc_manufacturer_powertrain,
        "chassis" to R.string.car_data_dtc_manufacturer_chassis,
        "body" to R.string.car_data_dtc_manufacturer_body,
        "network" to R.string.car_data_dtc_manufacturer_network,
    )

    /**
     * One decoded code: its raw bytes as hex ("01 28"), its family, whether it's SAE-generic, and
     * what to show for it (null while the catalogue isn't loaded yet).
     */
    data class Dtc(val code: String, val raw: String, val generic: Boolean, val family: String, val description: String?)

    /** Every 2-byte code in [bytes], as "P0128" etc. Zero padding and a trailing odd byte (malformed data) are dropped. */
    fun decode(bytes: ByteArray): List<String> = pairs(bytes).map { (h, l) -> toCode(h, l) }

    /** The 2-byte codes in [bytes], without zero padding or a trailing odd byte. */
    private fun pairs(bytes: ByteArray): List<Pair<Byte, Byte>> =
        (0 until bytes.size / 2).map { bytes[it * 2] to bytes[it * 2 + 1] }
            .filterNot { (h, l) -> h.toInt() == 0 && l.toInt() == 0 }

    private fun toCode(high: Byte, low: Byte): String {
        val h = high.toInt() and 0xFF
        val l = low.toInt() and 0xFF
        val system = SYSTEMS[(h shr 6) and 0x3]
        val digits = (h shr 4) and 0x3
        val nibbles = "${(h and 0xF).toString(16)}${(l shr 4).toString(16)}${(l and 0xF).toString(16)}"
        return "$system$digits$nibbles".uppercase()
    }

    /** The system a code belongs to, from its first character ("powertrain", "chassis", "body", "network"). */
    fun family(code: String): String = FAMILIES[code.firstOrNull()?.uppercaseChar()] ?: "unknown"

    /**
     * SAE/generic: the same meaning for every make, per SAE J2012's ranges. Powertrain: P0, P2 and
     * P34-P39 are generic; P1 and P30-P33 manufacturer-specific. Chassis, body and network: 0 and 3
     * are generic, 1 and 2 manufacturer-specific. AARE has no catalogue for manufacturer codes.
     */
    fun isGeneric(code: String): Boolean {
        val digit = code.getOrNull(1) ?: return false
        return when (code.first().uppercaseChar()) {
            'P' -> digit == '0' || digit == '2' || (digit == '3' && code.getOrNull(2)?.let { it in '4'..'9' } == true)
            'C', 'B', 'U' -> digit == '0' || digit == '3'
            else -> false
        }
    }

    /**
     * [code] with its family and a description: the catalogue's for a known generic code,
     * "Unknown" for an uncatalogued generic one, or the family alone for a manufacturer-specific
     * one. [catalogue] null (not loaded yet) leaves a generic code without a description.
     */
    fun describe(context: Context, code: String, raw: String, catalogue: Map<String, String>?): Dtc {
        val generic = isGeneric(code)
        val fam = family(code)
        val description = when {
            !generic -> context.getString(MANUFACTURER_DESCRIPTIONS[fam] ?: R.string.car_data_dtc_unknown)
            catalogue == null -> null
            else -> catalogue[code] ?: context.getString(R.string.car_data_dtc_unknown)
        }
        return Dtc(code, raw, generic, fam, description)
    }

    /**
     * One display line per code in [bytes] ("P0128 (01 28) — Coolant thermostat below regulating
     * temperature"), for the car data viewers; [catalogue] as for [describe].
     */
    fun describeAll(context: Context, bytes: ByteArray, catalogue: Map<String, String>?): List<String> =
        pairs(bytes).map { (h, l) -> describe(context, toCode(h, l), "%02x %02x".format(h, l), catalogue) }
            .map { d -> "${d.code} (${d.raw})" + (d.description?.let { " — $it" } ?: "") }

    /** The shipped catalogue of generic codes (`app/src/main/assets/dtc/generic.json`): code to a short description. */
    object Catalogue {
        const val ASSET_PATH = "dtc/generic.json"

        @Volatile private var cache: Map<String, String>? = null

        /** The catalogue if it's been read already, else null: never touches the assets. */
        val loaded: Map<String, String>? get() = cache

        /** The catalogue (~20 KB of JSON), read from the assets off the main thread the first time, then kept. */
        suspend fun load(context: Context): Map<String, String> = cache ?: withContext(Dispatchers.IO) { read(context) }

        private fun read(context: Context): Map<String, String> = cache ?: synchronized(this) {
            cache ?: runCatching { context.assets.open(ASSET_PATH).bufferedReader().use { parse(it.readText()) } }
                .onFailure { Log.w(TAG, "dtc catalogue: ${it.message}") }
                .getOrDefault(emptyMap())
                .also { cache = it }
        }

        /** Parses the catalogue's JSON object (code to description) into a map. Lenient: a non-object value is skipped. */
        internal fun parse(json: String): Map<String, String> {
            val obj = JSONObject(json)
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key, "")
                if (value.isNotBlank()) out[key] = value
            }
            return out
        }
    }
}
