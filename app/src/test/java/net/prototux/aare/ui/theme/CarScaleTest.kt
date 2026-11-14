package net.prototux.aare.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Text size and control size are independent settings, each resolved from a stored id. */
class CarScaleTest {
    @Test
    fun unknownOrMissingIdFallsBackToDefault() {
        assertEquals(CarTextSize.DEFAULT, CarTextSize.byId("nonsense"))
        assertEquals(CarControlSize.DEFAULT, CarControlSize.byId(""))
    }

    @Test
    fun idsRoundTripToTheRightStep() {
        assertEquals(CarTextSize.LARGER, CarTextSize.byId("larger"))
        assertEquals(CarTextSize.LARGEST, CarTextSize.byId("largest"))
        assertEquals(CarControlSize.LARGER, CarControlSize.byId("larger"))
        assertEquals(CarControlSize.LARGEST, CarControlSize.byId("largest"))
    }

    @Test
    fun defaultScaleLeavesDimensUntouched() {
        val dimens = CarDimens()
        assertSame(dimens, dimens.scaled(1f))
    }

    @Test
    fun scalingGrowsTouchTargetsIconsAndGutterButNotCornerRadius() {
        val dimens = CarDimens(touchTarget = 76.dp, railWidth = 96.dp, gutter = 12.dp, cardRadius = 28.dp, iconSize = 36.dp)
        val scaled = dimens.scaled(CarControlSize.LARGEST.scale)
        assertEquals(76.dp * 1.3f, scaled.touchTarget)
        assertEquals(96.dp * 1.3f, scaled.railWidth)
        assertEquals(12.dp * 1.3f, scaled.gutter)
        assertEquals(36.dp * 1.3f, scaled.iconSize)
        assertEquals(28.dp, scaled.cardRadius)
    }
}
