package net.prototux.aare.obdapp

import net.prototux.aare.obd.ObdReadings

/**
 * Fuel use from what the car reports, best source first:
 * 1. the fuel rate itself (PID 5E, some newer cars);
 * 2. the air flow (MAF, PID 10) divided by the air/fuel ratio;
 * 3. the air flow estimated from the manifold pressure, the intake air and the engine size
 *    ("speed density"), for cars without a MAF sensor.
 * Petrol engines run at the stoichiometric ratio, so 2 and 3 hold for them. A diesel's ratio swings
 * a lot, so a diesel without 5E gets no estimate.
 */
object FuelMath {
    private const val AIR_GAS_CONSTANT = 287.05f // J/(kg·K)
    private const val VOLUMETRIC_EFFICIENCY = 0.85f
    /** Below this load with the engine above idle, it's coasting: the injection is cut. */
    private const val OVERRUN_LOAD = 3f

    /** Litres per hour now; null when nothing to estimate from. */
    fun litersPerHour(r: ObdReadings, car: CarSpec): Float? {
        r.fuelRate?.let { return it }
        if (car.fuel == ObdAppSettings.Fuel.DIESEL) return null
        val rpm = r.rpm ?: return null
        if (rpm < 300f) return 0f
        if ((r.load ?: 100f) < OVERRUN_LOAD && rpm > 1200f) return 0f
        val airGs = r.maf ?: speedDensity(r, rpm, car) ?: return null
        val e = ethanolShare(r, car)
        val afr = 14.7f * (1 - e) + 9.0f * e
        val density = 0.745f * (1 - e) + 0.789f * e // kg/L
        return airGs / afr * 3600f / 1000f / density
    }

    /** km/L now; null when stopped or unknown; capped while coasting (no fuel). */
    fun kmPerLiter(r: ObdReadings, car: CarSpec): Float? {
        val speed = r.speed ?: return null
        if (speed < 3f) return null
        val lh = litersPerHour(r, car) ?: return null
        return if (lh <= 0.05f) MAX_KM_PER_L else (speed / lh).coerceAtMost(MAX_KM_PER_L)
    }

    const val MAX_KM_PER_L = 99f

    /** g/s of air: density in the manifold × engine size × efficiency × intake strokes per second. */
    private fun speedDensity(r: ObdReadings, rpm: Float, car: CarSpec): Float? {
        val manifold = r.manifoldKpa ?: return null
        val air = (r.intakeAir ?: 30f) + 273.15f
        val density = manifold * 1000f / (AIR_GAS_CONSTANT * air) // kg/m³
        val liters = car.displacementCc / 1000f
        return density * liters * VOLUMETRIC_EFFICIENCY * rpm / 120f // kg/m³ × L = g
    }

    private fun ethanolShare(r: ObdReadings, car: CarSpec) = ((r.ethanol ?: car.ethanolPercent.toFloat()) / 100f).coerceIn(0f, 1f)
}
