package net.prototux.aare.car

import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the car's audio focus lets play. */
class AudioFocusQuirksTest {
    @Test fun gainTransientIsForNavigationVoiceOnly() {
        assertFalse(CarSession.mediaAllowed(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT, gainTransientIsMedia = false))
        assertTrue(CarSession.guidanceAllowed(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT))
    }

    @Test fun aGainTransientAnsweringTheMusicsRequestLetsItPlay() {
        // The music's own request, or the car on its own: music, as before.
        assertTrue(CarSession.gainTransientIsMedia(answersGuidance = false, quirk = false))
        // A navigation prompt's request: voice only, unless a rule says the head unit means music too.
        assertFalse(CarSession.gainTransientIsMedia(answersGuidance = true, quirk = false))
        assertTrue(CarSession.gainTransientIsMedia(answersGuidance = true, quirk = true))
    }

    @Test fun exceptForHeadUnitsWhoseGainTransientMeansMusicToo() =
        assertTrue(CarSession.mediaAllowed(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT, gainTransientIsMedia = true))

    @Test fun theOtherStatesAreUnchanged() {
        for (s in listOf(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN, AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY,
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK)) assertTrue(s.name, CarSession.mediaAllowed(s, false))
        for (s in listOf(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY, AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS,
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT)) assertFalse(s.name, CarSession.mediaAllowed(s, true))
        assertTrue(CarSession.guidanceAllowed(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY))
        assertTrue(CarSession.guidanceAllowed(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK))
        assertFalse(CarSession.guidanceAllowed(AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS))
    }
}
