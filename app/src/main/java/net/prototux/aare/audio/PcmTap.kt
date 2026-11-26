package net.prototux.aare.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The car's media sound, as it is sent, for the now-playing visualizer.
 *
 * The media [CarAudioOut] says what it streams ([open]/[close]); a visual that wants the sound
 * calls [listen], which makes a [TapRing] the capture thread then copies every chunk the car got
 * into. With nobody listening, the capture thread pays one null check per chunk.
 */
object PcmTap {
    /** A stream's sample rate (Hz) and channel count; 16-bit PCM. */
    data class Format(val rate: Int, val channels: Int)

    /** The format of the media stream now going to the car, or null when there's none. */
    private val _format = MutableStateFlow<Format?>(null)
    val format: StateFlow<Format?> = _format.asStateFlow()
    private var owner: Any? = null

    /** Read by the capture thread for every chunk: null unless someone listens. */
    @Volatile var ring: TapRing? = null
        private set
    private var listeners = 0

    /** [who] (a media CarAudioOut) now streams [format] to the car. */
    @Synchronized fun open(who: Any, format: Format) {
        owner = who
        _format.value = format
        val r = ring
        if (listeners > 0 && (r == null || r.sampleRate != format.rate || r.channels != format.channels)) ring = TapRing(format.rate, format.channels)
        else r?.clear()
    }

    /** [who] stopped; nothing if another stream took over meanwhile. */
    @Synchronized fun close(who: Any) {
        if (owner !== who) return
        owner = null
        _format.value = null
        ring?.clear()
    }

    /**
     * A visual starts showing the sound. Pair with [release]. Read [ring] on every frame: a new
     * stream with another format gets a new one.
     */
    @Synchronized fun listen() {
        listeners++
        val f = _format.value ?: return
        val r = ring
        if (r == null || r.sampleRate != f.rate || r.channels != f.channels) ring = TapRing(f.rate, f.channels)
    }

    @Synchronized fun release() {
        if (listeners > 0) listeners--
        if (listeners == 0) ring = null
    }
}
