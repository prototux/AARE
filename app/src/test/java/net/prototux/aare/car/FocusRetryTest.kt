package net.prototux.aare.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusRetryTest {
    private val retry = FocusRetry()

    @Test
    fun noRetryWhenAnsweredInTime() {
        retry.onRequested(0)
        assertNull(retry.onAnswered())
        assertEquals(FocusRetry.Action.None, retry.due(1_000))
        assertNull(retry.nextCheckAtMs())
    }

    @Test
    fun asksAgainAfterOneSecondWithoutAnswer() {
        retry.onRequested(0)
        assertEquals(1_000L, retry.nextCheckAtMs())
        assertEquals(FocusRetry.Action.None, retry.due(999))
        assertEquals(FocusRetry.Action.Ask, retry.due(1_000))
        assertEquals(3_000L, retry.nextCheckAtMs())
    }

    @Test
    fun rearmsEveryTwoSecondsAfterTheFirstAsk() {
        retry.onRequested(0)
        assertEquals(FocusRetry.Action.Ask, retry.due(1_000))
        assertEquals(FocusRetry.Action.None, retry.due(2_999))
        assertEquals(FocusRetry.Action.Rearm, retry.due(3_000))
        assertEquals(FocusRetry.Action.Rearm, retry.due(5_000))
        assertEquals(7_000L, retry.nextCheckAtMs())
    }

    @Test
    fun givesUpAfterFifteen() {
        retry.onRequested(0)
        assertEquals(FocusRetry.Action.Ask, retry.due(1_000))
        var now = 3_000L
        repeat(14) {
            assertEquals(FocusRetry.Action.Rearm, retry.due(now))
            now += 2_000
        }
        assertEquals(FocusRetry.Action.GiveUp(asked = 16), retry.due(now))
        assertNull(retry.nextCheckAtMs())
        assertEquals(FocusRetry.Action.None, retry.due(now + 10_000))
    }

    @Test
    fun answerStopsRetries() {
        retry.onRequested(0)
        assertEquals(FocusRetry.Action.Ask, retry.due(1_000))
        assertEquals(FocusRetry.Action.Rearm, retry.due(3_000))
        retry.onAnswered()
        assertEquals(FocusRetry.Action.None, retry.due(5_000))
        assertNull(retry.nextCheckAtMs())
    }

    @Test
    fun answerAfterAskingAgainTellsHowManyAsks() {
        retry.onRequested(0)
        retry.due(1_000)
        retry.due(3_000)
        assertEquals(3, retry.onAnswered())
        // Already stopped: a second notification says nothing more.
        assertNull(retry.onAnswered())
    }

    @Test
    fun withdrawStopsRetries() {
        retry.onRequested(0)
        retry.onWithdrawn()
        assertEquals(FocusRetry.Action.None, retry.due(1_000))
        assertNull(retry.nextCheckAtMs())
    }

    @Test
    fun postponedAskIsNotCounted() {
        retry.onRequested(0)
        retry.postpone(1_000)
        assertEquals(3_000L, retry.nextCheckAtMs())
        assertEquals(FocusRetry.Action.None, retry.due(2_999))
        // Still the first ask, and still 15 of them in all.
        assertEquals(FocusRetry.Action.Ask, retry.due(3_000))
        var now = 5_000L
        repeat(14) {
            assertEquals(FocusRetry.Action.Rearm, retry.due(now))
            now += 2_000
        }
        assertEquals(FocusRetry.Action.GiveUp(asked = 16), retry.due(now))
    }

    @Test
    fun postponeWhileIdleDoesNothing() {
        retry.postpone(1_000)
        assertNull(retry.nextCheckAtMs())
    }

    @Test
    fun newRequestRestartsTheCount() {
        retry.onRequested(0)
        var now = 1_000L
        repeat(15) { retry.due(now); now += 2_000 }
        assertEquals(FocusRetry.Action.GiveUp(asked = 16), retry.due(now))

        val t = now + 60_000
        retry.onRequested(t)
        assertEquals(t + 1_000, retry.nextCheckAtMs())
        assertEquals(FocusRetry.Action.Ask, retry.due(t + 1_000))
        var at = t + 3_000
        repeat(14) {
            assertEquals(FocusRetry.Action.Rearm, retry.due(at))
            at += 2_000
        }
        assertEquals(FocusRetry.Action.GiveUp(asked = 16), retry.due(at))
    }
}
