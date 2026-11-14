package net.prototux.aare.aap

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** A byte pipe to a head unit: USB accessory, TCP (wireless or the developer head-unit server). */
interface Transport {
    val name: String
    val input: InputStream
    val output: OutputStream

    /** Closes both directions; must unblock a reader blocked in [input]. Idempotent. */
    fun close()
}

class SocketTransport(private val socket: Socket, override val name: String = "tcp ${socket.remoteSocketAddress}") : Transport {
    init {
        socket.tcpNoDelay = true
        // No SO_KEEPALIVE (none either way): its first probe comes after hours,
        // the AAP ping watchdog notices a dead head unit within seconds. No SO_TIMEOUT either,
        // although a 10 s read timeout is common: the watchdog deliberately allows 20 s of silence
        // for head units that go off channel to scan.
        // Big buffers, deliberately larger than a 16 KiB send buffer: video frames are tens
        // of kB and we don't want the writer to stall per frame.
        runCatching { socket.sendBufferSize = 512 * 1024 }
        runCatching { socket.receiveBufferSize = 256 * 1024 }
    }

    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()

    override fun close() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }
}
