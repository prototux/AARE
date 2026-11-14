package net.prototux.aare.car

import android.os.Build

/**
 * When the opt-in automatic Bluetooth pairing with the connected car ([BluetoothAutoPair]) starts:
 * only for the address the head unit announced on its AAP Bluetooth channel, and only while the
 * phone isn't paired with it. Whether a pairing request is answered is [PairingCheck]'s call.
 */
internal object AutoPairing {
    private val ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    /** Whether to pair with [carAddress] (as announced on the channel) at all. */
    fun shouldStart(enabled: Boolean, shizukuAvailable: Boolean, carAddress: String?, alreadyPaired: Boolean, bonded: Boolean): Boolean =
        enabled && shizukuAvailable && !alreadyPaired && !bonded && carAddress != null && validAddress(carAddress)

    fun validAddress(address: String) = ADDRESS.matches(address.uppercase()) && address != "00:00:00:00:00:00"

    /**
     * For the log, why an opted-in pairing with an unpaired car didn't start. AARE uses Shizuku
     * only on Android 12+ (CaptureMethods.shizukuAvailable), so on 10 or 11 that is the reason,
     * not Shizuku itself.
     */
    fun whyNot(sdk: Int, shizukuAvailable: Boolean): String = when {
        sdk < Build.VERSION_CODES.S -> "it needs Android 12 or later"
        !shizukuAvailable -> "Shizuku isn't available"
        else -> "no usable car address"
    }
}
