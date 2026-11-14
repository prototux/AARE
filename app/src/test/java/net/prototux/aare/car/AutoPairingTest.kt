package net.prototux.aare.car

import net.prototux.aare.car.AutoPairing.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPairingTest {
    private val car = "AA:BB:CC:00:11:22"

    @Test
    fun startsOnlyWhenOptedInAndNotPaired() {
        assertTrue(AutoPairing.shouldStart(enabled = true, shizukuAvailable = true, carAddress = car, alreadyPaired = false, bonded = false))
        assertFalse("off by default is off", AutoPairing.shouldStart(enabled = false, shizukuAvailable = true, carAddress = car, alreadyPaired = false, bonded = false))
        assertFalse(AutoPairing.shouldStart(enabled = true, shizukuAvailable = false, carAddress = car, alreadyPaired = false, bonded = false))
        assertFalse("the car says it is paired", AutoPairing.shouldStart(enabled = true, shizukuAvailable = true, carAddress = car, alreadyPaired = true, bonded = false))
        assertFalse("the phone has the bond", AutoPairing.shouldStart(enabled = true, shizukuAvailable = true, carAddress = car, alreadyPaired = false, bonded = true))
        listOf(null, "", "not an address", "00:00:00:00:00:00", "AA:BB:CC:00:11").forEach {
            assertFalse("$it", AutoPairing.shouldStart(enabled = true, shizukuAvailable = true, carAddress = it, alreadyPaired = false, bonded = false))
        }
    }

    @Test
    fun neverAnswersForAnotherDevice() {
        assertEquals(Answer.Leave("not the connected car"),
            AutoPairing.answer(car, "11:22:33:44:55:66", AutoPairing.VARIANT_PASSKEY_CONFIRMATION, 123456, "123456"))
    }

    @Test
    fun confirmsOnlyTheCarsPasskey() {
        assertEquals(Answer.Confirm, AutoPairing.answer(car, car.lowercase(), AutoPairing.VARIANT_PASSKEY_CONFIRMATION, 123456, "123456"))
        assertEquals("zero-padded", Answer.Confirm, AutoPairing.answer(car, car, AutoPairing.VARIANT_PASSKEY_CONFIRMATION, 42137, "042137"))
        assertTrue(AutoPairing.answer(car, car, AutoPairing.VARIANT_PASSKEY_CONFIRMATION, 123456, "654321") is Answer.Leave)
        assertTrue(AutoPairing.answer(car, car, AutoPairing.VARIANT_PASSKEY_CONFIRMATION, 123456, "abc") is Answer.Leave)
        assertTrue(AutoPairing.answer(car, car, AutoPairing.VARIANT_PASSKEY_CONFIRMATION, null, "123456") is Answer.Leave)
        assertEquals("the car's code isn't there yet", Answer.Wait, AutoPairing.answer(car, car, AutoPairing.VARIANT_PASSKEY_CONFIRMATION, 123456, null))
    }

    @Test
    fun pinFromTheCar() {
        assertEquals(Answer.Pin("0000"), AutoPairing.answer(car, car, AutoPairing.VARIANT_PIN, null, " 0000 "))
        assertEquals(Answer.Wait, AutoPairing.answer(car, car, AutoPairing.VARIANT_PIN, null, null))
        assertTrue(AutoPairing.answer(car, car, AutoPairing.VARIANT_PIN, null, "") is Answer.Leave)
    }

    @Test
    fun otherVariantsAreLeftToAndroid() {
        assertTrue(AutoPairing.answer(car, car, 1, 123456, "123456") is Answer.Leave)
        assertTrue(AutoPairing.answer(car, car, -1, null, "123456") is Answer.Leave)
    }
}
