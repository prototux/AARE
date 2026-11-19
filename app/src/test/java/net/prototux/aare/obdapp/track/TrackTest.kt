package net.prototux.aare.obdapp.track

import net.prototux.aare.obdapp.ObdHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Ported from the Painel GTS app's tests, where these timers were first checked. */
class TrackTest {
    // Steady acceleration from [from] to [to] in [seconds], read about 5.5 times a second with an
    // uneven delay, in whole km/h like the OBD sends.
    private fun accelerate(c: SpeedTest, from: Float, to: Float, seconds: Float, startMs: Long, still: Long = 2000): Long {
        var t = startMs
        val r = Random(7)
        while (t < startMs + still) { c.feed(from, t); t += 180 }
        val go = t
        while (true) {
            val s = (t - go) / 1000f
            val v = (from + (to - from) * s / seconds).toInt().toFloat()
            c.feed(v, t)
            if (v >= to + 5) return t
            t += 150 + r.nextInt(70)
        }
    }

    @Test fun zeroTo100() {
        val c = Acceleration(ObdHistory.Kind.ZERO_100, 0f, 100f)
        var got: Float? = null
        c.onResult = { _, v, _ -> got = v }
        c.arm()
        accelerate(c, 0f, 110f, 8.8f, 0) // 0-110 in 8.8 s = 0-100 in 8.0 s
        assertEquals(8.0f, c.result!!, 0.2f)
        assertEquals(c.result, got)
    }

    @Test fun notArmedNoResult() {
        val c = Acceleration(ObdHistory.Kind.ZERO_100, 0f, 100f)
        accelerate(c, 0f, 110f, 8.8f, 0)
        assertNull(c.result)
    }

    @Test fun mustStopFirst() {
        val c = Acceleration(ObdHistory.Kind.ZERO_100, 0f, 100f)
        c.arm()
        c.feed(30f, 0); c.feed(60f, 1000); c.feed(105f, 4000)
        assertNull(c.result)
    }

    @Test fun liftingOffCancels() {
        val c = Acceleration(ObdHistory.Kind.ZERO_100, 0f, 100f)
        c.arm()
        c.feed(0f, 0); c.feed(0f, 200); c.feed(20f, 1500); c.feed(50f, 3000)
        c.feed(40f, 4000)
        assertEquals(SpeedTest.State.WAITING, c.state)
        c.feed(100f, 6000)
        assertNull(c.result)
    }

    @Test fun quarterMile() {
        // 3 m/s² steady: 402 m in sqrt(2×402.336/3) = 16.38 s, at 176.9 km/h.
        val p = QuarterMile()
        p.arm()
        var t = 0L
        repeat(10) { p.feed(0f, t); t += 180 }
        val go = t
        while (p.result == null && t < go + 30_000) { t += 170; p.feed((3f * (t - go) / 1000f * 3.6f).toInt().toFloat(), t) }
        assertEquals(16.38f, p.result!!, 0.25f)
        assertEquals(176.9f, p.endSpeed!!, 3f)
    }

    @Test fun braking() {
        // 9 m/s² from 110 km/h: 100 to 0 = 27.78²/(2×9) = 42.9 m.
        val p = Braking()
        p.arm()
        var t = 0L
        repeat(5) { p.feed(110f, t); t += 180 }
        var v = 110f / 3.6f
        while (v > 0f) { t += 170; v = maxOf(0f, v - 9f * 0.17f); p.feed((v * 3.6f).toInt().toFloat(), t) }
        assertEquals(42.9f, p.result!!, 2.5f)
    }

