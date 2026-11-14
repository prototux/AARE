package net.prototux.aare.headunit

import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import net.prototux.aare.aap.AapSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SimTest {
    @Test
    fun noOptionsIsThePlainHeadUnit() {
        assertEquals(Sim(), Sim.parse(emptyMap()))
        // Options that aren't the harness's leave it alone too.
        assertEquals(Sim(), Sim.parse(mapOf("res" to "1920x1080", "cluster" to "true", "script" to "key:3@5")))
    }

    @Test
    fun identity() {
        val s = Sim.parse(mapOf("make" to "Mazda [x]", "model" to "3 Hatch", "year" to "2019", "hu-make" to "Harman",
            "hu-model" to "CMU 150", "hu-sw" to "74.00.324", "vehicle-id" to "VIN WVWZZZ1JZXW000001", "car-bt-address" to "00:11:22:33:44:55"))
        assertEquals(Sim(make = "Mazda [x]", model = "3 Hatch", year = "2019", huMake = "Harman", huModel = "CMU 150",
            huSoftwareVersion = "74.00.324", vehicleId = "VIN WVWZZZ1JZXW000001", carBtAddress = "00:11:22:33:44:55"), s)
    }

    @Test
    fun faults() {
        assertEquals(setOf(Sim.Fault.CLOSE_AFTER_VERSION), Sim.parse(mapOf("fault" to "close-after-version")).faults)
        val s = Sim.parse(mapOf("fault" to "close-mid-tls, auth-fail"))
        assertEquals(setOf(Sim.Fault.CLOSE_MID_TLS, Sim.Fault.AUTH_FAIL), s.faults)
        assertEquals(MessageStatus.STATUS_AUTHENTICATION_FAILURE_VALUE, s.authFailStatus)
        assertEquals(MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED_VALUE, Sim.parse(mapOf("fault" to "auth-fail:-24")).authFailStatus)
        assertThrows(IllegalArgumentException::class.java) { Sim.parse(mapOf("fault" to "close-early")) }
        assertThrows(IllegalArgumentException::class.java) { Sim.parse(mapOf("fault" to "true")) }
        assertThrows(IllegalArgumentException::class.java) { Sim.parse(mapOf("fault" to "auth-fail:0")) }
        assertThrows(IllegalArgumentException::class.java) { Sim.parse(mapOf("fault" to "auth-fail:x")) }
        assertThrows(IllegalArgumentException::class.java) { Sim.parse(mapOf("fault" to "close-mid-tls:1")) }
    }

    @Test
    fun channelsAndNumbers() {
        val s = Sim.parse(mapOf("refuse-channel" to "7", "ignore-channel" to "2,3", "video-ack-delay" to "250", "mic-status" to "-1",
            "ping-ms" to "100", "no-audio-ack" to "true", "no-audio-focus" to "true", "no-video-focus" to "true"))
        assertEquals(setOf(7), s.refuseChannels)
        assertEquals(setOf(2, 3), s.ignoreChannels)
        assertEquals(250L, s.videoAckDelayMs)
        assertEquals(-1, s.micStatus)
        assertEquals(100L, s.pingMs)
        assertTrue(s.noAudioAck && s.noAudioFocus && s.noVideoFocus)
        for (bad in listOf("refuse-channel" to "x", "ignore-channel" to "256", "video-ack-delay" to "-1", "ping-ms" to "0",
            "mic-status" to "9999999999", "mic-status" to "true")) {
            assertThrows(bad.toString(), IllegalArgumentException::class.java) { Sim.parse(mapOf(bad)) }
        }
    }

    @Test
    fun discoveryVariants() {
        assertEquals(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP, Sim.parse(mapOf("video-codec" to "h264")).videoCodec)
        assertEquals(MediaCodecType.MEDIA_CODEC_VIDEO_H265, Sim.parse(mapOf("video-codec" to "h265")).videoCodec)
        assertEquals(MediaCodecType.MEDIA_CODEC_VIDEO_VP9, Sim.parse(mapOf("video-codec" to "vp9")).videoCodec)
        assertThrows(IllegalArgumentException::class.java) { Sim.parse(mapOf("video-codec" to "av2")) }
        assertEquals(Sim.WifiProjection("Car, \"WiFi\"", "02:00:5E:10:00:01"),
            Sim.parse(mapOf("wifi-projection" to "Car, \"WiFi\",02:00:5E:10:00:01")).wifiProjection)
        for (bad in listOf("NoBssid", "Ssid,", ",02:00:5E:10:00:01")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Sim.parse(mapOf("wifi-projection" to bad)) }
        }
        val s = Sim.parse(mapOf("aac-sink" to "true", "unknown-service" to "true"))
        assertTrue(s.aacSink && s.unknownService)
    }

    @Test
    fun discoveryCarriesTheVariants() {
        val sim = Sim(make = "Mazda", model = "3", year = "2019", huMake = "Harman", huModel = "CMU", huSoftwareVersion = "74",
            vehicleId = "VIN123", carBtAddress = "00:11:22:33:44:55", aacSink = true, unknownService = true,
            videoCodec = MediaCodecType.MEDIA_CODEC_VIDEO_H265, wifiProjection = Sim.WifiProjection("CarNet", "02:00:5E:10:00:01"))
        val r = DiscoveryGoldenTest.headUnit(HeadUnit.Options(name = "My Mazda", sim = sim)).discovery()
        assertEquals("Mazda 3 Harman CMU [My Mazda] services: video#1, audio:AUDIO_STREAM_MEDIA#2, audio:AUDIO_STREAM_GUIDANCE#3, " +
            "audio:AUDIO_STREAM_SYSTEM_AUDIO#4, mic#5, input#6, sensors#7, navstatus#8, playback#9, phonestatus#15, bluetooth#10, " +
            "audio:AUDIO_STREAM_MEDIA#12, notifications#13, wifi#14", AapSession.describe(r))
        with(r.headunitInfo) {
            assertEquals("2019", year)
            assertEquals("VIN123", vehicleId)
            assertEquals("74", headUnitSoftwareVersion)
        }
        val byId = r.channelsList.associateBy { it.id }
        assertEquals("00:11:22:33:44:55", byId.getValue(10).bluetoothService.carAddress)
        assertEquals(MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC, byId.getValue(12).mediaSinkService.availableType)
        assertEquals(MediaCodecType.MEDIA_CODEC_VIDEO_H265, byId.getValue(1).mediaSinkService.availableType)
        assertEquals(listOf(MediaCodecType.MEDIA_CODEC_VIDEO_H265), byId.getValue(1).mediaSinkService.videoConfigsList.map { it.videoCodecType })
        assertEquals("02:00:5E:10:00:01", byId.getValue(14).wifiProjectionService.carWifiBssid)
    }
}
