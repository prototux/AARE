package net.prototux.aare.transport

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import net.prototux.aare.aap.AapException
import net.prototux.aare.aap.Transport
import java.io.FileInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Wired head units switch the phone to USB accessory mode (AOA) with the strings
 * manufacturer "Android", model "Android Auto" (see [isAndroidAuto]), then talk AAP over the
 * bulk endpoints.
 */
class UsbAccessoryTransport(usb: UsbManager, val accessory: UsbAccessory) : Transport {
    private val fd: ParcelFileDescriptor = usb.openAccessory(accessory)
        ?: throw AapException("cannot open USB accessory ${accessory.model} (permission?)")

    override val name = "usb ${accessory.manufacturer} ${accessory.model} ${accessory.version}"

    // Reads from an accessory must use buffers of at least one USB bulk transfer (16 kB),
    // otherwise the kernel drops the rest of the transfer. FrameReader buffers 64 kB.
    override val input: InputStream = FileInputStream(fd.fileDescriptor)
    // The accessory fd has no kernel send buffer (unlike a TCP socket), so an unbuffered write
    // blocks per fragment on the USB bulk endpoint. Buffer so FrameWriter's per-fragment writes
    // coalesce into one write per message at its flush() — fewer blocking syscalls on the pipe.
    override val output: OutputStream = BufferedOutputStream(FileOutputStream(fd.fileDescriptor), 64 * 1024)

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing the fd makes a blocked read() return with an error.
        runCatching { fd.close() }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    companion object {
        /** Kept in step with res/xml/accessory_filter.xml (UsbAccessoryMatchTest checks it). */
        internal val MODELS = setOf("Android Auto", "Android Open Automotive Protocol")

        /**
         * An accessory to project to: matched on the exact model only, so
         * a head unit with another manufacturer string works too. The plain "Android" model,
         * which is ignored by default, is not one.
         */
        fun isAndroidAuto(a: UsbAccessory) = isAndroidAutoModel(a.model)

        internal fun isAndroidAutoModel(model: String?) = model in MODELS
    }
}
