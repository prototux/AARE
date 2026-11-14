package net.prototux.aare.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** Default silence ahead of a car audio stream. */
class StartSilenceTest {
    @Test fun noneOverUsb() {
        assertEquals(0, StartSilence.configured(wireless = false, guidance = false, protocolMajor = 1, mediaOverride = 8, guidanceOverride = 8))
        assertEquals(0, StartSilence.configured(wireless = false, guidance = true, protocolMajor = 1, mediaOverride = null, guidanceOverride = null))
    }

    @Test fun overWifiFourEachNoneForMediaFromProtocol5() {
        assertEquals(4, StartSilence.configured(wireless = true, guidance = false, protocolMajor = 1, mediaOverride = null, guidanceOverride = null))
        assertEquals(4, StartSilence.configured(wireless = true, guidance = true, protocolMajor = 1, mediaOverride = null, guidanceOverride = null))
        assertEquals(0, StartSilence.configured(wireless = true, guidance = false, protocolMajor = 5, mediaOverride = null, guidanceOverride = null))
        assertEquals(4, StartSilence.configured(wireless = true, guidance = true, protocolMajor = 6, mediaOverride = null, guidanceOverride = null))
    }

    @Test fun aVehicleRuleWinsOverWifi() {
        assertEquals(8, StartSilence.configured(wireless = true, guidance = false, protocolMajor = 6, mediaOverride = 8, guidanceOverride = null))
        assertEquals(8, StartSilence.configured(wireless = true, guidance = true, protocolMajor = 1, mediaOverride = null, guidanceOverride = 8))
    }

    @Test fun neverMoreThanTheStreamWasQuietFor() {
        val media = 48_000.0 / 2048 // 23.4 messages a second
        assertEquals(4, StartSilence.cap(4, 60_000, media))
        assertEquals(0, StartSilence.cap(4, 0, media))
        // 100 ms is 2.3 messages, less half a message: 1.
        assertEquals(1, StartSilence.cap(4, 100, media))
        assertEquals(0, StartSilence.cap(0, 60_000, media))
    }
}
