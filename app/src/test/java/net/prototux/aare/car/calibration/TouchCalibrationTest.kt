package net.prototux.aare.car.calibration

import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import net.prototux.aare.car.ProjectionLayout
import net.prototux.aare.car.TouchMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCalibrationTest {
    private val frameW = 1280
    private val frameH = 720
    private val noMargins = intArrayOf(0, 0, 0, 0)
    private val targets = CalibrationMath.targets(frameW, frameH, noMargins)

    /** The raw points a panel reporting through [toRaw] (frame fraction -> raw) gives for the targets. */
    private fun taps(toRaw: (Double, Double) -> Pair<Double, Double>) = targets.map { toRaw(it.first, it.second) }

    private fun ok(f: Fit) = f as? Fit.Ok ?: throw AssertionError("expected a fit, got $f")

    private fun assertMaps(f: Fit.Ok, raw: Pair<Double, Double>, x: Double, y: Double) {
        assertEquals(x, f.ax * raw.first + f.bx * raw.second + f.cx, 1e-6)
        assertEquals(y, f.ay * raw.first + f.by * raw.second + f.cy, 1e-6)
    }

    @Test
    fun targetsAreInsetClockwiseInsideTheVisibleArea() {
        val t = CalibrationMath.targets(1000, 500, intArrayOf(100, 50, 100, 50))
        // Visible 800x400 from (100, 50); 12% in from each corner.
        assertEquals(196.0 / 1000, t[0].first, 1e-9); assertEquals(98.0 / 500, t[0].second, 1e-9)
        assertEquals(804.0 / 1000, t[1].first, 1e-9); assertEquals(98.0 / 500, t[1].second, 1e-9)
        assertEquals(804.0 / 1000, t[2].first, 1e-9); assertEquals(402.0 / 500, t[2].second, 1e-9)
        assertEquals(196.0 / 1000, t[3].first, 1e-9); assertEquals(402.0 / 500, t[3].second, 1e-9)
        assertEquals(0.5, CalibrationMath.centre(1000, 500, intArrayOf(100, 50, 100, 50)).first, 1e-9)
    }

    @Test
    fun panelReportingAMuchLargerRangeIsScaledDown() {
        // The reported bug: the car sends 0..32767 on both axes but AARE assumed the video size.
        val f = ok(CalibrationMath.fit(taps { x, y -> x * 32767 to y * 32767 }, targets))
        assertMaps(f, 0.0 to 0.0, 0.0, 0.0)
        assertMaps(f, 32767.0 to 32767.0, 1.0, 1.0)
        assertMaps(f, 16383.5 to 8191.75, 0.5, 0.25)
        assertTrue(f.maxError < 1e-9)
    }

    @Test
    fun offsetSwappedAndMirroredAxesAreFitted() {
        // Raw x follows the screen's y, raw y the screen's x mirrored, with an offset.
        val f = ok(CalibrationMath.fit(taps { x, y -> 200 + y * 4000 to 100 + (1 - x) * 6000 }, targets))
        assertMaps(f, 200.0 to 6100.0, 0.0, 0.0)
        assertMaps(f, 4200.0 to 100.0, 1.0, 1.0)
    }

    @Test
    fun hugeRawValuesStayPrecise() {
        // uint32 panels: up to 2^32.
        val f = ok(CalibrationMath.fit(taps { x, y -> 1e9 + x * 3e9 to 5e8 + y * 3.5e9 }, targets))
        assertMaps(f, 1e9 + 0.3 * 3e9 to 5e8 + 0.7 * 3.5e9, 0.3, 0.7)
    }

    @Test
    fun oneBadTapIsSingledOut() {
        val raw = taps { x, y -> x * 32767 to y * 32767 }.toMutableList()
        raw[2] = 1000.0 to 1000.0 // target 3 (bottom right) tapped near the top left
        val f = CalibrationMath.fit(raw, targets)
        assertTrue("$f", f is Fit.Inconsistent && f.worst == 2)
    }

    @Test
    fun tapsAtOnePlaceOrOnALineCantBeFitted() {
        assertEquals(Fit.Degenerate, CalibrationMath.fit(List(4) { 500.0 to 500.0 }, targets))
        assertEquals(Fit.Degenerate, CalibrationMath.fit(List(4) { 100.0 * it to 100.0 * it }, targets))
    }

    @Test
    fun encodesAndDecodes() {
        val c = TouchCalibration(1.0 / 32767, 1e-7, 0.001, -2e-7, 1.0 / 32767, -0.002, 0.01, 0.02, 0.03, 0.04, 1920, 1080)
        assertEquals(c, TouchCalibration.decode(c.encode()))
        assertNull(TouchCalibration.decode("2;1;2"))
        assertNull(TouchCalibration.decode("1;0;0;0;0;0;0;0;0;0;0;0;720")) // no width
        assertTrue(c.fits(1920, 1080))
        assertTrue(!c.fits(1280, 720))
        assertNull(TouchCalibration.decode("1;a;0;0;0;0;0;0;0;0;0"))
        assertNull(TouchCalibration.decode(null))
    }

    @Test
    fun mapperUsesTheCalibrationAndKeepsTheDefaultWithout() {
        val layout = ProjectionLayout.of(frameW, frameH, 0, 0, null, null)
        val c = TouchCalibration(1.0 / 32767, 0.0, 0.0, 0.0, 1.0 / 32767, 0.0, 0.0, 0.0, 0.0, 0.0, 1280, 720)
        fun ev(x: Int, y: Int) = TouchEvent.newBuilder().setAction(PointerAction.ACTION_DOWN)
            .addPointerData(TouchEvent.Pointer.newBuilder().setX(x).setY(y).setPointerId(0)).build()
        // MotionEvent is a stub in unit tests: check the mapping through mapX/mapY and the calibration itself.
        val plain = TouchMapper(1280, 720, layout)
        assertEquals(640f, plain.mapX(640), 0.001f)
        assertEquals(0.5, c.x(16383.5, 0.0), 1e-9)
        TouchMapper(1280, 720, layout, c).map(ev(16383, 16383)) // must not throw
    }

    @Test
    fun layoutTakesTheSavedMargins() {
        val car = ProjectionLayout.of(1280, 720, 0, 80, null, null)
        val l = car.withMargins(10, 20, 30, 40)
        assertEquals(listOf(10, 20, 30, 40), listOf(l.marginLeft, l.marginTop, l.marginRight, l.marginBottom))
        assertEquals(1240, l.visibleWidth); assertEquals(660, l.visibleHeight)
        val c = TouchCalibration(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 10.0 / 1280, 20.0 / 720, 30.0 / 1280, 40.0 / 720, 1280, 720)
        assertEquals(listOf(20, 40, 60, 80), c.margins(2560, 1440).toList())
    }

    @Test
    fun citroenMarelliTapsFromARealCar() {
        // Citroën, Marelli IVI2020: declares an 800x480 panel, a 1920x1080 frame with 220 px
        // margins above and below, and reports touches in pixels of the visible 1920x640 area.
        val margins = intArrayOf(0, 220, 0, 220)
        val t = CalibrationMath.targets(1920, 1080, margins)
        val f = ok(CalibrationMath.fit(listOf(246.0 to 78.0, 1688.0 to 95.0, 1686.0 to 534.0, 241.0 to 555.0), t))
        assertTrue(f.maxError < 0.01)
        // The check tap, on the centre target.
        val c = CalibrationMath.centre(1920, 1080, margins)
        assertEquals(c.first, f.ax * 961 + f.bx * 313 + f.cx, 0.005)
        assertEquals(c.second, f.ay * 961 + f.by * 313 + f.cy, 0.005)
    }
}
