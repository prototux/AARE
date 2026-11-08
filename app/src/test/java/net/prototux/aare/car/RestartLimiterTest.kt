package net.prototux.aare.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartLimiterTest {
    private val limiter = RestartLimiter(max = 3, windowMs = 10_000)

    @Test
    fun allowsUpToMaxInsideTheWindow() {
        assertTrue(limiter.allow(0))
        assertTrue(limiter.allow(1_000))
        assertTrue(limiter.allow(2_000))
        assertFalse(limiter.allow(3_000))
    }

    @Test
    fun oldRestartsLeaveTheWindow() {
        repeat(3) { assertTrue(limiter.allow(it * 1_000L)) }
        // The first two are more than 10 s old by now.
        assertTrue(limiter.allow(11_500))
    }
}
