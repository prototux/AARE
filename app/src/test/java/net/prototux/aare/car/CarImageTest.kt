package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Test

class CarImageTest {
    @Test
    fun leavesAnImageThatAlreadyFitsAlone() {
        assertEquals(CarImage.Size(200, 100), CarImage.targetSize(200, 100))
        assertEquals(CarImage.Size(256, 256), CarImage.targetSize(256, 256))
    }

    @Test
    fun neverUpscalesASmallImage() {
        assertEquals(CarImage.Size(40, 20), CarImage.targetSize(40, 20))
    }

    @Test
    fun shrinksAWideImageToFitTheWidth() {
        // 1024x512 is twice the bound wide: both sides halve.
        assertEquals(CarImage.Size(256, 128), CarImage.targetSize(1024, 512))
    }

    @Test
    fun shrinksATallPortraitImageToFitTheHeightNotJustTheWidth() {
        // Portrait album art: scaling by width alone (the old bug) would leave the height at
        // 1024, well over the 256 px bound on both sides.
        assertEquals(CarImage.Size(128, 256), CarImage.targetSize(512, 1024))
    }

    @Test
    fun shrinksASquareImageToTheBound() {
        assertEquals(CarImage.Size(256, 256), CarImage.targetSize(1000, 1000))
    }

    @Test
    fun honoursACustomBound() {
        assertEquals(CarImage.Size(96, 48), CarImage.targetSize(1024, 512, maxSide = 96))
    }

    @Test
    fun degenerateSizesStayAtLeastOnePixel() {
        assertEquals(CarImage.Size(1, 1), CarImage.targetSize(0, 0))
        assertEquals(CarImage.Size(256, 1), CarImage.targetSize(100_000, 1))
    }
}
