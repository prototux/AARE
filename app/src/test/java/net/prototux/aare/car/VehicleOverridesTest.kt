package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matching a head unit against a vehicles.json rule. */
class VehicleOverridesTest {
    private fun rule(make: String? = null, model: String? = null, name: String? = null) =
        VehicleRule(make, model, name, VehicleOverride())

    @Test fun matchesByMakeSubstringCaseInsensitive() =
        assertTrue(rule(make = "ford").matches("Ford Motor Company", "Focus", "SYNC 3"))

    @Test fun everySetFieldMustMatch() {
        assertTrue(rule(make = "Ford", name = "SYNC").matches("Ford", "Focus", "SYNC 3"))
        assertFalse(rule(make = "Ford", name = "MMI").matches("Ford", "Focus", "SYNC 3"))
    }

    @Test fun unsetFieldsAreIgnored() =
        assertTrue(rule(model = "Mach-E").matches("Ford", "Mustang Mach-E", "SYNC 4"))

    @Test fun anEmptyRuleNeverMatches() =
        assertFalse(rule().matches("Ford", "Focus", "SYNC"))

    @Test fun aMismatchFails() =
        assertFalse(rule(make = "Audi").matches("Ford", "Focus", "SYNC"))

    @Test fun servicesAreNamedAsTheLogWritesThem() {
        val o = VehicleOverride(skipServices = setOf("vendor", "audio:AUDIO_STREAM_GUIDANCE"), openServices = setOf("WIFI"))
        assertTrue(o.skips("vendor"))
        assertTrue(o.skips("audio:AUDIO_STREAM_GUIDANCE"))
        assertFalse(o.skips("audio:AUDIO_STREAM_MEDIA"))
        assertTrue(o.opens("wifi"))
        assertFalse(o.opens("radio"))
    }

    @Test fun aFamilyNameCoversEveryServiceOfIt() {
        assertTrue(VehicleOverride.matchesKind("audio", "audio:AUDIO_STREAM_MEDIA"))
        assertFalse(VehicleOverride.matchesKind("audio", "video"))
        assertFalse(VehicleOverride.matchesKind("vend", "vendor"))
    }

    @Test fun theUsersRuleWinsFieldByFieldOverTheBuiltInOne() {
        val builtIn = listOf(VehicleRule("Volkswagen", null, "MIB Regio", VehicleOverride(micSetup = false, dpi = 140)))
        val user = listOf(VehicleRule(null, null, "MIB Regio", VehicleOverride(dpi = 160)))
        val o = VehicleOverrides.resolve(user, builtIn, "Volkswagen", "VW_7_A0_HATCHBACK", "Volkswagen VW_7_A0_HATCHBACK Visteon MIB Regio")!!
        assertEquals(160, o.dpi)
        assertEquals(false, o.micSetup)
    }

    @Test fun eitherFileAloneApplies() {
        val rule = listOf(VehicleRule("Ford", null, null, VehicleOverride(maxHeight = 720)))
        assertEquals(720, VehicleOverrides.resolve(rule, emptyList(), "Ford", "Focus", "SYNC 3")!!.maxHeight)
        assertEquals(720, VehicleOverrides.resolve(emptyList(), rule, "Ford", "Focus", "SYNC 3")!!.maxHeight)
        assertEquals(null, VehicleOverrides.resolve(emptyList(), rule, "Audi", "A3", "MMI"))
    }
}
