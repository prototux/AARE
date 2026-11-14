package net.prototux.aare.ui.car

/** The position extrapolated from the last sample while playing, as the full-screen player does. */
internal fun mediaProgress(positionMs: Long, durationMs: Long, playing: Boolean, updatedAtMs: Long, nowMs: Long): Float {
    if (durationMs <= 0) return 0f
    val position = if (playing) positionMs + (nowMs - updatedAtMs).coerceAtLeast(0) else positionMs
    return (position.toFloat() / durationMs).coerceIn(0f, 1f)
}
