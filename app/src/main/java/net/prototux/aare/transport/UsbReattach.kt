package net.prototux.aare.transport

import android.hardware.usb.UsbAccessory

/**
 * Whether the head unit's USB accessory is still attached after a session ended, to retry it.
 *
 * `UsbAccessory.equals` compares serials, and `getSerial()` needs the USB permission, which
 * Android has already taken back once the accessory went away: `accessoryList.contains(acc)`
 * then threw a SecurityException and crashed the app at the end of every USB session (#152).
 * One phone has one accessory port, so the other fields are enough to tell it is the same one.
 */
internal object UsbReattach {
    data class Id(val manufacturer: String?, val model: String?, val description: String?, val version: String?, val uri: String?)

    fun id(a: UsbAccessory) = Id(a.manufacturer, a.model, a.description, a.version, a.uri)

    /** [wanted] is in [attached]; false when the list can't be read. */
    fun stillAttached(wanted: Id, attached: () -> List<Id>?): Boolean =
        runCatching { attached()?.contains(wanted) == true }.getOrDefault(false)
}
