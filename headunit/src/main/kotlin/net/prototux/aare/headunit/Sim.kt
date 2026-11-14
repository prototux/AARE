package net.prototux.aare.headunit

import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus

/**
 * Test-harness settings: the car's identity strings, faults and discovery variants, to drive a
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
    val faults: Set<Fault> = emptySet(),
    /** The AUTH_COMPLETE status sent with [Fault.AUTH_FAIL] (a MessageStatus number). */
    val authFailStatus: Int = MessageStatus.STATUS_AUTHENTICATION_FAILURE_VALUE,
    val refuseChannels: Set<Int> = emptySet(),
    val ignoreChannels: Set<Int> = emptySet(),
    val noAudioAck: Boolean = false,
    val videoAckDelayMs: Long = 0,
    val micStatus: Int = 0,
    val noAudioFocus: Boolean = false,
    val noVideoFocus: Boolean = false,
    val pingMs: Long = 1000,
    val aacSink: Boolean = false,
    val unknownService: Boolean = false,
    val videoCodec: MediaCodecType = MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP,
    val wifiProjection: WifiProjection? = null,
) {
    enum class Fault(val arg: String) {
        CLOSE_AFTER_VERSION("close-after-version"),
        CLOSE_MID_TLS("close-mid-tls"),
        AUTH_FAIL("auth-fail"),
    }

    data class WifiProjection(val ssid: String, val bssid: String)

    companion object {
        /** Reads the options (as parsed from the command line); throws IllegalArgumentException with a message for the user. */
        fun parse(o: Map<String, String>): Sim {
            val d = Sim()
            fun number(key: String): Long? = o[key]?.let { it.toLongOrNull() ?: throw IllegalArgumentException("--$key needs a number, not \"$it\"") }
            fun channels(key: String): Set<Int> = o[key]?.split(',')?.map { s ->
                s.trim().toIntOrNull()?.takeIf { it in 0..255 } ?: throw IllegalArgumentException("--$key needs channel numbers (0-255), comma-separated")
            }?.toSet() ?: emptySet()

            var authFailStatus = d.authFailStatus
            val faults = o["fault"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { token ->
                val fault = Fault.entries.firstOrNull { it.arg == token.substringBefore(':') }
                    ?: throw IllegalArgumentException("unknown --fault \"$token\" (one or more of ${Fault.entries.joinToString(", ") { it.arg }})")
                if (':' in token) {
                    require(fault == Fault.AUTH_FAIL) { "--fault ${fault.arg} takes no value" }
                    authFailStatus = token.substringAfter(':').toIntOrNull()?.takeIf { it != 0 }
                        ?: throw IllegalArgumentException("--fault auth-fail:STATUS needs a non-zero MessageStatus number")
                }
                fault
            }?.toSet() ?: d.faults

            val videoAckDelayMs = number("video-ack-delay") ?: d.videoAckDelayMs
            require(videoAckDelayMs >= 0) { "--video-ack-delay needs 0 or more milliseconds" }
            val pingMs = number("ping-ms") ?: d.pingMs
            require(pingMs > 0) { "--ping-ms needs a positive number of milliseconds" }
            val micStatus = number("mic-status")?.let { n ->
                n.toInt().takeIf { it.toLong() == n } ?: throw IllegalArgumentException("--mic-status is out of range")
            } ?: d.micStatus

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
                faults = faults,
                authFailStatus = authFailStatus,
                refuseChannels = channels("refuse-channel"),
                ignoreChannels = channels("ignore-channel"),
                noAudioAck = "no-audio-ack" in o,
                videoAckDelayMs = videoAckDelayMs,
                micStatus = micStatus,
                noAudioFocus = "no-audio-focus" in o,
                noVideoFocus = "no-video-focus" in o,
                pingMs = pingMs,
                aacSink = "aac-sink" in o,
                unknownService = "unknown-service" in o,
                videoCodec = videoCodec,
                wifiProjection = wifi,
            )
        }
    }
}
