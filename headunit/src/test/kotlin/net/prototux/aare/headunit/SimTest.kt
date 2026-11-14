package net.prototux.aare.headunit

import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
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
