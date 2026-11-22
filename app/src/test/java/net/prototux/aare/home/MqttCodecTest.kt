package net.prototux.aare.home

import net.prototux.aare.home.mqtt.MqttCodec
import net.prototux.aare.home.mqtt.MqttPacket
import net.prototux.aare.home.mqtt.MqttProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class MqttCodecTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun roundTrip(p: MqttPacket): MqttPacket = MqttCodec.read(ByteArrayInputStream(MqttCodec.encode(p)), 1 shl 20)

    @Test
    fun remainingLengthUsesSevenBitsPerByte() {
        // The boundaries from the MQTT 3.1.1 specification's table.
        assertArrayEquals(bytes(0x00), MqttCodec.encodeRemainingLength(0))
        assertArrayEquals(bytes(0x7F), MqttCodec.encodeRemainingLength(127))
        assertArrayEquals(bytes(0x80, 0x01), MqttCodec.encodeRemainingLength(128))
        assertArrayEquals(bytes(0xFF, 0x7F), MqttCodec.encodeRemainingLength(16_383))
        assertArrayEquals(bytes(0x80, 0x80, 0x01), MqttCodec.encodeRemainingLength(16_384))
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0x7F), MqttCodec.encodeRemainingLength(MqttCodec.MAX_REMAINING_LENGTH))
        for (n in listOf(0, 1, 127, 128, 300, 16_383, 16_384, 2_097_151, 2_097_152, MqttCodec.MAX_REMAINING_LENGTH)) {
            assertEquals(n, MqttCodec.readRemainingLength(ByteArrayInputStream(MqttCodec.encodeRemainingLength(n))))
        }
    }

    @Test(expected = MqttProtocolException::class)
    fun aFiveByteRemainingLengthIsRefused() {
        MqttCodec.readRemainingLength(ByteArrayInputStream(bytes(0x80, 0x80, 0x80, 0x80, 0x01)))
    }

    @Test
    fun connectMatchesTheSpecificationLayout() {
        val b = MqttCodec.encode(MqttPacket.Connect("car", "jo", "pw", keepAliveS = 60))
        val expected = bytes(
            0x10, 23, // CONNECT, remaining length
            0, 4, 'M'.code, 'Q'.code, 'T'.code, 'T'.code, 4, // "MQTT", level 4
            0xC2, 0, 60, // user + password + clean session, keep-alive 60 s
            0, 3, 'c'.code, 'a'.code, 'r'.code,
            0, 2, 'j'.code, 'o'.code,
            0, 2, 'p'.code, 'w'.code,
        )
        assertArrayEquals(expected, b)
        val back = roundTrip(MqttPacket.Connect("car", "jo", "pw", keepAliveS = 60)) as MqttPacket.Connect
        assertEquals("car", back.clientId)
        assertEquals("jo", back.username)
        assertEquals("pw", back.password)
        assertEquals(60, back.keepAliveS)
    }

    @Test
    fun aPasswordWithoutAUserNameIsLeftOut() {
        val back = roundTrip(MqttPacket.Connect("car", null, "pw")) as MqttPacket.Connect
        assertNull(back.username)
        assertNull(back.password)
    }

    @Test
    fun publishCarriesQosRetainAndThePacketIdOnlyAtQos1() {
        val q0 = MqttCodec.encode(MqttPacket.Publish("a/b", "on".encodeToByteArray(), qos = 0, retain = true))
        assertArrayEquals(bytes(0x31, 7, 0, 3, 'a'.code, '/'.code, 'b'.code, 'o'.code, 'n'.code), q0)
        val q1 = roundTrip(MqttPacket.Publish("a/b", "on".encodeToByteArray(), qos = 1, packetId = 513)) as MqttPacket.Publish
        assertEquals("a/b", q1.topic)
        assertEquals(1, q1.qos)
        assertEquals(513, q1.packetId)
        assertFalse(q1.retain)
        assertEquals("on", q1.payload.decodeToString())
    }

    @Test
    fun subscribeHasItsReservedFlagsAndSubAckItsCodes() {
        val b = MqttCodec.encode(MqttPacket.Subscribe(7, listOf("x/#" to 0)))
        assertEquals(0x82, b[0].toInt() and 0xFF)
        val sub = roundTrip(MqttPacket.Subscribe(7, listOf("x/#" to 0, "y" to 1))) as MqttPacket.Subscribe
        assertEquals(listOf("x/#" to 0, "y" to 1), sub.filters)
        assertEquals(MqttPacket.SubAck(7, listOf(0, MqttCodec.SUBACK_FAILURE)), roundTrip(MqttPacket.SubAck(7, listOf(0, 0x80))))
    }

    @Test
    fun smallPacketsRoundTrip() {
        assertEquals(MqttPacket.ConnAck(false, 4), roundTrip(MqttPacket.ConnAck(false, 4)))
        assertEquals(MqttPacket.PubAck(65_535), roundTrip(MqttPacket.PubAck(65_535)))
        assertArrayEquals(bytes(0xC0, 0), MqttCodec.encode(MqttPacket.PingReq))
        assertEquals(MqttPacket.PingResp, roundTrip(MqttPacket.PingResp))
        assertArrayEquals(bytes(0xE0, 0), MqttCodec.encode(MqttPacket.Disconnect))
    }

    @Test(expected = MqttProtocolException::class)
    fun aPacketOverTheLimitIsRefusedBeforeItIsRead() {
        val big = MqttCodec.encode(MqttPacket.Publish("t", ByteArray(2_000)))
        MqttCodec.read(ByteArrayInputStream(big), 1_000)
    }

    @Test(expected = MqttProtocolException::class)
    fun aStringRunningPastThePacketIsRefused() {
        MqttCodec.decode(0x30, bytes(0, 9, 'a'.code))
    }

    @Test
    fun topicsAndFilters() {
        assertTrue(MqttCodec.validPublishTopic("home/garage/set"))
        assertFalse(MqttCodec.validPublishTopic("home/+/set"))
        assertFalse(MqttCodec.validPublishTopic(""))
        assertTrue(MqttCodec.validFilter("home/+/state"))
        assertTrue(MqttCodec.validFilter("home/#"))
        assertFalse(MqttCodec.validFilter("home/#/x"))
        assertFalse(MqttCodec.validFilter("home/a+"))
    }

    @Test
    fun wildcardMatching() {
        assertTrue(MqttCodec.topicMatches("a/+/c", "a/b/c"))
        assertFalse(MqttCodec.topicMatches("a/+/c", "a/b/d"))
        assertTrue(MqttCodec.topicMatches("a/#", "a/b/c"))
        assertTrue(MqttCodec.topicMatches("a/#", "a")) // '#' includes the parent level
        assertFalse(MqttCodec.topicMatches("a/+", "a"))
        assertTrue(MqttCodec.topicMatches("a/b", "a/b"))
        assertFalse(MqttCodec.topicMatches("a/b", "a/b/c"))
        assertFalse(MqttCodec.topicMatches("#", "\$SYS/uptime"))
    }
}
