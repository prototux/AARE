package net.prototux.aare.ui.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaVideoTest {
    @Test
    fun thePlayerUntilTheVideoIsOpened() {
        assertEquals(MediaVideoView.PLAYER, mediaVideoView(open = false, driving = false))
        assertEquals(MediaVideoView.PLAYER, mediaVideoView(open = false, driving = true))
    }

    @Test
    fun theVideoOnlyWhileParkedThenTheCoverArt() {
        assertEquals(MediaVideoView.VIDEO, mediaVideoView(open = true, driving = false))
        assertEquals(MediaVideoView.ART, mediaVideoView(open = true, driving = true))
    }

    @Test
    fun offeredOnlyWhenOnWithALauncherAndParked() {
        assertTrue(mediaVideoOffered(enabled = true, launcherAvailable = true, driving = false))
        assertFalse(mediaVideoOffered(enabled = false, launcherAvailable = true, driving = false))
        assertFalse(mediaVideoOffered(enabled = true, launcherAvailable = false, driving = false))
        assertFalse(mediaVideoOffered(enabled = true, launcherAvailable = true, driving = true))
    }
}
