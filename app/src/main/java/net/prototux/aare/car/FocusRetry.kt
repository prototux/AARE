package net.prototux.aare.car

/**
 * When to ask the car again for an audio focus it didn't answer: [firstWaitMs] after the request,
 * then every [retryEveryMs], at most [maxRetries] times. Any answer, or the request being
 * withdrawn, stops it. Not thread-safe; times are from one monotonic clock.
 */
class FocusRetry(private val firstWaitMs: Long = 1_000, private val retryEveryMs: Long = 2_000, private val maxRetries: Int = 15) {
    sealed interface Action {
        data object None : Action
        /** Ask again, the same way. */
        data object Ask : Action
        /** Release, then ask again. */
        data object Rearm : Action
        /** No answer after [asked] requests: stop asking. */
        data class GiveUp(val asked: Int) : Action
    }

    private var nextAtMs: Long? = null
    private var retries = 0

    fun onRequested(nowMs: Long) {
        retries = 0
        nextAtMs = nowMs + firstWaitMs
    }

    /** Stops asking. Returns how many requests it took, when it took more than one. */
    fun onAnswered(): Int? {
        val asked = if (nextAtMs != null && retries > 0) retries + 1 else null
        nextAtMs = null
        return asked
    }

    fun onWithdrawn() { nextAtMs = null }

    /** Not now: check again in [retryEveryMs], without counting it as a retry. */
    fun postpone(nowMs: Long) {
        if (nextAtMs != null) nextAtMs = nowMs + retryEveryMs
    }

    fun due(nowMs: Long): Action {
        val at = nextAtMs ?: return Action.None
        if (nowMs < at) return Action.None
        if (retries >= maxRetries) {
            nextAtMs = null
            return Action.GiveUp(asked = retries + 1)
        }
        retries++
        nextAtMs = nowMs + retryEveryMs
        return if (retries == 1) Action.Ask else Action.Rearm
    }

    /** When [due] can next have something to do, or null when there's nothing to wait for. */
    fun nextCheckAtMs(): Long? = nextAtMs
}
