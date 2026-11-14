package net.prototux.aare.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToastGuardrailTest {
    @Test
    fun aZeroWindowNeverLimits() {
        val guardrail = ToastGuardrail(max = 1, windowMs = 0)
        repeat(10) { assertTrue(guardrail.allow("a", it * 1_000L)) }
    }

    @Test
    fun limitsPerComponentInsideTheWindow() {
        val guardrail = ToastGuardrail(max = 2, windowMs = 10_000)
        assertTrue(guardrail.allow("a", 0))
        assertTrue(guardrail.allow("a", 1_000))
        assertFalse(guardrail.allow("a", 2_000))
        // A different component keeps its own count.
        assertTrue(guardrail.allow("b", 2_000))
    }

    @Test
    fun oldPostsLeaveTheWindow() {
        val guardrail = ToastGuardrail(max = 2, windowMs = 10_000)
        repeat(2) { assertTrue(guardrail.allow("a", it * 1_000L)) }
        // The first two are more than 10 s old by now.
        assertTrue(guardrail.allow("a", 11_500))
    }
}
