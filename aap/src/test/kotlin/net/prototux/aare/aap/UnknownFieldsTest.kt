package net.prototux.aare.aap

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The discovery response's fields our protos don't know, for the log (#274). */
class UnknownFieldsTest {
    private fun bytes(write: CodedOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        CodedOutputStream.newInstance(out).apply { write(); flush() }
        return out.toByteArray()
    }

    private val known = ServiceDiscoveryResponse.newBuilder()
        .setDisplayName("Exit")
        .addChannels(Service.newBuilder().setId(3))
        .build().toByteArray()

    @Test
    fun aResponseOurProtosKnowHasNoUnknownFields() {
        assertEquals(emptyList<String>(), UnknownFields.inDiscoveryResponse(known))
        assertTrue(ServiceDiscoveryResponse.CHANNELS_FIELD_NUMBER in UnknownFields.knownFields(ServiceDiscoveryResponse::class.java))
    }

    @Test
    fun unknownFieldsAreListedWithTheirValue() {
        val body = known + bytes {
            writeString(901, "Cloudrive22ym")
            writeInt64(902, 5)
            writeByteArray(903, byteArrayOf(0x08, 0x01, 0x12, 0x00))
            writeFixed32(904, 0x1234)
        }
        assertEquals(
            listOf(
                "field 901, 13 bytes: \"Cloudrive22ym\"",
                "field 902, varint 5",
                "field 903, 4 bytes: 08 01 12 00",
                "field 904, fixed32 0x00001234",
            ),
            UnknownFields.inDiscoveryResponse(body),
        )
    }

    @Test
    fun aServicesUnknownFieldsAreListedUnderIt() {
        val service = Service.newBuilder().setId(7).build().toByteArray() + bytes { writeInt64(905, 3) }
        val body = known + bytes { writeByteArray(ServiceDiscoveryResponse.CHANNELS_FIELD_NUMBER, service) }
        assertEquals(listOf("channels[1].field 905, varint 3"), UnknownFields.inDiscoveryResponse(body))
    }

    @Test
    fun theListIsCapped() {
        val body = known + bytes { repeat(100) { writeInt64(906, it.toLong()) } }
        assertEquals(UnknownFields.MAX_LINES, UnknownFields.inDiscoveryResponse(body).size)
    }

    @Test
    fun aLongValueIsCutInThePreview() {
        val preview = UnknownFields.preview(ByteArray(100) { 'a'.code.toByte() })
        assertTrue(preview, preview.endsWith("…\"") && preview.length < 60)
    }
}
