package net.prototux.aare.aap

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * The fields of a message that this build's protos don't have, read from its raw bytes. A head
 * unit with a newer schema may put what AARE looks for there: a Chery that lists no services at
 * all (#274) either does that or waits for something from the phone, and only these tell which.
 */
internal object UnknownFields {
    /** At most this many lines for one message: a whole unknown schema shouldn't flood the log. */
    const val MAX_LINES = 30

    /** How much of a length-delimited value the log shows. */
    private const val PREVIEW_BYTES = 48

    private val known = ConcurrentHashMap<Class<*>, Set<Int>>()

    /** The field numbers [type] has: protoc gives every field a public `*_FIELD_NUMBER` constant. */
    fun knownFields(type: Class<*>): Set<Int> = known.getOrPut(type) {
        type.fields.filter { Modifier.isStatic(it.modifiers) && it.name.endsWith("_FIELD_NUMBER") && it.type == Int::class.javaPrimitiveType }
            .map { it.getInt(null) }.toSet()
    }

    /**
     * One line per unknown field of a service discovery response, its own and its services' ones
     * ("channels[2].field 31 ..."). Empty when the head unit only sends what our protos know.
     */
    fun inDiscoveryResponse(body: ByteArray): List<String> {
        val lines = ArrayList<String>()
        collect(body, ServiceDiscoveryResponse::class.java, "", lines) { number, index ->
            if (number == ServiceDiscoveryResponse.CHANNELS_FIELD_NUMBER) Service::class.java to "channels[$index]." else null
        }
        return lines
    }

    /**
     * Adds [type]'s unknown fields in [bytes] to [lines], prefixed with [path]. [nested] names the
     * known message fields to look into, by field number and occurrence.
     */
    fun collect(bytes: ByteArray, type: Class<*>, path: String, lines: MutableList<String>,
                nested: (number: Int, index: Int) -> Pair<Class<*>, String>? = { _, _ -> null }) {
        val knownHere = knownFields(type)
        val seen = HashMap<Int, Int>()
        val input = CodedInputStream.newInstance(bytes)
        while (lines.size < MAX_LINES) {
            val tag = input.readTag()
            if (tag == 0) return
            val number = WireFormat.getTagFieldNumber(tag)
            val index = seen.merge(number, 1, Int::plus)!! - 1
            if (number in knownHere) {
                val inner = if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) nested(number, index) else null
                if (inner == null) input.skipField(tag)
                else collect(input.readByteArray(), inner.first, path + inner.second, lines)
                continue
            }
            val value = when (WireFormat.getTagWireType(tag)) {
                WireFormat.WIRETYPE_VARINT -> "varint ${input.readInt64()}"
                WireFormat.WIRETYPE_FIXED32 -> "fixed32 0x%08x".format(input.readFixed32())
                WireFormat.WIRETYPE_FIXED64 -> "fixed64 0x%016x".format(input.readFixed64())
                WireFormat.WIRETYPE_LENGTH_DELIMITED -> input.readByteArray().let { "${it.size} bytes: ${preview(it)}" }
                else -> { input.skipField(tag); "group" }
            }
            lines += "${path}field $number, $value"
        }
    }

    /** Text when it reads as text (a name, a version), hex otherwise (a nested message). */
    internal fun preview(b: ByteArray): String {
        val shown = b.copyOf(minOf(b.size, PREVIEW_BYTES))
        val more = if (b.size > PREVIEW_BYTES) "…" else ""
        val text = shown.all { it in 0x20..0x7e }
        return if (text && shown.isNotEmpty()) "\"${String(shown, Charsets.US_ASCII)}$more\""
        else shown.joinToString(" ") { "%02x".format(it) } + more
    }
}
