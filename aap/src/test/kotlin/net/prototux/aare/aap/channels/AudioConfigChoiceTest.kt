package net.prototux.aare.aap.channels

import aap_protobuf.service.media.shared.message.AudioConfigurationOuterClass.AudioConfiguration
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The audio configuration each stream plays, picked from the head unit's list. */
class AudioConfigChoiceTest {
    private fun pcm(rate: Int, channels: Int) =
        AudioConfiguration.newBuilder().setSamplingRate(rate).setNumberOfBits(16).setNumberOfChannels(channels).build()

    private val mono16 = pcm(16_000, 1)
    private val mono48 = pcm(48_000, 1)
    private val stereo48 = pcm(48_000, 2)
    private val stereo44 = pcm(44_100, 2)

    private fun choose(type: AudioStreamType, configs: List<AudioConfiguration>, preferred: List<Int> = emptyList(), modern: Boolean = true) =
        AudioChannel.chooseConfig(type, configs, preferred, modern)

    @Test
    fun theHeadUnitsPreferredConfigWins() {
        assertEquals(0, choose(AudioStreamType.AUDIO_STREAM_MEDIA, listOf(stereo44, stereo48, mono48), preferred = listOf(0)))
        assertEquals(0, choose(AudioStreamType.AUDIO_STREAM_GUIDANCE, listOf(mono16, mono48), preferred = listOf(0)))
    }

    @Test
    fun withoutAPreferenceMediaPlays48kStereo() {
        assertEquals(1, choose(AudioStreamType.AUDIO_STREAM_MEDIA, listOf(stereo44, stereo48, mono48)))
    }

    @Test
    fun withoutAPreferenceOtherStreamsTake48kMonoFromProtocol16() {
        for (type in listOf(AudioStreamType.AUDIO_STREAM_GUIDANCE, AudioStreamType.AUDIO_STREAM_SYSTEM_AUDIO, AudioStreamType.AUDIO_STREAM_TELEPHONY)) {
            assertEquals(type.name, 1, choose(type, listOf(mono16, mono48)))
            assertEquals("16 kHz mono when there is no 48 kHz mono", 0, choose(type, listOf(mono16, stereo48)))
        }
    }

    @Test
    fun withoutAPreferenceOlderHeadUnitsGet16kMono() {
        assertEquals(0, choose(AudioStreamType.AUDIO_STREAM_GUIDANCE, listOf(mono16, mono48), modern = false))
    }

    @Test
    fun anUnusablePreferenceIsTreatedAsNone() {
        assertEquals("an index out of the list is not used", 1, choose(AudioStreamType.AUDIO_STREAM_MEDIA, listOf(mono16, stereo48), preferred = listOf(7)))
    }

    @Test
    fun noMatchTakesTheFirst() {
        assertEquals(0, choose(AudioStreamType.AUDIO_STREAM_MEDIA, listOf(mono16, stereo44)))
        assertEquals(0, choose(AudioStreamType.AUDIO_STREAM_GUIDANCE, listOf(stereo44)))
    }
}
