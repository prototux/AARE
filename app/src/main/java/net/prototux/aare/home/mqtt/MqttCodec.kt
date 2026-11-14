package net.prototux.aare.home.mqtt

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** The MQTT 3.1.1 control packets AARE sends or reads (no QoS 2, no will message). */
sealed interface MqttPacket {
    class Connect(
        val clientId: String,
        val username: String? = null,
        val password: String? = null,
        val keepAliveS: Int = 30,
        val cleanSession: Boolean = true,
    ) : MqttPacket

    data class ConnAck(val sessionPresent: Boolean, val returnCode: Int) : MqttPacket

    class Publish(
        val topic: String,
        val payload: ByteArray,
        val qos: Int = 0,
        val retain: Boolean = false,
        val dup: Boolean = false,
        /** Only for QoS 1; 0 otherwise. */
        val packetId: Int = 0,
    ) : MqttPacket

    data class PubAck(val packetId: Int) : MqttPacket
    data class Subscribe(val packetId: Int, val filters: List<Pair<String, Int>>) : MqttPacket
    /** One return code per filter: the granted QoS, or [MqttCodec.SUBACK_FAILURE]. */
    data class SubAck(val packetId: Int, val returnCodes: List<Int>) : MqttPacket
    data object PingReq : MqttPacket
    data object PingResp : MqttPacket
    data object Disconnect : MqttPacket
    /** A packet type AARE doesn't use (QoS 2's PUBREC…): read and skipped. */
    data class Other(val type: Int) : MqttPacket
}

class MqttProtocolException(message: String) : IOException(message)

/**
 * MQTT 3.1.1 packet encoding and decoding: pure, over bytes and streams, so it is tested without a
 * broker. Each packet is a fixed header (type and flags, then the "remaining length" as a 1–4 byte
 * varint) and a body.
 */
object MqttCodec {
    private const val CONNECT = 1
    private const val CONNACK = 2
    private const val PUBLISH = 3
    private const val PUBACK = 4
    private const val SUBSCRIBE = 8
    private const val SUBACK = 9
    private const val PINGREQ = 12
    private const val PINGRESP = 13
    private const val DISCONNECT = 14

    /** The largest remaining length the varint can carry (four bytes). */
    const val MAX_REMAINING_LENGTH = 268_435_455
    const val SUBACK_FAILURE = 0x80

    fun encode(p: MqttPacket): ByteArray = when (p) {
        is MqttPacket.Connect -> packet(CONNECT shl 4, connectBody(p))
        is MqttPacket.ConnAck -> packet(CONNACK shl 4, byteArrayOf(if (p.sessionPresent) 1 else 0, p.returnCode.toByte()))
        is MqttPacket.Publish -> {
            require(p.qos in 0..1) { "QoS ${p.qos} isn't supported" }
            val flags = (if (p.dup) 0x08 else 0) or (p.qos shl 1) or (if (p.retain) 1 else 0)
            val body = ByteArrayOutputStream()
            body.writeString(p.topic)
            if (p.qos > 0) body.writeShort(p.packetId)
            body.write(p.payload)
            packet((PUBLISH shl 4) or flags, body.toByteArray())
        }
        is MqttPacket.PubAck -> packet(PUBACK shl 4, shortBytes(p.packetId))
        is MqttPacket.Subscribe -> {
            require(p.filters.isNotEmpty()) { "SUBSCRIBE needs a topic filter" }
            val body = ByteArrayOutputStream()
            body.writeShort(p.packetId)
            p.filters.forEach { (filter, qos) -> body.writeString(filter); body.write(qos) }
            // SUBSCRIBE's reserved flags must be 0010.
            packet((SUBSCRIBE shl 4) or 0x02, body.toByteArray())
        }
        is MqttPacket.SubAck -> packet(SUBACK shl 4, shortBytes(p.packetId) + ByteArray(p.returnCodes.size) { p.returnCodes[it].toByte() })
        MqttPacket.PingReq -> packet(PINGREQ shl 4, ByteArray(0))
        MqttPacket.PingResp -> packet(PINGRESP shl 4, ByteArray(0))
        MqttPacket.Disconnect -> packet(DISCONNECT shl 4, ByteArray(0))
        is MqttPacket.Other -> throw IllegalArgumentException("can't encode packet type ${p.type}")
    }

