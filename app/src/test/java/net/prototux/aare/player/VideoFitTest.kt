package net.prototux.aare.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFitTest {
    private val wide = 16f / 9f
    private val old = 4f / 3f

    @Test fun fitKeepsTheWholePicture() {
        // A 4:3 picture on an 800x480 screen: full height, bars on the sides.
        assertEquals(640 to 480, surfaceSize(800, 480, old, VideoFit.FIT))
        // A 2.4:1 film: full width, bars above and below.
        assertEquals(800 to 333, surfaceSize(800, 480, 2.4f, VideoFit.FIT))
    }

    @Test fun zoomFillsTheScreenAndCutsTheEdges() {
        assertEquals(800 to 600, surfaceSize(800, 480, old, VideoFit.ZOOM))
        assertEquals(1152 to 480, surfaceSize(800, 480, 2.4f, VideoFit.ZOOM))
    }

    @Test fun stretchIsTheScreen() {
        assertEquals(800 to 480, surfaceSize(800, 480, old, VideoFit.STRETCH))
    }

    @Test fun sameShapeIsTheScreenInEveryMode() {
        for (fit in VideoFit.entries) assertEquals(1280 to 720, surfaceSize(1280, 720, wide, fit))
    }

    @Test fun zoomPercentIsOfTheFittedPicture() {
        // 4:3 on 800x480 fits at 640x480; 125 % of that.
        assertEquals(800 to 600, surfaceSize(800, 480, old, VideoFit.ZOOM, 125))
        assertEquals(320 to 240, surfaceSize(800, 480, old, VideoFit.ZOOM, 50))
        // Fit ignores the zoom.
        assertEquals(640 to 480, surfaceSize(800, 480, old, VideoFit.FIT, 200))
    }

    @Test fun fillIsWhatZeroZoomMeans() {
        assertEquals(125, fillPct(800, 480, old))
        assertEquals(100, fillPct(1280, 720, wide))
        assertEquals(surfaceSize(800, 480, old, VideoFit.ZOOM, 0), surfaceSize(800, 480, old, VideoFit.ZOOM, fillPct(800, 480, old)))
    }

    @Test fun nudgesStartFromFillAndLandOnRoundNumbers() {
        // Filling a 1024x600 screen with 16:9 takes 104 %: + gives 105, - gives 100.
        val fill = fillPct(1024, 600, wide)
        assertEquals(104, fill)
        assertEquals(105, nudgeZoom(0, fill, 1))
        assertEquals(100, nudgeZoom(0, fill, -1))
        // A 1920x720 strip: 150 %, already round, so a press moves a whole step.
        assertEquals(155, nudgeZoom(0, fillPct(1920, 720, wide), 1))
        assertEquals(130, nudgeZoom(125, fill, 1))
        assertEquals(ZOOM_MAX_PCT, nudgeZoom(ZOOM_MAX_PCT, fill, 1))
        assertEquals(ZOOM_MIN_PCT, nudgeZoom(ZOOM_MIN_PCT, fill, -1))
    }

    @Test fun theButtonGoesRoundAndUnknownIdsAreFit() {
        assertEquals(listOf(VideoFit.ZOOM, VideoFit.STRETCH, VideoFit.FIT), VideoFit.entries.map { it.next() })
        assertEquals(VideoFit.FIT, VideoFit.of("bogus"))
        assertEquals(VideoFit.ZOOM, VideoFit.of("zoom"))
    }
}
