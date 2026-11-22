package net.prototux.aare.audio

import android.media.MediaRecorder

/** A recording on the phone, as the audio system reports it to the shell (uid -1: unknown). */
data class PhoneRecording(val uid: Int, val source: Int, val silenced: Boolean)

/** Which of the phone's recordings hear the car's microphone instead of the phone's. */
object PhoneAppsMic {
    /**
     * The sources voice typing, voice recorders and voice-message buttons use. Not
     * VOICE_COMMUNICATION: calls and VoIP keep their own route (the car's hands-free link). Not
     * CAMCORDER (a video's sound belongs with the camera) nor hotword detection.
     */
    val SOURCES = setOf(
        MediaRecorder.AudioSource.DEFAULT,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.UNPROCESSED,
    )

    /**
     * [r] is one to give the car's mic: a source in [SOURCES], not by [excludedUids] (AARE, whose
     * car apps get the car's mic their own way, and the shell), and not silenced (an app in the
     * background records nothing anyway). An unknown uid counts as another app's.
     */
    fun takesOver(r: PhoneRecording, excludedUids: Set<Int>): Boolean =
        r.source in SOURCES && r.uid !in excludedUids && !r.silenced

    fun anyTakesOver(recordings: List<PhoneRecording>, excludedUids: Set<Int>): Boolean =
        recordings.any { takesOver(it, excludedUids) }
}

/**
 * When the car's microphone opens and closes for the phone's apps: it opens as soon as one of them
 * records, and closes once none has for [holdMs]. Moving a recording to the car's mic stops and
 * restarts it for a moment, which must not close the mic again. A car that refused its mic isn't
 * asked again until the recordings stop and a new one starts. Not thread-safe: one thread drives it.
 */
class MicTakeover(private val holdMs: Long = 1_500) {
    enum class Action { NONE, OPEN, CLOSE }

    /** The car's mic is asked for (or open) on behalf of the phone's apps. */
    var open = false
        private set
    private var refused = false
    /** When the last recording stopped while open, or -1. */
    private var idleSince = -1L

    /** A recording to take over is going on ([recording]) or not, at [now] (ms). */
    fun onRecording(recording: Boolean, now: Long): Action {
        if (recording) {
            idleSince = -1
            if (open || refused) return Action.NONE
            open = true
            return Action.OPEN
        }
        refused = false
        if (open && idleSince < 0) idleSince = now
        return Action.NONE
    }

    /** When [onTick] should run next, or -1 if nothing is pending. */
    fun nextTick(): Long = if (open && idleSince >= 0) idleSince + holdMs else -1

    fun onTick(now: Long): Action {
        if (!open || idleSince < 0 || now - idleSince < holdMs) return Action.NONE
        open = false
        idleSince = -1
        return Action.CLOSE
    }

    /** The car refused its mic: it counts as closed, and isn't asked again for this recording. */
    fun onRefused() {
        open = false
        refused = true
        idleSince = -1
    }

    /** The feature stops (session over, setting off): CLOSE if the mic was open. */
    fun stop(): Action {
        val was = open
        open = false
        refused = false
        idleSince = -1
        return if (was) Action.CLOSE else Action.NONE
    }
}
