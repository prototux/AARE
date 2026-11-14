package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

/** Non-square car pixels. */
class PixelAspectTest {
    @Test fun fromProtocol12TheHeadUnitsValueIsUsed() {
        assertEquals(1.15f, PixelAspect.factor(null, 11_500, 1 to 2, "Mazda"), 1e-6f)
        assertEquals(0.9f, PixelAspect.factor(null, 9_000, 6 to 1, "Mazda"), 1e-6f)
        assertEquals(1f, PixelAspect.factor(null, null, 6 to 1, "Mazda"), 0f)
    }

    @Test fun beforeProtocol12OnlyAKnownCarGetsOne() {
        assertEquals(1f, PixelAspect.factor(null, 11_500, 1 to 1, "Mazda"), 0f)
        // A vehicles.json rule (the built-in table) sets it.
        assertEquals(1.15f, PixelAspect.factor(1.15f, null, 1 to 1, "MITSUBISHI MOTORS"), 1e-6f)
        // Without a make, the head unit's own value is used.
        assertEquals(1.1f, PixelAspect.factor(null, 11_000, 1 to 0, ""), 1e-6f)
    }

    @Test fun anOverrideWinsAndNonsenseIsIgnored() {
        assertEquals(1f, PixelAspect.factor(1f, 11_500, 6 to 1, "Mazda"), 0f)
        assertEquals(1f, PixelAspect.factor(null, 0, 6 to 1, "Mazda"), 0f)
        assertEquals(1f, PixelAspect.factor(null, 100_000, 6 to 1, "Mazda"), 0f)
    }

    @Test fun wideSquarePixelsWidenTheLayoutTallOnesHeightenIt() {
        assertEquals(1472 to 720, PixelAspect.logicalSize(1280, 720, 1.15f))
        assertEquals(800 to 533, PixelAspect.logicalSize(800, 480, 0.9f))
        assertEquals(800 to 480, PixelAspect.logicalSize(800, 480, 1f))
    }
}
