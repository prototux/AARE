package net.prototux.aare.car

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Standard OBD-II trouble codes (SAE J2012 / ISO 15031-6), decoded from the raw bytes the car's
 * diagnostics sensor sends ([CarData.State.diagnostics]) into the familiar 5-character codes
 * (P/C/B/U + 4 digits), with a plain-language description for the generic (SAE-defined) ones from
 * the catalogue AARE ships (`app/src/main/assets/dtc/generic.json`).
 *
 * The wire format (two bytes per code, as AAP's `DiagnosticsData.dtc` is otherwise undocumented):
 * byte 1 bits 7-6 pick the system letter, bits 5-4 the first digit (0-3: SAE-generic codes use 0
 * or 2, manufacturer-specific ones 1 or 3), bits 3-0 the second digit; byte 2's high and low
 * nibbles are the third and fourth digits. Each digit is a hex nibble (0-9, occasionally A-F).
 */
object DtcCodes {
    private const val TAG = "AareDtc"
    private val SYSTEMS = charArrayOf('P', 'C', 'B', 'U')
    private val FAMILIES = mapOf('P' to "powertrain", 'C' to "chassis", 'B' to "body", 'U' to "network")

    /** One decoded code: its family, whether it's SAE-generic, and what to show for it. */
    data class Dtc(val code: String, val generic: Boolean, val family: String, val description: String)

    /** Every 2-byte code in [bytes], as "P0128" etc. A trailing odd byte (malformed data) is dropped. */
    fun decode(bytes: ByteArray): List<String> = (0 until bytes.size / 2).map { toCode(bytes[it * 2], bytes[it * 2 + 1]) }

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
     * SAE/generic: the same meaning for every make, per J2012 (the second character is 0 or 2).
     * 1 and 3 are manufacturer-specific (or, for some systems, jointly SAE/manufacturer-defined);
     * either way AARE has no catalogue for them.
     */
    fun isGeneric(code: String): Boolean = code.getOrNull(1)?.let { it == '0' || it == '2' } == true

    /** [code] with its family and a description: the catalogue's for a known generic code, "unknown" for an
     * uncatalogued generic one, or the family alone (no description) for a manufacturer-specific one. */
    fun describe(context: Context, code: String): Dtc {
        val generic = isGeneric(code)
        val fam = family(code)
        val description = when {
            !generic -> "manufacturer-specific $fam code"
            else -> Catalogue.lookup(context, code) ?: "unknown"
        }
        return Dtc(code, generic, fam, description)
    }

    /** One display line per code in [bytes] ("P0128 — Coolant thermostat below regulating temperature"), for the car data viewers. */
    fun describeAll(context: Context, bytes: ByteArray): List<String> =
        decode(bytes).map { code -> describe(context, code) }.map { "${it.code} — ${it.description}" }

    /** The shipped catalogue of generic codes (`app/src/main/assets/dtc/generic.json`): code to a short description. */
    object Catalogue {
        const val ASSET_PATH = "dtc/generic.json"

        @Volatile private var cache: Map<String, String>? = null

        fun lookup(context: Context, code: String): String? = load(context)[code]

        private fun load(context: Context): Map<String, String> = cache ?: synchronized(this) {
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
