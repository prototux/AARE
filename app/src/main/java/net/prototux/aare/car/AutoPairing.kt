package net.prototux.aare.car

/**
 * The rules of the opt-in automatic Bluetooth pairing with the connected car ([BluetoothAutoPair]):
 * only the address the head unit announced on its AAP Bluetooth channel, only while the phone
 * isn't paired with it, and a pairing request is only confirmed when its passkey is the one the
 * car sent over AAP. Anything else is left to Android's own pairing dialog.
 */
internal object AutoPairing {
    /** BluetoothDevice.PAIRING_VARIANT_PIN and PAIRING_VARIANT_PASSKEY_CONFIRMATION. */
    const val VARIANT_PIN = 0
    const val VARIANT_PASSKEY_CONFIRMATION = 2

    private val ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    /** Whether to pair with [carAddress] (as announced on the channel) at all. */
    fun shouldStart(enabled: Boolean, shizukuAvailable: Boolean, carAddress: String?, alreadyPaired: Boolean, bonded: Boolean): Boolean =
        enabled && shizukuAvailable && !alreadyPaired && !bonded && carAddress != null && validAddress(carAddress)

    fun validAddress(address: String) = ADDRESS.matches(address.uppercase()) && address != "00:00:00:00:00:00"

    sealed interface Answer {
        /** Confirm the numeric comparison: its passkey is the car's. */
        data object Confirm : Answer
        data class Pin(val pin: String) : Answer
        /** The car's passkey hasn't come over AAP yet: answer once it does. */
        data object Wait : Answer
        /** Leave the request to Android's dialog. */
        data class Leave(val why: String) : Answer
    }

    /**
     * How to answer a pairing request from [requestAddress] ([variant], with Android's [key] for a
     * numeric comparison), given the car's [authData] from AAP (null: not sent yet).
     */
    fun answer(carAddress: String, requestAddress: String, variant: Int, key: Int?, authData: String?): Answer {
        if (!requestAddress.equals(carAddress, ignoreCase = true)) return Answer.Leave("not the connected car")
        return when (variant) {
            VARIANT_PASSKEY_CONFIRMATION -> when {
                key == null -> Answer.Leave("no passkey in the request")
                authData == null -> Answer.Wait
                passkey(authData) == key -> Answer.Confirm
                else -> Answer.Leave("the passkey isn't the one the car sent")
            }
            VARIANT_PIN -> when {
                authData == null -> Answer.Wait
                authData.isNotBlank() && authData.trim().length <= 16 -> Answer.Pin(authData.trim())
                else -> Answer.Leave("the car sent no usable PIN")
            }
            else -> Answer.Leave("pairing variant $variant isn't handled")
        }
    }

    /** The car's numeric passkey; it may come zero-padded ("042137"). */
    private fun passkey(authData: String): Int? = authData.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 6 }?.toInt()
}
