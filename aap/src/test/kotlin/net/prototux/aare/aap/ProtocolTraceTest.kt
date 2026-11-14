package net.prototux.aare.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTraceTest {
    /** #96: on wireless, pings filled the whole log in two and a half minutes. */
    @Test fun pingsAreLeftOut() {
        assertFalse(ProtocolTrace.shown(null, ControlMsg.PING_REQUEST))
        assertFalse(ProtocolTrace.shown(null, ControlMsg.PING_RESPONSE))
        assertTrue(ProtocolTrace.shown(null, ControlMsg.AUDIO_FOCUS_NOTIFICATION))
        assertTrue(ProtocolTrace.shown(null, ControlMsg.UNEXPECTED_MESSAGE))
    }

    /** #100: a black screen happens on the video channel, which wasn't logged at all. */
    @Test fun streamSetupAndFocusAreShownNotTheirData() {
        for (ch in listOf("video", "audio:AUDIO_STREAM_MEDIA", "mic")) {
            assertTrue(ProtocolTrace.shown(ch, MediaMsg.SETUP))
            assertTrue(ProtocolTrace.shown(ch, MediaMsg.START))
            assertFalse(ProtocolTrace.shown(ch, MediaMsg.DATA))
            assertFalse(ProtocolTrace.shown(ch, MediaMsg.ACK))
        }
        assertTrue(ProtocolTrace.shown("video", MediaMsg.VIDEO_FOCUS_REQUEST))
        assertFalse(ProtocolTrace.shown("video", MediaMsg.CODEC_CONFIG))
    }

    @Test fun highRateStreamsOfOtherChannelsAreLeftOut() {
        assertFalse(ProtocolTrace.shown("input", InputMsg.INPUT_REPORT))
        assertTrue(ProtocolTrace.shown("input", InputMsg.KEY_BINDING_RESPONSE))
        assertFalse(ProtocolTrace.shown("sensors", SensorMsg.BATCH))
        assertTrue(ProtocolTrace.shown("sensors", SensorMsg.REQUEST))
        assertFalse(ProtocolTrace.shown("navstatus", NavStatusMsg.CURRENT_POSITION))
        assertTrue(ProtocolTrace.shown("navstatus", NavStatusMsg.TURN_EVENT))
        assertFalse(ProtocolTrace.shown("playback", MediaPlaybackMsg.METADATA))
        assertTrue(ProtocolTrace.shown("bluetooth", BluetoothMsg.PAIRING_RESPONSE))
    }

    @Test fun namesAreReadable() {
        assertEquals("BATTERY_STATUS", ProtocolTrace.name(null, ControlMsg.BATTERY_STATUS_NOTIFICATION))
        assertEquals("VIDEO_FOCUS_REQUEST", ProtocolTrace.name("video", MediaMsg.VIDEO_FOCUS_REQUEST))
        assertEquals("NAV_TURN_EVENT", ProtocolTrace.name("navstatus", NavStatusMsg.TURN_EVENT))
        assertEquals("0x8001", ProtocolTrace.name("bluetooth", BluetoothMsg.PAIRING_REQUEST))
        assertEquals("0x63", ProtocolTrace.name(null, 99))
    }
}
