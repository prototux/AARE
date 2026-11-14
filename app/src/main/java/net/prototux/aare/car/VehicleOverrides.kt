package net.prototux.aare.car

import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
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
    /** The only video resolutions to pick, as "800x480" (when the head unit offers one of them). */
    val videoResolutions: Set<String>? = null,
    /** Width / height of one of the car screen's pixels (see [PixelAspect]); 1 is square. */
    val pixelAspect: Float? = null,
    /** Navigation voice goes with the music on the media channel; the guidance channel stays unused. */
    val singleChannelCapture: Boolean? = null,
    /** The car is electric even though it reports no fuel type, or an unknown one. */
    val forceElectric: Boolean? = null,
    /** Silent audio messages sent ahead of a media / guidance stream's start, over Wi-Fi (see [StartSilence]). */
    val wifiMediaStartSilence: Int? = null,
    val wifiGuidanceStartSilence: Int? = null,
    /** Music keeps playing when the car grants GAIN_TRANSIENT (AARE's default: only navigation voice does). */
    val mediaOnGainTransient: Boolean? = null,
    /** An unsolicited GAIN_TRANSIENT_GUIDANCE_ONLY from the car pauses the phone's music (AARE's default: yes). */
    val pauseOnGuidanceOnly: Boolean? = null,
    /** Whether to connect to this head unit wirelessly (AARE's default: yes). */
    val wireless: Boolean? = null,
    /** Whether the app list has the "Exit to car" entry (AARE's default: yes). */
    val exitToCar: Boolean? = null,
    /** What the "Exit to car" entry is called: [ExitLabel.NAME], [ExitLabel.MAKE] or [ExitLabel.DEFAULT]. */
    val exitLabel: String? = null,
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
        videoResolutions = videoResolutions ?: base.videoResolutions,
        pixelAspect = pixelAspect ?: base.pixelAspect,
        singleChannelCapture = singleChannelCapture ?: base.singleChannelCapture,
        forceElectric = forceElectric ?: base.forceElectric,
        wifiMediaStartSilence = wifiMediaStartSilence ?: base.wifiMediaStartSilence,
        wifiGuidanceStartSilence = wifiGuidanceStartSilence ?: base.wifiGuidanceStartSilence,
        mediaOnGainTransient = mediaOnGainTransient ?: base.mediaOnGainTransient,
        pauseOnGuidanceOnly = pauseOnGuidanceOnly ?: base.pauseOnGuidanceOnly,
        wireless = wireless ?: base.wireless,
        exitToCar = exitToCar ?: base.exitToCar,
        exitLabel = exitLabel ?: base.exitLabel,
    )

    fun skips(kind: String): Boolean = skipServices.orEmpty().any { matchesKind(it, kind) }
    fun opens(kind: String): Boolean = openServices.orEmpty().any { matchesKind(it, kind) }

    /** For the connection log: only the fields a rule set. */
    override fun toString(): String = listOf(
        "dpi" to dpi, "widthMargin" to widthMargin, "heightMargin" to heightMargin, "maxHeight" to maxHeight,
        "prefer60fps" to prefer60fps, "skipServices" to skipServices, "openServices" to openServices, "micSetup" to micSetup,
        "videoResolutions" to videoResolutions, "pixelAspect" to pixelAspect, "singleChannelCapture" to singleChannelCapture,
        "forceElectric" to forceElectric, "wifiMediaStartSilence" to wifiMediaStartSilence,
        "wifiGuidanceStartSilence" to wifiGuidanceStartSilence, "mediaOnGainTransient" to mediaOnGainTransient,
        "pauseOnGuidanceOnly" to pauseOnGuidanceOnly, "wireless" to wireless, "exitToCar" to exitToCar, "exitLabel" to exitLabel,
    ).filter { it.second != null }.joinToString(prefix = "{", postfix = "}") { (k, v) -> "$k=$v" }

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

/**
 * Who a head unit says it is (service discovery), and the protocol version it asked for. Empty
 * strings for what it doesn't report; [protocol] is null when unknown.
 */
