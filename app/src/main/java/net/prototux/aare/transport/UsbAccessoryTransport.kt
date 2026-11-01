package net.prototux.aare.transport

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import net.prototux.aare.aap.AapException
import net.prototux.aare.aap.Transport
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Wired head units switch the phone to USB accessory mode (AOA) with the strings
 * manufacturer "Android", model "Android Auto", then talk AAP over the bulk endpoints.
 */
class UsbAccessoryTransport(usb: UsbManager, val accessory: UsbAccessory) : Transport {
    private val fd: ParcelFileDescriptor = usb.openAccessory(accessory)
        ?: throw AapException("cannot open USB accessory ${accessory.model} (permission?)")

    override val name = "usb ${accessory.manufacturer} ${accessory.model} ${accessory.version}"

    // Reads from an accessory must use buffers of at least one USB bulk transfer (16 kB),
    // otherwise the kernel drops the rest of the transfer. FrameReader buffers 64 kB.
    override val input: InputStream = FileInputStream(fd.fileDescriptor)
    override val output: OutputStream = FileOutputStream(fd.fileDescriptor)

    @Volatile private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        // Closing the fd makes a blocked read() return with an error.
        runCatching { fd.close() }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    companion object {
        fun isAndroidAuto(a: UsbAccessory) =
            a.manufacturer.equals("Android", true) && (a.model ?: "").startsWith("Android Auto", true)
    }
}
