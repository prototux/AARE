package net.prototux.aare.transport.wireless

/**
 * How a car's Wi-Fi network request gets approved. Android shows its "connect to device" dialog
 * for a WifiNetworkSpecifier request unless this app already got approval for that SSID and BSSID,
 * or a companion association links the app to the access point's MAC address. Associating the car
 * gives the car's *Bluetooth* address, which head units rarely reuse for their access point; so
 * for an associated car the user can let Shizuku answer the dialog's question instead.
 */
internal object WifiApproval {
    enum class Way {
        /** Android decides, asking the user unless it remembers an approval: today's behaviour. */
        DIALOG,
        /** A companion association covers the access point's own MAC: Android approves by itself. */
        COMPANION,
        /** An associated car, opted in, Shizuku usable: the shell approves this one request. */
        SHIZUKU,
    }

    /**
     * [associated]: the MAC addresses of AARE's companion associations. [carAddress]: the Bluetooth
     * address of the car being joined; [bssid]: its access point, if the car sent one.
     */
    fun way(associated: Set<String>, carAddress: String, bssid: String?, shizukuOptIn: Boolean, shizukuAvailable: Boolean): Way {
        val macs = associated.mapTo(HashSet()) { it.uppercase() }
        return when {
            bssid != null && bssid.uppercase() in macs -> Way.COMPANION
            carAddress.uppercase() in macs && shizukuOptIn && shizukuAvailable -> Way.SHIZUKU
            else -> Way.DIALOG
        }
    }
}
