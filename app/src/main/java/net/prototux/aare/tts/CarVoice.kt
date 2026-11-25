package net.prototux.aare.tts

import net.prototux.aare.aap.pcm.PacedPcmQueue
import net.prototux.aare.aap.pcm.PcmFormat
import net.prototux.aare.audio.PcmSource

/**
 * Where AARE's voice engine ([CarTtsService]) sends speech while a car is connected: the car's
 * navigation guidance channel.
 *
 * The car session opens a [Source] for that channel; while it is open, the engine pushes
 * synthesized prompts into it instead of playing them on the phone. When no car is connected (or
 * the car has no guidance channel) there's no source, and the engine plays prompts on the phone as
 * any engine would.
 */
object CarVoice {
    @Volatile private var queue: PacedPcmQueue? = null

    /** The car's guidance queue while a car is connected, else null. */
    val current: PacedPcmQueue? get() = queue

    /** The car session's side: a paced stream for the guidance channel, in that channel's format. */
    class Source(private val format: PcmFormat) : PcmSource {
        private var q: PacedPcmQueue? = null

        override fun start(): Boolean {
            val created = PacedPcmQueue(format)
            q = created
            queue?.close()
            queue = created
            return true
        }

        override fun read(buf: ByteArray, offset: Int, length: Int): Int = q?.read(buf, offset, length) ?: -1

        override fun release() {
            val mine = q ?: return
            q = null
            mine.close()
            // Only clear what is still ours: a newer session may have replaced it.
            if (queue === mine) queue = null
        }
    }
}
