package net.prototux.aare.car

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
}
