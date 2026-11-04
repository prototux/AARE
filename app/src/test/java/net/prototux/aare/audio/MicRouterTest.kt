package net.prototux.aare.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MicRouterTest {
    /**
     * No car mic, and the phone's can't start: the unit-test android.jar's AudioRecord never
     * reaches STATE_INITIALIZED, like a mic that's busy in a call.
     */
    @Test
    fun aPhoneMicThatCantStartEndsTheClient() {
        val voice = Collections.synchronizedList(ArrayList<Boolean>())
        val router = MicRouter({ null }, { voice += it })
        repeat(2) { attempt ->
            val stopped = CountDownLatch(1)
            router.open({ _, _, _ -> }, { stopped.countDown() })
            assertTrue("attempt $attempt told to stop", stopped.await(5, TimeUnit.SECONDS))
        }
        // Each attempt opened and closed the voice session; the second one could start again.
        assertEquals(listOf(true, false, true, false), voice.toList())
    }
}
