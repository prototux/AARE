package net.prototux.aare.car

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.MediaSinkServiceOuterClass.MediaSinkService
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSinksTest {
    private fun sink(id: Int, stream: AudioStreamType, codec: MediaCodecType? = null): Service =
        Service.newBuilder().setId(id).setMediaSinkService(
            MediaSinkService.newBuilder().setAudioType(stream).apply { codec?.let { setAvailableType(it) } },
        ).buildPartial()

    private val media = AudioStreamType.AUDIO_STREAM_MEDIA
    private val guidance = AudioStreamType.AUDIO_STREAM_GUIDANCE
    private val aac = MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC
    private val pcm = MediaCodecType.MEDIA_CODEC_AUDIO_PCM

    /** #82: the car's only music sink asks for AAC; skipping it left the car without music. */
    @Test fun onlySinkOfItsStreamIsOpenedEvenIfItAsksForAac() {
        val music = sink(4, media, aac)
        assertFalse(AudioSinks.skip(music, listOf(music, sink(5, guidance, pcm))))
    }

    /** a970bb5: an AAC sink next to a PCM one of the same stream stays closed, the PCM one plays. */
    @Test fun aacSinkBesideAPcmOneIsSkipped() {
        val aacMusic = sink(3, media, aac)
        val all = listOf(aacMusic, sink(4, media, pcm))
        assertTrue(AudioSinks.skip(aacMusic, all))
        assertFalse(AudioSinks.skip(all[1], all))
    }

    /** A PCM sink of another stream doesn't count. */
    @Test fun pcmSinkOfAnotherStreamDoesNotCount() {
        val music = sink(4, media, aac)
        assertFalse(AudioSinks.skip(music, listOf(music, sink(5, guidance, pcm))))
    }

    /** No codec stated reads as PCM (the proto default): never skipped. */
    @Test fun sinkWithoutCodecIsPcm() {
        val music = sink(4, media)
        assertFalse(AudioSinks.skip(music, listOf(music, sink(3, media, pcm))))
    }
}
