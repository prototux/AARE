package net.prototux.aare.aap.wireless

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/*
 * The Wi-Fi Projection Protocol ("WPP"): the small exchange over Bluetooth RFCOMM through which a
 * wireless head unit tells the phone which network to join and where its AAP server listens.
 * See docs/research/reference-projects.md sections 4 and 6, gearhead-notes.md 3.2.
 */

/** RFCOMM service class the head unit publishes over SDP; the phone connects to it as client. */
const val AA_WIRELESS_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"

/** Message ids from the 4-byte WPP header. Directions are as seen from the phone. */
object WppMessageId {
    /** HU -> phone: ip/port of the head unit's AAP TCP server. */
    const val START_REQUEST = 1
    /** phone -> HU: asks for the access point credentials. Empty. */
    const val INFO_REQUEST = 2
    /** HU -> phone: ssid, password, bssid, security mode, access point type. */
    const val INFO_RESPONSE = 3
    /** HU -> phone: optional opening message carrying the HU's WPP version. */
    const val VERSION_REQUEST = 4
    const val VERSION_RESPONSE = 5
    /** phone -> HU: whether the phone got onto the network (0 = yes). */
    const val CONNECTION_STATUS = 6
    /** phone -> HU: acknowledges [START_REQUEST]. */
    const val START_RESPONSE = 7
    /** Keepalive, either direction; a request is answered by echoing its payload. */
    const val PING_REQUEST = 8
    const val PING_RESPONSE = 9
    /** HU -> phone, WPP over TCP only: the head unit refuses the phone. */
    const val CONNECTION_REJECTION = 10
    /** HU -> phone: WPP-over-TCP setup data. Not implemented, only logged. */
    const val SETUP_INFO = 11

    fun name(id: Int): String = when (id) {
        START_REQUEST -> "WifiStartRequest"
        INFO_REQUEST -> "WifiInfoRequest"
        INFO_RESPONSE -> "WifiInfoResponse"
        VERSION_REQUEST -> "WifiVersionRequest"
        VERSION_RESPONSE -> "WifiVersionResponse"
        CONNECTION_STATUS -> "WifiConnectionStatus"
        START_RESPONSE -> "WifiStartResponse"
        PING_REQUEST -> "WifiPingRequest"
        PING_RESPONSE -> "WifiPingResponse"
        CONNECTION_REJECTION -> "WifiConnectionRejection"
        SETUP_INFO -> "WifiSetupInfo"
        else -> "unknown($id)"
    }
}

/** Status codes of WifiStartResponse / WifiConnectionStatus / WifiVersionResponse (aaw.Status). */
object WppStatus {
    const val SUCCESS = 0
    const val UNSOLICITED_MESSAGE = 1
    const val NO_COMPATIBLE_VERSION = -1
    const val WIFI_INACCESSIBLE_CHANNEL = -2
    const val WIFI_INCORRECT_CREDENTIALS = -3
    const val PROJECTION_ALREADY_STARTED = -4
    const val WIFI_DISABLED = -5
    const val WIFI_NOT_YET_STARTED = -6
    const val INVALID_HOST = -7
    const val NO_SUPPORTED_WIFI_CHANNELS = -8
    const val INSTRUCT_USER_TO_CHECK_THE_PHONE = -9
    const val PHONE_WIFI_DISABLED = -10
    const val WIFI_NETWORK_UNAVAILABLE = -11
}

/**
 * WifiInfoResponse.security_mode as real head units send it: bit flags, not the in-session
 * WifiSecurityMode enum (where WPA2_PERSONAL is 5). Gearhead only accepts 8 and 40.
 */
object WppSecurityMode {
    const val UNKNOWN = 0
    const val OPEN = 1
    const val WEP_64 = 2
    const val WEP_128 = 3
    const val WPA_PERSONAL = 4
    const val WPA2_PERSONAL = 8
    const val WPA_WPA2_PERSONAL = 12
    const val WPA_ENTERPRISE = 20
    const val WPA2_ENTERPRISE = 24
    const val WPA_WPA2_ENTERPRISE = 28
    const val WPA3_PERSONAL = 32
    const val WPA2_WPA3_PERSONAL = 40

    /** What the phone should configure for a wire value. Lenient where a mistake is plausible. */
    fun toSecurity(mode: Int, password: String): WifiSecurity = when (mode) {
        OPEN -> WifiSecurity.OPEN
        // 4/12: WPA(1)-only access points are rare; a WPA/WPA2 AP takes a WPA2 PSK.
        WPA_PERSONAL, WPA2_PERSONAL, WPA_WPA2_PERSONAL -> WifiSecurity.WPA2_PERSONAL
        WPA3_PERSONAL -> WifiSecurity.WPA3_PERSONAL
        WPA2_WPA3_PERSONAL -> WifiSecurity.WPA2_WPA3_PERSONAL
        // 5 is aasdk's in-session WPA2_PERSONAL; an aasdk-based head unit may send it verbatim.
        // 0 (unset) with a password can only sensibly be a PSK network.
        5, UNKNOWN -> if (password.isEmpty()) WifiSecurity.OPEN else WifiSecurity.WPA2_PERSONAL
        else -> WifiSecurity.UNSUPPORTED
    }
}

/** The kinds of networks the phone can join for wireless projection. */
enum class WifiSecurity { OPEN, WPA2_PERSONAL, WPA3_PERSONAL, WPA2_WPA3_PERSONAL, UNSUPPORTED }

/**
 * Longest payload accepted from the head unit. Reads happen into a fixed buffer of this
 * size and treats a longer frame as a broken link; real WPP messages are a few hundred bytes.
 */
const val WPP_MAX_PAYLOAD = 1024

/** One WPP message: `[u16 BE payload length][u16 BE message id][protobuf payload]`. */
class WppFrame(val id: Int, val payload: ByteArray) {
    override fun toString() = "${WppMessageId.name(id)} (${payload.size} B)"
}

/**
 * Reads one frame; returns null on a clean end of stream between frames. Throws an IOException,
 * like a broken link, for a payload over [WPP_MAX_PAYLOAD].
 */
fun readWppFrame(input: InputStream): WppFrame? {
    val data = if (input is DataInputStream) input else DataInputStream(input)
    val hi = data.read()
    if (hi < 0) return null
    val lo = data.read()
    if (lo < 0) throw EOFException("WPP stream ended inside a header")
    val length = (hi shl 8) or lo
    val id = data.readUnsignedShort()
    if (length > WPP_MAX_PAYLOAD) throw IOException("WPP frame ${WppMessageId.name(id)} of $length B is over the $WPP_MAX_PAYLOAD B limit")
    val payload = ByteArray(length)
    data.readFully(payload)
    return WppFrame(id, payload)
}

/** Builds the bytes of one frame; written with a single write() so it leaves as one RFCOMM packet. */
fun encodeWppFrame(id: Int, payload: ByteArray): ByteArray {
    require(payload.size <= 0xffff) { "WPP payload too large: ${payload.size}" }
    val out = ByteArray(4 + payload.size)
    out[0] = (payload.size shr 8).toByte()
    out[1] = payload.size.toByte()
    out[2] = (id shr 8).toByte()
    out[3] = id.toByte()
    payload.copyInto(out, 4)
    return out
}

fun writeWppFrame(output: OutputStream, id: Int, payload: ByteArray) {
    output.write(encodeWppFrame(id, payload))
    output.flush()
}
