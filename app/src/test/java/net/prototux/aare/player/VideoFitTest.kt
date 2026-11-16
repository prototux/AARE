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

    @Test fun theButtonGoesRoundAndUnknownIdsAreFit() {
        assertEquals(listOf(VideoFit.ZOOM, VideoFit.STRETCH, VideoFit.FIT), VideoFit.entries.map { it.next() })
        assertEquals(VideoFit.FIT, VideoFit.of("bogus"))
        assertEquals(VideoFit.ZOOM, VideoFit.of("zoom"))
    }
}