    @Test fun oneArmedAtATimeAndBest() {
        val s = SpeedTests()
        s.toggle(s.zeroTo100); s.toggle(s.braking)
        assertEquals(listOf<SpeedTest>(s.braking), s.all.filter { it.armed })
        s.toggle(s.braking)
        assertTrue(s.all.none { it.armed })

        val c = s.from80To120
        c.arm()
        var t = accelerate(c, 60f, 130f, 7f, 0) // 80-120 in 4 s
        assertEquals(4.0f, c.result!!, 0.2f)
        t = accelerate(c, 60f, 130f, 10.5f, t + 1000) // 80-120 in 6 s
        assertEquals(6.0f, c.result!!, 0.2f)
        assertEquals(4.0f, c.best!!, 0.2f)
    }

    // --- Laps: a round track of 200 m radius (1257 m), one GPS point a second ---
    private val lat0 = -23.70
    private val lon0 = -46.70
    private val radius = 200.0
    private val lap = 2 * PI * radius

    private fun drive(l: LapTimer, ms: Long, angle: Double, mps: Double, seconds: Int): Pair<Long, Double> {
        var t = ms; var a = angle
        repeat(seconds) {
            val x = radius * cos(a); val y = radius * sin(a)
            l.point(lat0 + y / 110_540.0, lon0 + x / (cos(Math.toRadians(lat0)) * 111_320.0), t, (mps * 3.6).toFloat())
            t += 1000; a += mps / radius
        }
        return t to a
    }

    @Test fun lapTimesWithSlowGps() {
        val l = LapTimer()
        l.arm()
        drive(l, 123_457, 0.0, 25.0, 3 * 51 + 10)
        assertEquals(3, l.laps)
        assertEquals((lap / 25.0).toFloat(), l.last!!, 0.15f)
        val s = l.lastSectors!!
        assertEquals(l.last!!, s.sum(), 0.01f)
        s.forEach { assertEquals(l.last!! / 3, it, 0.3f) }
    }

    @Test fun bestLapAndDelta() {
        val l = LapTimer()
        l.arm()
        var (t, a) = drive(l, 0, 0.0, 20.0, 64)
        val r = drive(l, t, a, 25.0, 52); t = r.first; a = r.second
        assertEquals(2, l.laps)
        assertEquals((lap / 25.0).toFloat(), l.best!!, 0.3f)
        drive(l, t, a, 20.0, 31)
        val d = l.delta()!!
        assertTrue("delta $d", d in 4f..8f)
        assertTrue(l.outline.size > 40)
    }

    @Test fun noFalseLaps() {
        val l = LapTimer()
        l.arm()
        var t = 0L
        for (y in listOf(0.0, 10.0, 20.0, 10.0, 0.0, -10.0, 0.0, 10.0, 20.0, 30.0)) { l.point(lat0 + y / 110_540.0, lon0, t, 36f); t += 1000 }
        assertEquals(0, l.laps)
    }

    // --- Dyno: a pretend car with a steady 120 hp at the wheels, pulling in 3rd from 40 to 140 km/h ---
    private fun pull(d: Dyno, intervalMs: Long): Float {
        if (d.armed) d.toggle()
        d.toggle()
        val power = 120f * 735.5f
        var v = 40f / 3.6f
        var t = 0L; var next = 0L; var n = 0
        while (v < 140f / 3.6f) {
            val drag = 0.5f * 1.15f * 0.66f * v * v + 0.012f * 1330f * 9.81f
            v += (power / v - drag) / (1330f * 1.107f) * 0.01f
            t += 10
            if (t >= next) {
                val rpm = (v * 3.6f * 45f * 4).toInt() / 4f // 3rd: 45 rpm per km/h
                d.feed((v * 3.6f).toInt().toFloat(), rpm, t)
                next = t + intervalMs + (n++ % 3) * 30
            }
        }
        return d.peakHp
    }

    @Test fun dynoFastReadings() {
        val d = Dyno(1330f)
        assertEquals(120f, pull(d, 170), 12f)
        assertTrue(d.curve.count { it > 0f } >= 10)
    }

    @Test fun dynoSlowReadings() {
        assertEquals(120f, pull(Dyno(1330f), 650), 15f)
    }
}