data class HeadUnitIdentity(
    val make: String,
    val model: String,
    /** The connection log's `head unit:` string: make, model, head unit make and model. */
    val name: String,
    val year: String = "",
    val headUnitMake: String = "",
    val headUnitModel: String = "",
    val headUnitBuild: String = "",
    val protocol: Pair<Int, Int>? = null,
) {
    companion object {
        /** From service discovery: the head unit info block, or the older top-level fields when it's empty. */
        fun of(r: ServiceDiscoveryResponse, name: String, protocol: Pair<Int, Int>?): HeadUnitIdentity {
            val hu = r.headunitInfo
            return HeadUnitIdentity(
                make = hu.make.ifEmpty { r.make }, model = hu.model.ifEmpty { r.model }, name = name,
                year = hu.year.ifEmpty { r.year }, headUnitMake = hu.headUnitMake.ifEmpty { r.headUnitMake },
                headUnitModel = hu.headUnitModel.ifEmpty { r.headUnitModel },
                headUnitBuild = hu.headUnitSoftwareBuild.ifEmpty { r.headUnitSoftwareBuild }, protocol = protocol,
            )
        }
    }
}

/**
 * Which head units a rule is for. Each field is a list of alternatives (any one matching is enough);
 * an empty list matches anything. [make], [model] and [name] are case-insensitive substrings, or the
 * whole value with [exact]. The others are always the whole value, case-insensitive, like Android
 * Auto's own car lists. The protocol fields are "major.minor": [protocol] exactly, [minProtocol] and
 * [maxProtocol] as inclusive bounds; they never match a head unit whose version isn't known.
 */
data class VehicleMatch(
    val make: List<String> = emptyList(),
    val model: List<String> = emptyList(),
    val name: List<String> = emptyList(),
    val exact: Boolean = false,
    val year: List<String> = emptyList(),
    val headUnitMake: List<String> = emptyList(),
    val headUnitModel: List<String> = emptyList(),
    val headUnitBuild: List<String> = emptyList(),
    val protocol: List<Pair<Int, Int>> = emptyList(),
    val minProtocol: Pair<Int, Int>? = null,
    val maxProtocol: Pair<Int, Int>? = null,
) {
    /** Nothing to match on: such a rule never matches (an empty "match" is a mistake, not "every car"). */
    val isEmpty: Boolean
        get() = make.isEmpty() && model.isEmpty() && name.isEmpty() && year.isEmpty() && headUnitMake.isEmpty() &&
            headUnitModel.isEmpty() && headUnitBuild.isEmpty() && protocol.isEmpty() && minProtocol == null && maxProtocol == null

    fun matches(id: HeadUnitIdentity): Boolean {
        if (isEmpty) return false
        fun part(pats: List<String>, value: String) =
            pats.isEmpty() || pats.any { if (exact) value.trim().equals(it, ignoreCase = true) else value.contains(it, ignoreCase = true) }
        fun whole(pats: List<String>, value: String) = pats.isEmpty() || pats.any { value.trim().equals(it, ignoreCase = true) }
        if (!part(make, id.make) || !part(model, id.model) || !part(name, id.name)) return false
        if (!whole(year, id.year) || !whole(headUnitMake, id.headUnitMake) || !whole(headUnitModel, id.headUnitModel) ||
            !whole(headUnitBuild, id.headUnitBuild)) return false
        if (protocol.isEmpty() && minProtocol == null && maxProtocol == null) return true
        val v = id.protocol ?: return false
        return (protocol.isEmpty() || v in protocol) &&
            (minProtocol == null || compareVersions(v, minProtocol) >= 0) &&
            (maxProtocol == null || compareVersions(v, maxProtocol) <= 0)
    }

    companion object {
        fun compareVersions(a: Pair<Int, Int>, b: Pair<Int, Int>): Int = compareValuesBy(a, b, { it.first }, { it.second })

        /** "1.2" to (1, 2); anything else throws, so a rule with a typo is skipped rather than matching every car. */
        fun version(s: String): Pair<Int, Int> {
            val parts = s.trim().split('.')
            require(parts.size == 2) { "protocol version \"$s\" isn't major.minor" }
            return (parts[0].toIntOrNull() ?: error("bad protocol version \"$s\"")) to (parts[1].toIntOrNull() ?: error("bad protocol version \"$s\""))
        }
    }
}

