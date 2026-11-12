package net.prototux.aare.car.calibration

import aap_protobuf.service.inputsource.message.PointerActionOuterClass.PointerAction
import aap_protobuf.service.inputsource.message.TouchEventOuterClass.TouchEvent
import net.prototux.aare.car.ProjectionLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationControllerTest {
    /** Runs posted work at once; delayed work when [advance] says so. */
    private class FakeScheduler : CalibrationController.Scheduler {
        val delayed = mutableListOf<Runnable>()
        override fun post(r: Runnable) = r.run()
        override fun postDelayed(r: Runnable, ms: Long) { delayed += r }
        override fun remove(r: Runnable) { delayed.remove(r) }
        fun advance() { val d = delayed.toList(); delayed.clear(); d.forEach { it.run() } }
    }

    private class FakeHost : CalibrationController.Host {
        override val car = "Marelli IVI2020"
        override var driving = false
        override var projection: CalibrationController.Projection? = null
        var applied: TouchCalibration? = null
        var applyCount = 0
        override fun apply(c: TouchCalibration?) { applied = c; applyCount++ }
    }

    private val host = FakeHost()
    private val clock = FakeScheduler()
    private val ctl = CalibrationController(host, clock)
    private val state get() = CalibrationHub.state.value

    /** A panel that reports 0..32767 over the whole frame. */
    private fun tap(fx: Double, fy: Double) = ctl.onTouchFromReader(
        TouchEvent.newBuilder().setAction(PointerAction.ACTION_DOWN)
            .addPointerData(TouchEvent.Pointer.newBuilder().setX((fx * 32767).toInt()).setY((fy * 32767).toInt()).setPointerId(0)).build(),
    )

    private fun connect() {
        val p = CalibrationController.Projection(1280, 720, ProjectionLayout.of(1280, 720, 0, 0, null, null), null)
        host.projection = p
        ctl.onProjection(p)
    }

    private fun tapTargets() {
        repeat(4) { (state as CalState.Target).point.let { tap(it.first, it.second) } }
    }

    @Test
    fun fullRunSavesWhenTheCheckTapHits() {
        connect()
        assertTrue(state is CalState.Ready)
        ctl.start()
        assertTrue(state is CalState.Area)
        ctl.setMargins(listOf(0, 0, 0, 0))
        ctl.next()
        assertTrue(ctl.capturing)
        tapTargets()
        val verify = state as CalState.Verify
        assertEquals(CalibrationController.VERIFY_SECONDS, verify.secondsLeft)
        tap(verify.point.first, verify.point.second)
        assertEquals(CalState.Result(CalState.Outcome.SAVED), state)
        assertFalse(ctl.capturing)
        val c = host.applied!!
        assertEquals(0.5, c.x(16383.5, 0.0), 1e-4)
        ctl.close()
    }

    @Test
    fun missedCheckTapKeepsTheOldMapping() {
        connect(); ctl.start(); ctl.next(); tapTargets()
        tap(0.05, 0.05)
        assertEquals(CalState.Result(CalState.Outcome.MISSED), state)
        assertEquals(0, host.applyCount)
        assertFalse(ctl.capturing)
        ctl.close()
    }

    @Test
    fun noCheckTapRevertsAfterTheCountdown() {
        connect(); ctl.start(); ctl.next(); tapTargets()
        repeat(CalibrationController.VERIFY_SECONDS - 1) { clock.advance() }
        assertEquals(1, (state as CalState.Verify).secondsLeft)
        clock.advance()
        assertEquals(CalState.Result(CalState.Outcome.TIMEOUT), state)
        assertEquals(0, host.applyCount)
        ctl.close()
    }

    @Test
    fun aStrayTapIsAskedAgain() {
        connect(); ctl.start(); ctl.next()
        val t = List(4) { i -> (state as CalState.Target).point.also { if (i == 2) tap(0.1, 0.1) else tap(it.first, it.second) } }
        val retry = state as CalState.Target
        assertEquals(2, retry.index); assertTrue(retry.retry)
        tap(t[2].first, t[2].second)
        assertTrue(state is CalState.Verify)
        ctl.cancel()
        assertEquals(CalState.Result(CalState.Outcome.CANCELLED), state)
        assertEquals(0, host.applyCount)
        ctl.close()
    }

    @Test
    fun notWhileDrivingAndNothingSavedWhenTheVideoRestarts() {
        connect()
        host.driving = true
        ctl.start()
        assertTrue((state as CalState.Ready).driving)
        host.driving = false
        ctl.start(); ctl.next()
        connect() // the car sent a new video config mid-calibration
        assertEquals(CalState.Result(CalState.Outcome.INTERRUPTED), state)
        assertFalse(ctl.capturing)
        ctl.close()
        assertEquals(CalState.Unavailable, state)
        assertNull(host.applied)
    }

    @Test
    fun edgesStayWithinTheFrame() {
        connect(); ctl.start()
        ctl.setMargins(listOf(-5, 10_000, 20, 30))
        assertEquals(listOf(0, 270, 20, 30), (state as CalState.Area).margins)
        ctl.close()
    }
}
