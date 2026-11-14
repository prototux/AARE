package net.prototux.aare.car

import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus

/**
 * The phone's half of the car's Bluetooth authentication. Android's
 * pairing request gives the pairing variant and, for numeric comparison, the passkey; the car
 * sends the passkey or PIN it shows. Once both are in, [decide] says what to do and what to answer.
 */
object PairingCheck {
    // BluetoothDevice.PAIRING_VARIANT_*: only PIN and PASSKEY_CONFIRMATION are public API.
    const val VARIANT_PIN = 0
    const val VARIANT_PASSKEY_CONFIRMATION = 2

    /** [pin] is the PIN to enter for the car, null for none; [result] is what to answer the car. */
    data class Decision(val pin: String?, val result: MessageStatus)

    /**
     * [variant] is null until Android's pairing request for the car came in; [authData] is null
     * until the car sent its own. Null while one of them is still needed, and for anything the
     * car's data can't check (consent and the other variants, a passkey that isn't a number):
     * then nothing is entered and the car gets no answer, which is what head units got before.
     */
    fun decide(variant: Int?, passkey: Int, authData: String?): Decision? {
        if (variant == null || authData == null) return null
        return when (variant) {
            // Success is answered once the PIN is entered, whether Android took it or not.
            VARIANT_PIN -> Decision(authData, MessageStatus.STATUS_SUCCESS)
            VARIANT_PASSKEY_CONFIRMATION -> {
                // Compared as numbers: the car may pad, space or group the digits it shows.
                val theirs = authData.filter { it in '0'..'9' }.takeIf { it.isNotEmpty() && it.length <= 18 }?.toLong()
                if (passkey < 0 || theirs == null) null
                else Decision(null, if (theirs == passkey.toLong()) MessageStatus.STATUS_SUCCESS else MessageStatus.STATUS_BLUETOOTH_AUTH_DATA_MISMATCH)
            }
            else -> null
        }
    }
}