/** A rule: the head units it is for, and what to [override] for them. */
data class VehicleRule(
    val match: VehicleMatch,
    val override: VehicleOverride,
) {
    /** Make, model and display-name substrings only, as vehicles.json had before the other match keys. */
    constructor(make: String?, model: String?, name: String?, override: VehicleOverride) :
        this(VehicleMatch(listOfNotNull(make?.takeIf { it.isNotBlank() }), listOfNotNull(model?.takeIf { it.isNotBlank() }),
            listOfNotNull(name?.takeIf { it.isNotBlank() })), override)

    fun matches(id: HeadUnitIdentity): Boolean = match.matches(id)

    /** A head unit known only by make, model and display name: a rule needing anything else doesn't match. */
    fun matches(make: String, model: String, name: String): Boolean = matches(HeadUnitIdentity(make, model, name))
}

/**
 * One vehicles.json quirk's global choice, kept in Settings for every car: [AUTOMATIC] keeps
 * what vehicles.json (built-in rules and the user's file) resolves for the connected car,
 * [ON] / [OFF] force it regardless. See [VehicleOverrides.effective].
 */
enum class QuirkChoice(val pref: String) {
    AUTOMATIC("auto"), ON("on"), OFF("off");
    companion object {
        fun of(pref: String?): QuirkChoice = entries.firstOrNull { it.pref == pref } ?: AUTOMATIC
    }
}

/**
 * The "Car quirks" settings page: one global [QuirkChoice] per vehicles.json quirk that has a
 * clear on/off meaning, applied on top of the per-car vehicles.json resolution for every car
 * (not just the connected one). See [VehicleOverrides.effective]. Fields [VehicleOverride] has
 * no setting for (dpi, margins, maxHeight, prefer60fps, skip/openServices, exitLabel) are never
 * touched: those stay per-vehicle only, or have their own setting elsewhere (prefer60fps,
 * maxHeight: Settings.prefer60fps / maxVideoHeight).
 */
data class QuirkSettings(
    val pixelAspect: QuirkChoice = QuirkChoice.AUTOMATIC,
    val videoResolutions: QuirkChoice = QuirkChoice.AUTOMATIC,
    val singleChannelCapture: QuirkChoice = QuirkChoice.AUTOMATIC,
    val wifiMediaStartSilence: QuirkChoice = QuirkChoice.AUTOMATIC,
    val wifiGuidanceStartSilence: QuirkChoice = QuirkChoice.AUTOMATIC,
    val mediaOnGainTransient: QuirkChoice = QuirkChoice.AUTOMATIC,
    val pauseOnGuidanceOnly: QuirkChoice = QuirkChoice.AUTOMATIC,
    val wireless: QuirkChoice = QuirkChoice.AUTOMATIC,
    val forceElectric: QuirkChoice = QuirkChoice.AUTOMATIC,
    val exitToCar: QuirkChoice = QuirkChoice.AUTOMATIC,
    val micSetup: QuirkChoice = QuirkChoice.AUTOMATIC,
)

/**
 * Per-vehicle overrides, from two `vehicles.json` files in the same format: the one AARE ships
 * (assets, for head units known to need something), and a user-edited one in the app's external
 * files dir. Lets a specific car's density, visible-area margins or protocol quirks be fixed
 * without a new build (some head units misreport their layout, giving black bars or touches that
 * land off, see #128 / #127 / #108; some need a channel opened or a message left out). For one head
 * unit the user's matching rule wins, field by field, over the built-in ones.
 */
object VehicleOverrides {
    const val FILE = "vehicles.json"
    private const val TAG = "AareVehicles"

    fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE)

    /** The override for this head unit, or null. The files are tiny, so they are read each connection. */
    fun forHeadUnit(context: Context, id: HeadUnitIdentity): VehicleOverride? {
        val user = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()
        val builtIn = runCatching { context.assets.open(FILE).bufferedReader().use { it.readText() } }.getOrNull()
        return resolve(rules(user, "vehicles.json"), rules(builtIn, "built-in vehicles.json"), id)
    }

    /**
     * The user's first matching rule over the built-in ones (see [VehicleOverride.over]), or null if
     * nothing matches. Every matching built-in rule counts, the earlier one winning a field both set:
     * each of them covers one quirk, and one head unit can have several. The user's file keeps its
     * first-match meaning, so a file written before stays as it was.
     */
    internal fun resolve(user: List<VehicleRule>, builtIn: List<VehicleRule>, id: HeadUnitIdentity): VehicleOverride? {
        val mine = user.firstOrNull { it.matches(id) }?.override
        val shipped = builtIn.filter { it.matches(id) }.map { it.override }.reduceOrNull { acc, next -> acc.over(next) }
        return mine?.over(shipped) ?: shipped
    }

    internal fun resolve(user: List<VehicleRule>, builtIn: List<VehicleRule>, make: String, model: String, name: String): VehicleOverride? =
        resolve(user, builtIn, HeadUnitIdentity(make, model, name))

    /**
     * [resolved] (vehicles.json for the connected car, from [forHeadUnit], or null) with [quirks]'s
     * global choices applied on top. [QuirkChoice.AUTOMATIC] keeps what [resolved] says for every
     * field (including leaving it unset, so AARE's own default decides, exactly as without this
     * settings page); [QuirkChoice.ON] / [QuirkChoice.OFF] force the quirk on or off for every car,
     * regardless of what vehicles.json matched.
     *
     * [pixelAspect][QuirkSettings.pixelAspect], the two start-silence counts and
     * [videoResolutions][QuirkSettings.videoResolutions] have no single "on" value to force (how
     * much pixel correction, how much silence, which resolutions is the head unit's or the rule's
     * to say): their [QuirkChoice.ON] is the same as [QuirkChoice.AUTOMATIC], and only
     * [QuirkChoice.OFF] forces a value (square pixels, no silence, no resolution limit).
     */
    fun effective(resolved: VehicleOverride?, quirks: QuirkSettings): VehicleOverride? {
        if (quirks == QuirkSettings()) return resolved // every quirk Automatic: today's behaviour, unchanged
        val base = resolved ?: VehicleOverride()
        return base.copy(
            pixelAspect = if (quirks.pixelAspect == QuirkChoice.OFF) 1f else base.pixelAspect,
            videoResolutions = if (quirks.videoResolutions == QuirkChoice.OFF) null else base.videoResolutions,
            singleChannelCapture = forced(quirks.singleChannelCapture, base.singleChannelCapture),
            wifiMediaStartSilence = if (quirks.wifiMediaStartSilence == QuirkChoice.OFF) 0 else base.wifiMediaStartSilence,
            wifiGuidanceStartSilence = if (quirks.wifiGuidanceStartSilence == QuirkChoice.OFF) 0 else base.wifiGuidanceStartSilence,
            mediaOnGainTransient = forced(quirks.mediaOnGainTransient, base.mediaOnGainTransient),
            pauseOnGuidanceOnly = forced(quirks.pauseOnGuidanceOnly, base.pauseOnGuidanceOnly),
            wireless = forced(quirks.wireless, base.wireless),
            forceElectric = forced(quirks.forceElectric, base.forceElectric),
            exitToCar = forced(quirks.exitToCar, base.exitToCar),
            micSetup = forced(quirks.micSetup, base.micSetup),
            // Nothing matched and no setting forced a field: stay null, like no vehicles.json rule at all.
        ).takeUnless { it == VehicleOverride() }
    }

    private fun forced(choice: QuirkChoice, value: Boolean?): Boolean? = when (choice) {
        QuirkChoice.ON -> true
        QuirkChoice.OFF -> false
        QuirkChoice.AUTOMATIC -> value
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
            runCatching { parseRule(r) }.onSuccess { out += it }.onFailure { Log.w(TAG, "vehicles.json rule ${i + 1} skipped: ${it.message}") }
        }
        return out
    }

    private fun parseRule(r: JSONObject): VehicleRule {
        val m = r.optJSONObject("match")
        val a = r.optJSONObject("apply")
        fun int(o: JSONObject?, k: String) = o?.takeIf { it.has(k) }?.optInt(k)
        fun float(o: JSONObject?, k: String) = o?.takeIf { it.has(k) }?.optDouble(k)?.takeIf { !it.isNaN() }?.toFloat()
        fun bool(o: JSONObject?, k: String) = o?.takeIf { it.has(k) }?.optBoolean(k)
        fun str(o: JSONObject?, k: String) = o?.optString(k, "")?.trim()?.takeIf { it.isNotEmpty() }
        fun strings(o: JSONObject?, k: String): Set<String>? = (o?.opt(k) as? JSONArray)?.let { list ->
            (0 until list.length()).mapNotNull { list.optString(it, "").trim().takeIf(String::isNotEmpty) }.toSet()
        }
        // A match key is one string or a list of alternatives; blank ones mean "any". make, model
        // and name aren't trimmed: a substring with a space in it kept that space before these keys.
        fun alternatives(k: String, trim: Boolean = true): List<String> = when (val v = m?.opt(k)) {
            is JSONArray -> (0 until v.length()).mapNotNull { j -> v.optString(j, "").let { if (trim) it.trim() else it }.takeIf(String::isNotBlank) }
            null, JSONObject.NULL -> emptyList()
            else -> listOfNotNull(v.toString().let { if (trim) it.trim() else it }.takeIf(String::isNotBlank))
        }
        return VehicleRule(
            match = VehicleMatch(
                make = alternatives("make", trim = false), model = alternatives("model", trim = false), name = alternatives("name", trim = false),
                exact = bool(m, "exact") ?: false, year = alternatives("year"),
                headUnitMake = alternatives("headUnitMake"), headUnitModel = alternatives("headUnitModel"),
                headUnitBuild = alternatives("headUnitBuild"), protocol = alternatives("protocol").map(VehicleMatch::version),
                minProtocol = str(m, "minProtocol")?.let(VehicleMatch::version), maxProtocol = str(m, "maxProtocol")?.let(VehicleMatch::version),
            ),
            override = VehicleOverride(
                dpi = int(a, "dpi"), widthMargin = int(a, "widthMargin"), heightMargin = int(a, "heightMargin"),
                maxHeight = int(a, "maxHeight"), prefer60fps = bool(a, "prefer60fps"),
                skipServices = strings(a, "skipServices"), openServices = strings(a, "openServices"),
                micSetup = bool(a, "micSetup"), videoResolutions = strings(a, "videoResolutions"),
                pixelAspect = float(a, "pixelAspect"), singleChannelCapture = bool(a, "singleChannelCapture"),
                forceElectric = bool(a, "forceElectric"), wifiMediaStartSilence = int(a, "wifiMediaStartSilence"),
                wifiGuidanceStartSilence = int(a, "wifiGuidanceStartSilence"), mediaOnGainTransient = bool(a, "mediaOnGainTransient"),
                pauseOnGuidanceOnly = bool(a, "pauseOnGuidanceOnly"), wireless = bool(a, "wireless"),
                exitToCar = bool(a, "exitToCar"), exitLabel = str(a, "exitLabel"),
            ),
        )
    }

    /** A starter template for the user to edit. */
    fun template(): String =
        """
        {
          "rules": [
            {
              "_comment": "Match a head unit by make/model/display-name substrings (case-insensitive), and optionally year, head unit make/model/build and protocol version. Remove what you don't need; see docs/vehicles.md.",
              "match": { "make": "", "model": "", "name": "", "year": "", "headUnitMake": "", "headUnitModel": "", "minProtocol": "" },
              "apply": {
                "dpi": 160, "widthMargin": 0, "heightMargin": 0, "maxHeight": 1080, "prefer60fps": true,
                "skipServices": [], "openServices": [], "micSetup": true
              }
            }
          ]
        }
        """.trimIndent()
}
