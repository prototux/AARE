package net.prototux.aare.aap

/**
 * Message ids, mirrored from the `*MessageId` / `ControlMessageType` enums in the protos so that
 * hot paths can switch on plain ints.
 */
object ControlMsg {
    const val VERSION_REQUEST = 1
    const val VERSION_RESPONSE = 2
    const val ENCAPSULATED_SSL = 3
    const val AUTH_COMPLETE = 4
    const val SERVICE_DISCOVERY_REQUEST = 5
    const val SERVICE_DISCOVERY_RESPONSE = 6
    const val CHANNEL_OPEN_REQUEST = 7
    const val CHANNEL_OPEN_RESPONSE = 8
    const val CHANNEL_CLOSE_NOTIFICATION = 9
    const val PING_REQUEST = 11
    const val PING_RESPONSE = 12
    const val NAV_FOCUS_REQUEST = 13
    const val NAV_FOCUS_NOTIFICATION = 14
    const val BYEBYE_REQUEST = 15
    const val BYEBYE_RESPONSE = 16
    const val VOICE_SESSION_NOTIFICATION = 17
    const val AUDIO_FOCUS_REQUEST = 18
    const val AUDIO_FOCUS_NOTIFICATION = 19
    const val CAR_CONNECTED_DEVICES_REQUEST = 20
    const val CAR_CONNECTED_DEVICES_RESPONSE = 21
    const val USER_SWITCH_REQUEST = 22
    const val BATTERY_STATUS_NOTIFICATION = 23
    const val CALL_AVAILABILITY_STATUS = 24
    const val USER_SWITCH_RESPONSE = 25
    const val SERVICE_DISCOVERY_UPDATE = 26
    const val UNEXPECTED_MESSAGE = 255
    const val FRAMING_ERROR = 65535
}

/** Media sink (video, audio out) and media source (microphone) channels. */
object MediaMsg {
    const val DATA = 0 // 8-byte timestamp + payload
    const val CODEC_CONFIG = 1 // payload only
    const val SETUP = 0x8000
    const val START = 0x8001
    const val STOP = 0x8002
    const val CONFIG = 0x8003
    const val ACK = 0x8004
    const val MICROPHONE_REQUEST = 0x8005
    const val MICROPHONE_RESPONSE = 0x8006
    const val VIDEO_FOCUS_REQUEST = 0x8007
    const val VIDEO_FOCUS_NOTIFICATION = 0x8008
    const val UPDATE_UI_CONFIG_REQUEST = 0x8009
    const val UPDATE_UI_CONFIG_REPLY = 0x800A
    const val AUDIO_UNDERFLOW_NOTIFICATION = 0x800B
}

object InputMsg {
    const val INPUT_REPORT = 0x8001
    const val KEY_BINDING_REQUEST = 0x8002
    const val KEY_BINDING_RESPONSE = 0x8003
    const val INPUT_FEEDBACK = 0x8004
}

object SensorMsg {
    const val REQUEST = 0x8001
    const val RESPONSE = 0x8002
    const val BATCH = 0x8003
    const val ERROR = 0x8004
}

object NavStatusMsg {
    const val START = 0x8001
    const val STOP = 0x8002
    const val STATUS = 0x8003
    const val TURN_EVENT = 0x8004 // deprecated, still the only thing older head units read
    const val DISTANCE_EVENT = 0x8005 // deprecated, same
    const val STATE = 0x8006
    const val CURRENT_POSITION = 0x8007
    // These are sent only when the navigation app gives the data (through Google's
    // own navigation SDK, not the Car App Library, so AARE has nothing to put in them): the energy
    // forecast to head units newer than 5.0, the route info to 7.0 and later.
    const val RAW_VEHICLE_ENERGY_FORECAST = 0x8008
    const val RAW_ROUTE_INFO = 0x8009
}

object MediaPlaybackMsg {
    const val STATUS = 0x8001
    const val INPUT = 0x8002
    const val METADATA = 0x8003
}

object BluetoothMsg {
    const val PAIRING_REQUEST = 0x8001
    const val PAIRING_RESPONSE = 0x8002
    const val AUTHENTICATION_DATA = 0x8003
    const val AUTHENTICATION_RESULT = 0x8004
}

object PhoneStatusMsg {
    const val STATUS = 0x8001
    const val INPUT = 0x8002
}

object GenericNotificationMsg {
    const val SUBSCRIBE = 0x8001
    const val UNSUBSCRIBE = 0x8002
    const val MESSAGE = 0x8003
    const val ACK = 0x8004
}

object WifiProjectionMsg {
    const val CREDENTIALS_REQUEST = 0x8001
    const val CREDENTIALS_RESPONSE = 0x8002
}
