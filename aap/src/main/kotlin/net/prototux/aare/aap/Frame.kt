package net.prototux.aare.aap

/**
 * AAP framing.
 *
 * Every message travels in one or more frames:
 *
 * ```
 * byte 0      channel id
 * byte 1      flags: bit0 FIRST, bit1 LAST, bit2 CONTROL (channel-control message on a service
 *             channel, e.g. channel open), bit3 ENCRYPTED
 * bytes 2-3   frame payload size, big endian
 * bytes 4-7   only on the FIRST frame of a fragmented message (FIRST set, LAST clear):
 *             total plaintext size of the whole message, big endian
 * payload     plaintext, or one TLS record when ENCRYPTED
 * ```
 *
 * The reassembled plaintext of a message starts with a 2-byte big-endian message id, followed by
 * the protobuf body (or raw bytes for media and TLS handshake messages).
 */
object Frame {
    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02
    const val FLAG_CONTROL = 0x04
    const val FLAG_ENCRYPTED = 0x08

    /** Largest whole frame (header included) the official phone sends; some head units size their receive buffer to it. */
    const val MAX_FRAME = 16128

    /** Bytes a TLS 1.2 AES-GCM record adds around each encrypted chunk: 5 header + 8 explicit nonce + 16 tag. */
    const val TLS_OVERHEAD = 5 + 8 + 16

    /**
     * Largest plaintext chunk put in a single frame, so that a whole encrypted frame (header of a
     * FIRST fragment + TLS record) never exceeds [MAX_FRAME]. Without the TLS overhead taken off,
     * encrypted fragments came out at 16157 bytes.
     */
    const val MAX_PAYLOAD = MAX_FRAME - 8 - TLS_OVERHEAD // 8: channel, flags, size, total size

    const val HEADER_SIZE = 2
    const val SHORT_SIZE = 2
    const val EXTENDED_SIZE = 6

    fun isFirst(flags: Int) = flags and FLAG_FIRST != 0
    fun isLast(flags: Int) = flags and FLAG_LAST != 0
    fun isControl(flags: Int) = flags and FLAG_CONTROL != 0
    fun isEncrypted(flags: Int) = flags and FLAG_ENCRYPTED != 0

    /** First fragment of a multi-frame message: it carries the extended (total size) field. */
    fun hasTotalSize(flags: Int) = isFirst(flags) && !isLast(flags)
}

/**
 * A complete AAP message.
 *
 * @param channel channel id (0 = control channel)
 * @param control true for channel-control messages sent on a service channel (frame flag bit 2)
 * @param encrypted whether it travels inside TLS
 * @param id message id (first 2 bytes of the plaintext)
 * @param body message body after the id
 */
class AapMessage(
    val channel: Int,
    val control: Boolean,
    val encrypted: Boolean,
    val id: Int,
    val body: ByteArray,
    val bodyOffset: Int = 0,
    val bodyLength: Int = body.size - bodyOffset,
) {
    /** Body bytes as an independent array. */
    fun bodyBytes(): ByteArray =
        if (bodyOffset == 0 && bodyLength == body.size) body else body.copyOfRange(bodyOffset, bodyOffset + bodyLength)

    override fun toString(): String =
        "AapMessage(ch=$channel, id=0x${id.toString(16)}, ${if (control) "ctl " else ""}${if (encrypted) "enc " else ""}len=$bodyLength)"

    companion object {
        /** Builds the plaintext (id + body) of a message. */
        fun plaintext(id: Int, body: ByteArray, offset: Int = 0, length: Int = body.size - offset): ByteArray {
            val out = ByteArray(2 + length)
            out[0] = (id shr 8).toByte()
            out[1] = id.toByte()
            System.arraycopy(body, offset, out, 2, length)
            return out
        }
    }
}
