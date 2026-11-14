package net.prototux.aare.host.templates

import android.os.SystemClock
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis

/** How long the default action (Action.FLAG_DEFAULT) of a row of actions counts down. */
internal const val DEFAULT_ACTION_COUNTDOWN_MS = 10_000L

/** Marks a title variant that carries an action's countdown instead of text to show. */
private const val TIMED_ACTION_PARAMS = "TimedActionParams"

/** The one action of a row that counts down and then clicks itself: its index, and for how long. */
internal data class RowCountdown(val index: Int, val millis: Long)

/**
 * Which action of a row counts down, if any. The default action (FLAG_DEFAULT) does, for
 * [DEFAULT_ACTION_COUNTDOWN_MS]. In a row without one, an app allowed its own countdowns
 * ([timedParams]) can give one to any action through a title variant (see [timedActionMillis]).
 * Only the first such action of a row counts down.
 */
internal fun rowCountdown(actions: List<Action>, timedParams: Boolean): RowCountdown? =
    rowCountdown(actions.map { (it.flags and Action.FLAG_DEFAULT) != 0 }, timedParams) { timedActionMillis(actions[it].title) }

/** [rowCountdown] over which actions are the default one and each action's own countdown ([ownMillis] by index). */
internal fun rowCountdown(isDefault: List<Boolean>, timedParams: Boolean, ownMillis: (Int) -> Long?): RowCountdown? {
    val default = isDefault.indexOf(true)
    if (default >= 0) return RowCountdown(default, DEFAULT_ACTION_COUNTDOWN_MS)
    if (!timedParams) return null
    for (i in isDefault.indices) ownMillis(i)?.let { return RowCountdown(i, it) }
    return null
}

/** An action's own countdown, from its title's first variant ([timedActionParamsMillis]). */
internal fun timedActionMillis(title: CarText?): Long? =
    title?.variants?.firstOrNull()?.let { timedActionParamsMillis(it.toString()) }

/**
 * Countdown parameters in a title variant: "TimedActionParams|duration:N" counts down N seconds
 * (other comma-separated "key:value" pairs are ignored, and a missing, unreadable or non-positive
 * duration is 10 seconds). Null when [spec] isn't such a variant.
 */
internal fun timedActionParamsMillis(spec: String): Long? {
    if (!isTimedActionParams(spec)) return null
    var seconds = 10
    spec.split('|').getOrNull(1)?.split(',')?.forEach { pair ->
        val kv = pair.split(':')
        if (kv[0].trim() == "duration") kv.getOrNull(1)?.trim()?.lowercase()?.toIntOrNull()?.takeIf { it > 0 }?.let { seconds = it }
    }
    return seconds * 1000L
}

/** A title variant that holds countdown parameters: never shown. */
internal fun isTimedActionParams(variant: CharSequence): Boolean =
    variant.toString().substringBefore('|').contains(TIMED_ACTION_PARAMS)

/**
 * Runs an action's countdown and returns how far it got (0 to 1); at the end [fire] clicks it.
 * The countdown belongs to [key] (what the action shows): a template refresh with the same
 * action carries on where it was instead of starting over. Time-based rather than an animation,
 * so a phone with animations turned off doesn't click the action at once.
 */
@Composable
internal fun actionCountdown(key: Any, millis: Long, fire: () -> Unit): () -> Float {
    val started = remember(key, millis) { SystemClock.elapsedRealtime() }
    var progress by remember(key, millis) { mutableFloatStateOf(0f) }
    val currentFire by rememberUpdatedState(fire)
    LaunchedEffect(key, millis) {
        val end = started + millis
        while (true) {
            val left = end - SystemClock.elapsedRealtime()
            if (left <= 0) break
            progress = 1f - left.toFloat() / millis
            withFrameMillis { }
        }
        progress = 1f
        currentFire()
    }
    return { progress }
}
