package net.prototux.aare.media

import android.os.Bundle

/**
 * The now-playing transport row: the app's custom actions on the [left] and [right], and in the
 * skip buttons' slots when those give theirs up ([previous]/[next]; null: the skip button).
 */
internal data class TransportRow<T>(val left: List<T>, val previous: T?, val next: T?, val right: List<T>)

/**
 * Lays out the transport row: a skip button the app can't use right
 * now hands its slot to the app's next custom action, unless the app reserved the slot (see
 * [reservesSkipSlots]). [slots] more actions fit beside the buttons, split between both sides.
 */
internal fun <T : Any> transportRow(
    actions: List<T>,
    slots: Int,
    canPrevious: Boolean,
    canNext: Boolean,
    reservePrevious: Boolean,
    reserveNext: Boolean,
): TransportRow<T> {
    val queue = ArrayDeque(actions)
    val previous = if (canPrevious || reservePrevious) null else queue.removeFirstOrNull()
    val next = if (canNext || reserveNext) null else queue.removeFirstOrNull()
    val rest = queue.take(slots.coerceAtLeast(0))
    val left = rest.take((rest.size + 1) / 2)
    return TransportRow(left, previous, next, rest.drop(left.size))
}

/**
 * The session extras' reserved skip slots (previous, next): the androidx keys and the older
 * Google ones, either counts.
 */
internal fun reservesSkipSlots(extras: Bundle?): Pair<Boolean, Boolean> {
    if (extras == null) return false to false
    fun any(vararg keys: String) = keys.any { extras.getBoolean(it, false) }
    return any(MediaKeys.RESERVE_PREVIOUS, MediaKeys.RESERVE_PREVIOUS_LEGACY) to any(MediaKeys.RESERVE_NEXT, MediaKeys.RESERVE_NEXT_LEGACY)
}
