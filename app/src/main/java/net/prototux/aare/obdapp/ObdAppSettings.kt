package net.prototux.aare.obdapp

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The car's details the OBD2 app needs for what the adapter can't tell (fuel use, range),
 * kept apart from AARE's own settings: the whole app goes away with [net.prototux.aare.Settings.obdAdapter].
 */
class ObdAppSettings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("obd_app", Context.MODE_PRIVATE)

    enum class Engine { AUTO, TURBO, ASPIRATED }
    enum class Fuel { PETROL, DIESEL }

    /** What the second gauge shows: boost (turbo) or engine load (aspirated). AUTO: load until boost is seen. */
    var engine: Engine
        get() = runCatching { Engine.valueOf(prefs.getString("engine", null) ?: "") }.getOrDefault(Engine.AUTO)
        set(v) = prefs.edit { putString("engine", v.name) }

    /** AUTO saw the manifold above the outside air at least once: it's a turbo. */
    var turboSeen: Boolean
        get() = prefs.getBoolean("turbo_seen", false)
        set(v) = prefs.edit { putBoolean("turbo_seen", v) }

    val showsBoost get() = when (engine) { Engine.TURBO -> true; Engine.ASPIRATED -> false; Engine.AUTO -> turboSeen }

    var rpmMax: Int
        get() = prefs.getInt("rpm_max", 7000)
        set(v) = prefs.edit { putInt("rpm_max", v) }

    var redline: Int
        get() = prefs.getInt("redline", 6500)
        set(v) = prefs.edit { putInt("redline", v) }

    /** Engine size in cm³, for fuel use on cars without a MAF sensor. */
    var displacementCc: Int
        get() = prefs.getInt("displacement", 1600)
        set(v) = prefs.edit { putInt("displacement", v) }

    var tankLiters: Int
        get() = prefs.getInt("tank", 50)
        set(v) = prefs.edit { putInt("tank", v) }

    var fuel: Fuel
        get() = runCatching { Fuel.valueOf(prefs.getString("fuel", null) ?: "") }.getOrDefault(Fuel.PETROL)
        set(v) = prefs.edit { putString("fuel", v.name) }

    /** Ethanol in the petrol when the car has no sensor for it (0 in most countries, 27 in Brazil's petrol, 100 for ethanol). */
    var ethanolPercent: Int
        get() = prefs.getInt("ethanol", 0)
        set(v) = prefs.edit { putInt("ethanol", v) }

    /** What the dashboard's Car card shows, in order ([CardValue] ids, at most [CardValue.MAX]). */
    var cardValues: List<CardValue>
        get() = (prefs.getString("card_values", null) ?: CardValue.DEFAULT.joinToString(",") { it.id })
            .split(',').mapNotNull { id -> CardValue.entries.firstOrNull { it.id == id } }.distinct()
        set(v) = prefs.edit { putString("card_values", v.distinct().take(CardValue.MAX).joinToString(",") { it.id }) }

    /** The gear ratios learned while driving (km/h per 1000 rpm), "" = none yet. */
    var gears: String
        get() = prefs.getString("gears", "") ?: ""
        set(v) = prefs.edit { putString("gears", v) }

    /** The last trip's average, km/L, for the range before the new trip has its own. */
    var lastAverage: Float
        get() = prefs.getFloat("last_average", 0f)
        set(v) = prefs.edit { putFloat("last_average", v) }

    fun car() = CarSpec(displacementCc, fuel, ethanolPercent, tankLiters)
}

/** A value the dashboard's Car card can show. */
enum class CardValue(
    /** Stored in the settings: never change it once released. */
    val id: String,
) {
    COOLANT("coolant"), OIL("oil"), INTAKE("intake"), OUTSIDE("outside"),
    /** Boost on a turbo, engine load on an aspirated engine: the second gauge's value. */
    GAUGE("gauge"),
    BATTERY("battery"), FUEL("fuel"), ETHANOL("ethanol"), RANGE("range"),
    /** The trip's average fuel use. */
    AVERAGE("average"),
    /** The fuel use right now. */
    NOW("now");

    companion object {
        const val MAX = 6
        val DEFAULT = listOf(COOLANT, GAUGE, BATTERY, FUEL, RANGE, AVERAGE)
    }
}

/** The car's details for the calculations, as plain values (no Android), so they can be tested. */
data class CarSpec(
    val displacementCc: Int = 1600,
    val fuel: ObdAppSettings.Fuel = ObdAppSettings.Fuel.PETROL,
    val ethanolPercent: Int = 0,
    val tankLiters: Int = 50,
)