    /**
     * Reads one packet. A remaining length over [maxLength] is refused before anything is
     * allocated, so a broker can't make the phone buffer a huge message.
     */
    fun read(input: InputStream, maxLength: Int): MqttPacket {
        val header = input.read()
        if (header < 0) throw EOFException("connection closed")
        val length = readRemainingLength(input)
        if (length > maxLength) throw MqttProtocolException("packet of $length bytes is over the $maxLength-byte limit")
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(body, off, length - off)
            if (n < 0) throw EOFException("connection closed mid-packet")
            off += n
        }
        return decode(header, body)
    }

    /** Decodes a packet from its first header byte and its body. */
    fun decode(header: Int, body: ByteArray): MqttPacket {
        val type = (header shr 4) and 0x0F
        val flags = header and 0x0F
        val r = Reader(body)
        return when (type) {
            CONNECT -> decodeConnect(r)
            CONNACK -> MqttPacket.ConnAck(r.byte() and 1 == 1, r.byte())
            PUBLISH -> {
                val qos = (flags shr 1) and 0x03
                if (qos > 1) throw MqttProtocolException("QoS $qos isn't supported")
                val topic = r.string()
                val id = if (qos > 0) r.short() else 0
                MqttPacket.Publish(topic, r.rest(), qos, retain = flags and 1 == 1, dup = flags and 0x08 != 0, packetId = id)
            }
            PUBACK -> MqttPacket.PubAck(r.short())
            SUBSCRIBE -> {
                val id = r.short()
                val filters = ArrayList<Pair<String, Int>>()
                while (r.remaining > 0) filters += r.string() to r.byte()
                MqttPacket.Subscribe(id, filters)
            }
            SUBACK -> {
                val id = r.short()
                MqttPacket.SubAck(id, r.rest().map { it.toInt() and 0xFF })
            }
            PINGREQ -> MqttPacket.PingReq
            PINGRESP -> MqttPacket.PingResp
            DISCONNECT -> MqttPacket.Disconnect
            else -> MqttPacket.Other(type)
        }
    }

    /** The remaining length as MQTT's varint: 7 bits per byte, low bits first, the top bit meaning "more". */
    fun encodeRemainingLength(length: Int): ByteArray {
        require(length in 0..MAX_REMAINING_LENGTH) { "remaining length $length out of range" }
        val out = ByteArrayOutputStream(4)
        var x = length
        do {
            var b = x and 0x7F
            x = x shr 7
            if (x > 0) b = b or 0x80
            out.write(b)
        } while (x > 0)
        return out.toByteArray()
    }

    fun readRemainingLength(input: InputStream): Int {
        var value = 0
        var shift = 0
        repeat(4) {
            val b = input.read()
            if (b < 0) throw EOFException("connection closed in a packet header")
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
        }
        throw MqttProtocolException("remaining length longer than four bytes")
    }

    /** What a CONNACK's refusal means, for the connection log. */
    fun connAckReason(code: Int): String = when (code) {
        0 -> "accepted"
        1 -> "protocol version refused"
        2 -> "client id refused"
        3 -> "server unavailable"
        4 -> "bad user name or password"
        5 -> "not authorised"
        else -> "refused ($code)"
    }

    /** A topic one can publish to: not empty, no wildcards, no NUL. */
    fun validPublishTopic(topic: String): Boolean =
        topic.isNotEmpty() && topic.none { it == '+' || it == '#' || it == '\u0000' } && topic.encodeToByteArray().size <= 65_535

    /** A subscription filter: '+' only as a whole level, '#' only as the whole last level. */
    fun validFilter(filter: String): Boolean {
        if (filter.isEmpty() || '\u0000' in filter || filter.encodeToByteArray().size > 65_535) return false
        val levels = filter.split('/')
        return levels.withIndex().all { (i, level) ->
            when {
                level == "#" -> i == levels.lastIndex
                level == "+" -> true
                else -> '#' !in level && '+' !in level
            }
        }
    }

    /** Whether [topic] matches the subscription [filter] ('+' one level, '#' the rest). */
    fun topicMatches(filter: String, topic: String): Boolean {
        val f = filter.split('/')
        val t = topic.split('/')
        // Wildcards at the start don't match the broker's own "$SYS"-style topics.
        if (topic.startsWith('$') && (f[0] == "+" || f[0] == "#")) return false
        for (i in f.indices) {
            when (f[i]) {
                "#" -> return true
                "+" -> if (i >= t.size) return false
                else -> if (i >= t.size || f[i] != t[i]) return false
            }
        }
        return f.size == t.size
    }

    // ---------------------------------------------------------------- helpers

    private fun connectBody(p: MqttPacket.Connect): ByteArray {
        require(p.keepAliveS in 0..65_535) { "keep-alive out of range" }
        // A password without a user name is a protocol error in 3.1.1.
        val user = p.username?.takeIf { it.isNotEmpty() }
        val pass = p.password?.takeIf { user != null && it.isNotEmpty() }
        val out = ByteArrayOutputStream()
        out.writeString("MQTT")
        out.write(4) // protocol level: 3.1.1
        out.write((if (user != null) 0x80 else 0) or (if (pass != null) 0x40 else 0) or (if (p.cleanSession) 0x02 else 0))
        out.writeShort(p.keepAliveS)
        out.writeString(p.clientId)
        user?.let { out.writeString(it) }
        pass?.let { out.writeString(it) }
        return out.toByteArray()
    }

    private fun decodeConnect(r: Reader): MqttPacket.Connect {
        if (r.string() != "MQTT" || r.byte() != 4) throw MqttProtocolException("not an MQTT 3.1.1 CONNECT")
        val flags = r.byte()
        val keepAlive = r.short()
        val clientId = r.string()
        if (flags and 0x04 != 0) { r.string(); r.string() } // a will: skipped
        val user = if (flags and 0x80 != 0) r.string() else null
        val pass = if (flags and 0x40 != 0) r.string() else null
        return MqttPacket.Connect(clientId, user, pass, keepAlive, cleanSession = flags and 0x02 != 0)
    }

    private fun packet(header: Int, body: ByteArray): ByteArray = byteArrayOf(header.toByte()) + encodeRemainingLength(body.size) + body

    private fun shortBytes(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun ByteArrayOutputStream.writeShort(v: Int) = write(shortBytes(v))

    private fun ByteArrayOutputStream.writeString(s: String) {
        val bytes = s.encodeToByteArray()
        require(bytes.size <= 65_535) { "string too long for MQTT" }
        writeShort(bytes.size)
        write(bytes)
    }

    private class Reader(private val b: ByteArray) {
        private var pos = 0
        val remaining get() = b.size - pos
        fun byte(): Int {
            if (pos >= b.size) throw MqttProtocolException("packet too short")
            return b[pos++].toInt() and 0xFF
        }
        fun short(): Int = (byte() shl 8) or byte()
        fun string(): String {
            val n = short()
            if (n > remaining) throw MqttProtocolException("string runs past the packet")
            return b.decodeToString(pos, pos + n).also { pos += n }
        }
        fun rest(): ByteArray = b.copyOfRange(pos, b.size).also { pos = b.size }
    }
}
