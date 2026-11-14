package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DtcCodesTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun decodesTheFourSystemLetters() {
        // P0128 (coolant thermostat), U0100 (lost comm with ECM/PCM), C0035, B0001: see DtcCodes' kdoc for the bit layout.
        assertEquals(listOf("P0128"), DtcCodes.decode(bytes(0x01, 0x28)))
        assertEquals(listOf("U0100"), DtcCodes.decode(bytes(0xC1, 0x00)))
        assertEquals(listOf("C0035"), DtcCodes.decode(bytes(0x40, 0x35)))
        assertEquals(listOf("B0001"), DtcCodes.decode(bytes(0x80, 0x01)))
    }

    @Test
    fun decodesAManufacturerSpecificCode() {
        // digit 1 (bits 5-4 of the first byte) = 1: manufacturer-specific, per J2012.
        assertEquals(listOf("P1300"), DtcCodes.decode(bytes(0x13, 0x00)))
    }

    @Test
    fun decodesSeveralCodesConcatenated() {
        assertEquals(listOf("P0128", "U0100"), DtcCodes.decode(bytes(0x01, 0x28, 0xC1, 0x00)))
    }

    @Test
    fun aTrailingOddByteIsDropped() {
        assertEquals(listOf("P0128"), DtcCodes.decode(bytes(0x01, 0x28, 0x00)))
    }

    @Test
    fun emptyBytesDecodeToNoCodes() {
        assertTrue(DtcCodes.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun familyNamesTheFourSystems() {
        assertEquals("powertrain", DtcCodes.family("P0128"))
        assertEquals("chassis", DtcCodes.family("C0035"))
        assertEquals("body", DtcCodes.family("B0001"))
        assertEquals("network", DtcCodes.family("U0100"))
    }

    @Test
    fun genericFollowsTheJ2012RangesOfEachSystem() {
        // Powertrain: P0, P2 and P34-P39 generic; P1 and P30-P33 manufacturer-specific.
        for (code in listOf("P0128", "P2201", "P3400", "P3999")) assertTrue(code, DtcCodes.isGeneric(code))
        for (code in listOf("P1300", "P3000", "P3300", "P33FF")) assertFalse(code, DtcCodes.isGeneric(code))
        // Chassis, body, network: 0 and 3 generic; 1 and 2 manufacturer-specific.
        for (code in listOf("B0001", "B3000", "C0035", "C3000", "U0100", "U3000")) assertTrue(code, DtcCodes.isGeneric(code))
        for (code in listOf("B1000", "B2000", "C1000", "C2000", "U1000", "U2000")) assertFalse(code, DtcCodes.isGeneric(code))
        assertFalse(DtcCodes.isGeneric(""))
        assertFalse(DtcCodes.isGeneric("X0000"))
    }

    @Test
    fun theShippedCatalogueParsesIntoUniqueWellFormedGenericKeys() {
        val json = File("src/main/assets/dtc/generic.json").readText()
        val catalogue = DtcCodes.Catalogue.parse(json)
        // Roughly the size asked for: not a handful, not thousands.
        assertTrue("expected a few hundred entries, found ${catalogue.size}", catalogue.size in 250..450)
        for ((code, description) in catalogue) {
            assertTrue("key \"$code\" isn't a well-formed code", Regex("[PCBU][0-9A-F]{4}").matches(code))
            assertTrue("\"$code\" isn't classified generic, so it shouldn't be in the catalogue", DtcCodes.isGeneric(code))
            assertTrue("\"$code\" has a blank description", description.isNotBlank())
        }
        assertEquals("duplicate keys in the catalogue", catalogue.keys.size, catalogue.keys.toSet().size)
    }

    @Test
    fun parseSkipsABlankDescriptionRatherThanKeepingAnEmptyOne() {
        val catalogue = DtcCodes.Catalogue.parse("""{"P0128": "Coolant thermostat below regulating temperature", "P0999": ""}""")
        assertEquals(setOf("P0128"), catalogue.keys)
    }
}
