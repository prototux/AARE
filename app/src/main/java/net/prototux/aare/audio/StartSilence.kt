package net.prototux.aare.audio

import kotlin.math.floor

/**
 * Silence sent ahead of a car audio stream: when a stream starts, it
 * first sends a few silent audio messages, so the head unit's buffer has some sound in it before
 * the first late message can starve it. Over USB it sends none; over Wi-Fi 4 for media and 4 for
 * navigation voice, none for media from head units on protocol 5 and later, and a few head units
 * get another number (built-in vehicles.json).
 */
object StartSilence {
    const val WIFI = 4
    /** Media to a head unit from protocol 5.0 on. */
    const val WIFI_MEDIA_V5 = 0

    /** How many silent messages a stream starts with, before [cap]. vehicles.json's numbers are for Wi-Fi only. */
    fun configured(wireless: Boolean, guidance: Boolean, protocolMajor: Int?, mediaOverride: Int?, guidanceOverride: Int?): Int = when {
        !wireless -> 0
        guidance -> guidanceOverride ?: WIFI
        else -> mediaOverride ?: if ((protocolMajor ?: 0) >= 5) WIFI_MEDIA_V5 else WIFI
    }.coerceAtLeast(0)

    /**
     * Never more silence than the stream was quiet for: [idleMs] since it last sent anything, at
     * [messagesPerSecond] (one message is 2048 samples at 48 kHz, 23.4 a second; 1024 at 16 kHz,
     * 15.6), rounded down with half a message to spare.
     */
    fun cap(configured: Int, idleMs: Long, messagesPerSecond: Double): Int =
        minOf(configured.toLong(), floor(idleMs / 1000.0 * messagesPerSecond - 0.5).toLong()).coerceAtLeast(0).toInt()
}
