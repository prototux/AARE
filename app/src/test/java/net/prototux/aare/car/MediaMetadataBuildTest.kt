package net.prototux.aare.car

import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import net.prototux.aare.media.PlaybackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMetadataBuildTest {
    private fun info(title: String = "Title", artist: String = "Artist", album: String = "Album", durationMs: Long = 180_000) =
        PlaybackInfo(
            packageName = "com.example.player", title = title, artist = artist, album = album,
            art = null, playing = true, positionMs = 0, durationMs = durationMs, sampledAtMs = 0,
        )

    private fun build(p: PlaybackInfo): MediaPlaybackMetadata =
        fillMediaMetadata(MediaPlaybackMetadata.newBuilder(), p).build()

    @Test
    fun setsEveryFieldWhenAllAreKnown() {
        val meta = build(info())
        assertEquals("Title", meta.song)
        assertEquals("Artist", meta.artist)
        assertEquals("Album", meta.album)
        assertEquals(180, meta.durationSeconds)
    }

    @Test
    fun leavesArtistUnsetWhenBlank() {
        val meta = build(info(artist = ""))
        assertFalse(meta.hasArtist())
        assertTrue(meta.hasSong())
    }

    @Test
    fun leavesAlbumUnsetWhenBlank() {
        val meta = build(info(album = "  "))
        assertFalse(meta.hasAlbum())
    }

    @Test
    fun leavesDurationUnsetWhenZeroOrNegative() {
        assertFalse(build(info(durationMs = 0)).hasDurationSeconds())
        assertFalse(build(info(durationMs = -1)).hasDurationSeconds())
    }

    @Test
    fun leavesSongUnsetWhenBlank() {
        val meta = build(info(title = ""))
        assertFalse(meta.hasSong())
    }

    @Test
    fun aLiveStreamWithNothingButATitleLeavesTheRestUnset() {
        val meta = build(info(artist = "", album = "", durationMs = 0))
        assertTrue(meta.hasSong())
        assertFalse(meta.hasArtist())
        assertFalse(meta.hasAlbum())
        assertFalse(meta.hasDurationSeconds())
    }
}
