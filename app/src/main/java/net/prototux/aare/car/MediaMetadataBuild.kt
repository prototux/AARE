package net.prototux.aare.car

import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import net.prototux.aare.media.PlaybackInfo

/**
 * Fills a MediaPlaybackMetadata builder from the phone's playback info. Every text field here is
 * proto2 `optional`: a head unit falls back to its own placeholder ("Live", a station name, a
 * scrubber with no end) for a field left unset, but shows a blank one for a field sent as ""
 * or 0. Live streams and radio apps routinely leave title, artist, album or duration empty, so
 * each field is only set when the player actually reported something for it.
 */
internal fun fillMediaMetadata(builder: MediaPlaybackMetadata.Builder, info: PlaybackInfo): MediaPlaybackMetadata.Builder {
    if (info.title.isNotBlank()) builder.setSong(info.title)
    if (info.artist.isNotBlank()) builder.setArtist(info.artist)
    if (info.album.isNotBlank()) builder.setAlbum(info.album)
    if (info.durationMs > 0) builder.setDurationSeconds((info.durationMs / 1000).toInt())
    return builder
}
