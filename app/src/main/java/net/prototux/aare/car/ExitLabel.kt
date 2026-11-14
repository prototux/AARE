package net.prototux.aare.car

/**
 * What the app list's "Exit to car" entry is called: after the car,
 * its display name or else its make ("Exit" to a "Toyota" screen), or the generic label. Which one
 * a make gets is vehicles.json's `exitLabel` (built-in rules carry known lists).
 */
object ExitLabel {
    /** The head unit's display name, else its make (the default). */
    const val NAME = "name"
    /** The make, even when there is a display name. */
    const val MAKE = "make"
    /** The generic label. */
    const val DEFAULT = "default"

    /** Values head units report that aren't a brand, so never a label (built-in list). */
    private val NOT_A_BRAND = setOf("OEM Infotainment", "Chery Automobile Co.,Ltd")

    /** Names written this way on purpose, kept even in all capitals or small letters (built-in list). */
    private val AS_WRITTEN = setOf(
        "ALPINA", "AVAN", "AVATR", "BMW M", "BMW", "BMWI", "BMWM", "BYD", "carrozzeria", "CEER", "CHERY", "CHETA", "COUGAR",
        "CUPRA", "CVTE", "DAF", "DAIHATSU", "DENZA", "dr", "DS", "EBRO", "EXLANTIX", "FOXTRON", "FUSO", "FX11", "GAC", "GR",
        "GWM TANK", "GWM", "IDV", "ISUZU", "IVECO", "JBL", "JETOUR", "JMEV", "KENWOOD", "KGM", "LDV", "LEAPMOTOR", "LEPAR",
        "LEPAS", "LINKTOUR", "LUCID", "LUXEED", "MACK", "MACROM", "MAN", "MBUX", "MG", "MINI", "NEVO", "OMODA", "ORA", "POER",
        "PTW AS", "RAM", "RELY", "RIZON", "RKM", "ROEWE", "RR", "RSM", "SEAT", "SKODA", "smart", "SPORTEQUIPE", "SSL", "SUBARU",
        "SYMC", "SYNC", "TANK", "TENET", "TVS", "UNI", "VIDA", "VW", "WEY", "XZENT", "ZEEKR", "ZENEC", "ŠKODA",
    )

    /** The label for [mode] (null: [NAME]), or null for the generic one. */
    fun of(mode: String?, displayName: String, make: String): String? {
        val m = mode?.trim()?.lowercase()
        if (m == DEFAULT) return null
        if (m != MAKE && usable(displayName)) return pretty(displayName.trim())
        if (usable(make)) return pretty(make.trim())
        return null
    }

    private fun usable(s: String) = s.isNotBlank() && s.trim() !in NOT_A_BRAND

    /** "TOYOTA" and "toyota" read "Toyota", word by word; mixed case, and [AS_WRITTEN], stay as they are. */
    fun pretty(s: String): String {
        if (s in AS_WRITTEN || (s != s.lowercase() && s != s.uppercase())) return s
        return s.split(Regex("\\s+")).joinToString(" ") { w -> w.take(1).uppercase() + w.drop(1).lowercase() }
    }
}
