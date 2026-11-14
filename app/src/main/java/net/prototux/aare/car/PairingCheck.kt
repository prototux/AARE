package net.prototux.aare.car

import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import java.util.Locale

/**
 * The phone's half of the car's Bluetooth authentication. Android's
 * pairing request gives the pairing variant and, for numeric comparison, the passkey; the car
 * sends the passkey or PIN it shows. Once both are in, [decide] says what to do and what to answer.
 */
object PairingCheck {
    // BluetoothDevice.PAIRING_VARIANT_*: only PIN and PASSKEY_CONFIRMATION are public API.
    const val VARIANT_PIN = 0
    const val VARIANT_PASSKEY_CONFIRMATION = 2

    /** Variants waited for the car's auth data on (PIN, passkey, display passkey/PIN, 16-digit PIN). */
    private val NEEDS_AUTH_DATA = setOf(0, 1, 2, 4, 5, 7)

    /** [pin] is the PIN to enter for the car, null for none; [result] is what to answer the car. */
    data class Decision(val pin: String?, val result: MessageStatus)

    /**
     * [variant] is null until Android's pairing request for the car came in; [authData] is null
     * until the car sent its own. Null while one of them is still needed.
     */
    fun decide(variant: Int?, passkey: Int, authData: String?): Decision? {
        if (variant == null) return null
        if (variant in NEEDS_AUTH_DATA && authData == null) return null
        return when (variant) {
            // Success is answered once the PIN is entered, whether Android took it or not.
            VARIANT_PIN -> Decision(authData, MessageStatus.STATUS_SUCCESS)
            // The car shows the passkey zero-padded to six digits.
            VARIANT_PASSKEY_CONFIRMATION -> Decision(null,
                if (String.format(Locale.US, "%06d", passkey) == authData) MessageStatus.STATUS_SUCCESS
                else MessageStatus.STATUS_BLUETOOTH_AUTH_DATA_MISMATCH)
            else -> Decision(null, MessageStatus.STATUS_BLUETOOTH_INVALID_PAIRING_METHOD)
        }
    }
}
