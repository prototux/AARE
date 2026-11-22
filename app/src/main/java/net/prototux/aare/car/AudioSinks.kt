package net.prototux.aare.car

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType

/**
 * Which audio sinks to open. AARE only encodes PCM, and a few head units list an AAC sink for a
 * stream next to a PCM one (sometimes before it): fed PCM, that AAC sink stays silent, so it is
 * left closed and the PCM sink plays the stream.
 *
 * Only then, though. A head unit whose only sink for a stream asks for AAC still gets PCM on it,
 * as before 1.5.0: on #82's (built on Google's receiver library) the music plays that way, and
 * leaving its AAC sink closed left the car with no music at all.
 */
internal object AudioSinks {
    /** Whether [sink] stays closed: it asks for another codec, and [all] has a PCM sink of its stream. */
    fun skip(sink: Service, all: List<Service>): Boolean {
        val s = sink.mediaSinkService
        if (s.availableType == MediaCodecType.MEDIA_CODEC_AUDIO_PCM) return false
        return all.any { other ->
            other !== sink && other.hasMediaSinkService() && other.mediaSinkService.videoConfigsCount == 0 &&
                other.mediaSinkService.audioType == s.audioType &&
                other.mediaSinkService.availableType == MediaCodecType.MEDIA_CODEC_AUDIO_PCM
        }
    }
}
