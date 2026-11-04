package net.prototux.aare.car

import android.os.SystemClock

/**
 * Caps restarts of something that keeps failing (a video encoder): at most [max] in any
 * [windowMs], so a broken codec ends in a clean stop instead of a create/release loop.
 * Not thread-safe: use it from one thread (the main thread).
 */
class RestartLimiter(private val max: Int, private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    /** Records a restart at [now] (elapsedRealtime) and tells whether it's still allowed. */
    fun allow(now: Long = SystemClock.elapsedRealtime()): Boolean {
        while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
        times.addLast(now)
        return times.size <= max
    }
}
