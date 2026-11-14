package net.prototux.aare.aap

/**
 * What the verbose protocol log shows of each message, sent and received, and under which name.
 *
 * The log is what car reports come with, and it keeps a limited number of lines. It used to show
 * only the control channel, received side, pings included: on a wireless link the pings alone
 * filled it in two and a half minutes (#96), and a black screen or a refused message was invisible
 * (#100). It now leaves out what flows (pings, audio and video data and their acks, touch reports,
 * sensor batches, positions, playback status) and keeps what sets things up or fails: stream
 * setup, start, stop and focus, the control messages, and what the head unit says back.
 */
object ProtocolTrace {
    private val MEDIA_FLOW = setOf(MediaMsg.DATA, MediaMsg.CODEC_CONFIG, MediaMsg.ACK)

    /** Whether to log message [id] on a channel named [channelName] (null: the control channel). */
    fun shown(channelName: String?, id: Int): Boolean = when {
        channelName == null -> id != ControlMsg.PING_REQUEST && id != ControlMsg.PING_RESPONSE
        isMedia(channelName) -> id !in MEDIA_FLOW
        channelName == "input" -> id != InputMsg.INPUT_REPORT
        channelName == "sensors" -> id != SensorMsg.BATCH
        channelName == "navstatus" -> id != NavStatusMsg.CURRENT_POSITION
        channelName == "playback" -> false
        else -> true
    }

    /** A readable name for message [id] on [channelName], or its hex id when unknown. */
    fun name(channelName: String?, id: Int): String {
        val names = when {
            channelName == null -> CONTROL
            isMedia(channelName) -> MEDIA
            channelName == "input" -> INPUT
            channelName == "sensors" -> SENSORS
            channelName == "navstatus" -> NAVSTATUS
            else -> emptyMap()
        }
        return names[id] ?: "0x${id.toString(16)}"
    }

    private fun isMedia(channelName: String) = channelName == "video" || channelName == "mic" || channelName.startsWith("audio")

    private val CONTROL = mapOf(
        ControlMsg.VERSION_REQUEST to "VERSION_REQUEST", ControlMsg.VERSION_RESPONSE to "VERSION_RESPONSE",
        ControlMsg.ENCAPSULATED_SSL to "SSL", ControlMsg.AUTH_COMPLETE to "AUTH_COMPLETE",
        ControlMsg.SERVICE_DISCOVERY_REQUEST to "SERVICE_DISCOVERY_REQUEST",
        ControlMsg.SERVICE_DISCOVERY_RESPONSE to "SERVICE_DISCOVERY_RESPONSE",
        ControlMsg.CHANNEL_OPEN_REQUEST to "CHANNEL_OPEN_REQUEST", ControlMsg.CHANNEL_OPEN_RESPONSE to "CHANNEL_OPEN_RESPONSE",
        ControlMsg.CHANNEL_CLOSE_NOTIFICATION to "CHANNEL_CLOSE",
        ControlMsg.PING_REQUEST to "PING_REQUEST", ControlMsg.PING_RESPONSE to "PING_RESPONSE",
        ControlMsg.NAV_FOCUS_REQUEST to "NAV_FOCUS_REQUEST", ControlMsg.NAV_FOCUS_NOTIFICATION to "NAV_FOCUS_NOTIFICATION",
        ControlMsg.BYEBYE_REQUEST to "BYEBYE_REQUEST", ControlMsg.BYEBYE_RESPONSE to "BYEBYE_RESPONSE",
        ControlMsg.VOICE_SESSION_NOTIFICATION to "VOICE_SESSION",
        ControlMsg.AUDIO_FOCUS_REQUEST to "AUDIO_FOCUS_REQUEST", ControlMsg.AUDIO_FOCUS_NOTIFICATION to "AUDIO_FOCUS_NOTIFICATION",
        ControlMsg.CAR_CONNECTED_DEVICES_REQUEST to "CAR_CONNECTED_DEVICES_REQUEST",
        ControlMsg.CAR_CONNECTED_DEVICES_RESPONSE to "CAR_CONNECTED_DEVICES_RESPONSE",
        ControlMsg.USER_SWITCH_REQUEST to "USER_SWITCH_REQUEST", ControlMsg.USER_SWITCH_RESPONSE to "USER_SWITCH_RESPONSE",
        ControlMsg.BATTERY_STATUS_NOTIFICATION to "BATTERY_STATUS", ControlMsg.CALL_AVAILABILITY_STATUS to "CALL_AVAILABILITY",
        ControlMsg.SERVICE_DISCOVERY_UPDATE to "SERVICE_DISCOVERY_UPDATE", ControlMsg.UNEXPECTED_MESSAGE to "UNEXPECTED_MESSAGE",
    )
    private val MEDIA = mapOf(
        MediaMsg.DATA to "DATA", MediaMsg.CODEC_CONFIG to "CODEC_CONFIG", MediaMsg.SETUP to "SETUP", MediaMsg.START to "START",
        MediaMsg.STOP to "STOP", MediaMsg.CONFIG to "CONFIG", MediaMsg.ACK to "ACK",
        MediaMsg.MICROPHONE_REQUEST to "MICROPHONE_REQUEST", MediaMsg.MICROPHONE_RESPONSE to "MICROPHONE_RESPONSE",
        MediaMsg.VIDEO_FOCUS_REQUEST to "VIDEO_FOCUS_REQUEST", MediaMsg.VIDEO_FOCUS_NOTIFICATION to "VIDEO_FOCUS_NOTIFICATION",
        MediaMsg.UPDATE_UI_CONFIG_REQUEST to "UPDATE_UI_CONFIG_REQUEST", MediaMsg.UPDATE_UI_CONFIG_REPLY to "UPDATE_UI_CONFIG_REPLY",
        MediaMsg.AUDIO_UNDERFLOW_NOTIFICATION to "AUDIO_UNDERFLOW",
    )
    private val INPUT = mapOf(
        InputMsg.INPUT_REPORT to "INPUT_REPORT", InputMsg.KEY_BINDING_REQUEST to "KEY_BINDING_REQUEST",
        InputMsg.KEY_BINDING_RESPONSE to "KEY_BINDING_RESPONSE", InputMsg.INPUT_FEEDBACK to "INPUT_FEEDBACK",
    )
    private val SENSORS = mapOf(
        SensorMsg.REQUEST to "SENSOR_REQUEST", SensorMsg.RESPONSE to "SENSOR_RESPONSE", SensorMsg.BATCH to "SENSOR_BATCH",
        SensorMsg.ERROR to "SENSOR_ERROR",
    )
    private val NAVSTATUS = mapOf(
        NavStatusMsg.START to "NAV_START", NavStatusMsg.STOP to "NAV_STOP", NavStatusMsg.STATUS to "NAV_STATUS",
        NavStatusMsg.TURN_EVENT to "NAV_TURN_EVENT", NavStatusMsg.DISTANCE_EVENT to "NAV_DISTANCE_EVENT",
        NavStatusMsg.STATE to "NAV_STATE", NavStatusMsg.CURRENT_POSITION to "NAV_CURRENT_POSITION",
    )
}
