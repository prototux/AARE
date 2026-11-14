package net.prototux.aare.car

import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCheckTest {
    @Test
    fun waitsForBothHalves() {
        assertNull(PairingCheck.decide(null, Int.MIN_VALUE, "123456"))
        assertNull(PairingCheck.decide(PairingCheck.VARIANT_PASSKEY_CONFIRMATION, 123456, null))
        assertNull(PairingCheck.decide(PairingCheck.VARIANT_PIN, Int.MIN_VALUE, null))
    }

    @Test
    fun pinIsEnteredForTheCar() {
        assertEquals(PairingCheck.Decision("0000", MessageStatus.STATUS_SUCCESS),
            PairingCheck.decide(PairingCheck.VARIANT_PIN, Int.MIN_VALUE, "0000"))
    }

    @Test
    fun passkeysAreComparedAsNumbers() {
        val match = PairingCheck.Decision(null, MessageStatus.STATUS_SUCCESS)
        assertEquals(match, PairingCheck.decide(PairingCheck.VARIANT_PASSKEY_CONFIRMATION, 1234, "001234"))
        assertEquals(match, PairingCheck.decide(PairingCheck.VARIANT_PASSKEY_CONFIRMATION, 1234, "1234"))
        assertEquals(match, PairingCheck.decide(PairingCheck.VARIANT_PASSKEY_CONFIRMATION, 123456, "123 456"))
    }

    @Test
    fun differentPasskeyIsAMismatch() {
        assertEquals(PairingCheck.Decision(null, MessageStatus.STATUS_BLUETOOTH_AUTH_DATA_MISMATCH),
            PairingCheck.decide(PairingCheck.VARIANT_PASSKEY_CONFIRMATION, 1234, "1235"))
    }

    @Test
    fun aPasskeyThatIsNoNumberGetsNoAnswer() {
        assertNull(PairingCheck.decide(PairingCheck.VARIANT_PASSKEY_CONFIRMATION, 1234, "abc"))
        assertNull(PairingCheck.decide(PairingCheck.VARIANT_PASSKEY_CONFIRMATION, Int.MIN_VALUE, "123456"))
        assertNull(PairingCheck.decide(PairingCheck.VARIANT_PASSKEY_CONFIRMATION, 1234, "1".repeat(19)))
    }

    @Test
    fun otherVariantsGetNoAnswer() {
        // Consent (3) and passkey entry (1): nothing the car's data can check.
        assertNull(PairingCheck.decide(3, Int.MIN_VALUE, null))
        assertNull(PairingCheck.decide(3, Int.MIN_VALUE, "123456"))
        assertNull(PairingCheck.decide(1, Int.MIN_VALUE, "123456"))
    }
}
