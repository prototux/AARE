package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** The "Exit to car" entry named after the car. */
class ExitLabelTest {
    @Test fun theGenericLabelWithoutTheCarFixes() {
        assertEquals(null, ExitLabel.of(null, "My Mazda", "Mazda", carFixes = false))
        // A rule still names it.
        assertEquals("Audi", ExitLabel.of(ExitLabel.MAKE, "MMI", "Audi", carFixes = false))
    }

    @Test fun theDisplayNameElseTheMakeWithTheCarFixes() {
        assertEquals("My Mazda", ExitLabel.of(null, "My Mazda", "Mazda", carFixes = true))
        assertEquals("Mazda", ExitLabel.of(null, "", "Mazda", carFixes = true))
        assertEquals(null, ExitLabel.of(null, " ", "", carFixes = true))
    }

    @Test fun aRuleCanAskForTheMakeOrTheGenericLabel() {
        assertEquals("Audi", ExitLabel.of(ExitLabel.MAKE, "MMI", "Audi", carFixes = true))
        assertEquals(null, ExitLabel.of(ExitLabel.DEFAULT, "My Chevy", "Chevrolet", carFixes = true))
    }

    @Test fun valuesThatArentABrandAreSkipped() {
        assertEquals("CHERY", ExitLabel.of(null, "Chery Automobile Co.,Ltd", "CHERY", carFixes = true))
        assertEquals(null, ExitLabel.of(null, "", "OEM Infotainment", carFixes = true))
    }

    @Test fun capitalsAreTamedButBrandsKeepTheirSpelling() {
        assertEquals("Toyota", ExitLabel.pretty("TOYOTA"))
        assertEquals("Land Rover", ExitLabel.pretty("land rover"))
        assertEquals("BMW", ExitLabel.pretty("BMW"))
        assertEquals("ŠKODA", ExitLabel.pretty("ŠKODA"))
        assertEquals("McLaren", ExitLabel.pretty("McLaren"))
    }
}
