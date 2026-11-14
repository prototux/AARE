package net.prototux.aare.headunit

import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType

/**
 * Test-harness settings: the car's identity strings and discovery variants, to drive a
 * phone app through its error and logging paths. Every default is the plain head unit.
 */
data class Sim(
    val make: String = "AARE",
    val model: String = "Desktop",
    val year: String = "2026",
    val huMake: String = "AARE",
    val huModel: String = "desktop head unit",
    val huSoftwareVersion: String = "1.0",
    /** HeadUnitInfo.vehicle_id; left out when null. */
    val vehicleId: String? = null,
    /** The Bluetooth service's car_address. Null keeps SKIP_THIS_BLUETOOTH and leaves pairing requests unanswered. */
    val carBtAddress: String? = null,
    val aacSink: Boolean = false,
    val unknownService: Boolean = false,
    val videoCodec: MediaCodecType = MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP,
    val wifiProjection: WifiProjection? = null,
) {
    data class WifiProjection(val ssid: String, val bssid: String)

    companion object {
        /** Reads the options (as parsed from the command line); throws IllegalArgumentException with a message for the user. */
        fun parse(o: Map<String, String>): Sim {
            val d = Sim()
            val videoCodec = when (val c = o["video-codec"]) {
                null, "h264" -> MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                "h265" -> MediaCodecType.MEDIA_CODEC_VIDEO_H265
                "vp9" -> MediaCodecType.MEDIA_CODEC_VIDEO_VP9
                else -> throw IllegalArgumentException("--video-codec is h264, h265 or vp9, not \"$c\"")
            }
            // An SSID may hold a comma, a BSSID never does.
            val wifi = o["wifi-projection"]?.let { v ->
                val ssid = v.substringBeforeLast(',', "")
                val bssid = v.substringAfterLast(',', "")
                require(ssid.isNotEmpty() && bssid.isNotEmpty()) { "--wifi-projection needs SSID,BSSID" }
                WifiProjection(ssid, bssid)
            }

            return Sim(
                make = o["make"] ?: d.make,
                model = o["model"] ?: d.model,
                year = o["year"] ?: d.year,
                huMake = o["hu-make"] ?: d.huMake,
                huModel = o["hu-model"] ?: d.huModel,
                huSoftwareVersion = o["hu-sw"] ?: d.huSoftwareVersion,
                vehicleId = o["vehicle-id"],
                carBtAddress = o["car-bt-address"],
                aacSink = "aac-sink" in o,
                unknownService = "unknown-service" in o,
                videoCodec = videoCodec,
                wifiProjection = wifi,
            )
        }
    }
}
