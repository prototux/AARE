package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** The "Exit to car" entry named after the car. */
class ExitLabelTest {
    @Test fun theDisplayNameElseTheMake() {
        assertEquals("My Mazda", ExitLabel.of(null, "My Mazda", "Mazda"))
        assertEquals("Mazda", ExitLabel.of(null, "", "Mazda"))
        assertEquals(null, ExitLabel.of(null, " ", ""))
    }

    @Test fun aRuleCanAskForTheMakeOrTheGenericLabel() {
        assertEquals("Audi", ExitLabel.of(ExitLabel.MAKE, "MMI", "Audi"))
        assertEquals(null, ExitLabel.of(ExitLabel.DEFAULT, "My Chevy", "Chevrolet"))
    }

    @Test fun valuesThatArentABrandAreSkipped() {
        assertEquals("CHERY", ExitLabel.of(null, "Chery Automobile Co.,Ltd", "CHERY"))
        assertEquals(null, ExitLabel.of(null, "", "OEM Infotainment"))
    }

    @Test fun capitalsAreTamedButBrandsKeepTheirSpelling() {
        assertEquals("Toyota", ExitLabel.pretty("TOYOTA"))
        assertEquals("Land Rover", ExitLabel.pretty("land rover"))
        assertEquals("BMW", ExitLabel.pretty("BMW"))
        assertEquals("ŠKODA", ExitLabel.pretty("ŠKODA"))
        assertEquals("McLaren", ExitLabel.pretty("McLaren"))
    }
}
