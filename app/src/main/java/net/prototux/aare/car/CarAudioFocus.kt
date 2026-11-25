package net.prototux.aare.car

import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import android.util.Log
import net.prototux.aare.DiagLog
import net.prototux.aare.aap.Priority
import net.prototux.aare.audio.CarAudioOut

/**
 * The car's audio focus for AARE's two streams: music, full focus while it plays ([media]), and
 * navigation voice, a transient focus that ducks the car's other sound, then back to the music
 * ([guidance]). Takes the car's answers ([onCarFocus]) and asks again when it gives none. The media
 * pauses the car causes stay with the session.
 *
 * [send] sends a focus request at a send priority (AapSession.requestAudioFocus); [clock] is
 * the [scheduler]'s (uptimeMillis on the main handler, in a session).
 */
internal class CarAudioFocus(
    private val send: (AudioFocusRequestType, Int) -> Unit,
    private val scheduler: Scheduler,
    private val clock: () -> Long,
) {
    /** Runs a task at a time of [clock], or cancels it: the session's main handler. */
    interface Scheduler {
        fun at(timeMs: Long, task: Runnable)
        fun cancel(task: Runnable)
    }

    /** The car lets music play. */
    @Volatile var mediaFocus = false
        private set
    /** The car lets navigation voice play (but not music). */
    @Volatile private var guidanceFocusState = false
    /**
     * The car answered our own guidance request. Some head units (Ford SYNC) answer the transient
     * may-duck request with a plain LOSS: the music lost focus so the prompt can play. Taken as
     * a grant for the guidance channel, or the prompt waits for a GAIN that never comes.
     */
    @Volatile private var guidanceAnswered = false
    @Volatile private var mediaPlaying = false
    @Volatile private var guidancePlaying = false
    @Volatile private var closed = false

    /**
     * Without an answer, ask again: once the same way, like Android Auto (which asks again after
     * 500 ms, up to 3 times), then releasing first. Some head units (a Jancar one) drop the answer
     * to a request made before their session is ready, and never answer a request that doesn't
     * change their state.
     */
    private val mediaRetry = FocusRetry()
    // Guards mediaRetry: requests come from the capture thread, answers from the AAP reader, checks on main.
    private val lock = Any()
    private val check = Runnable { checkMedia() }

    /** Music: full focus while it plays. */
    val media = object : CarAudioOut.Focus {
        override fun request(playing: Boolean) {
            mediaPlaying = playing
            synchronized(lock) {
                if (playing) mediaRetry.onRequested(clock()) else mediaRetry.onWithdrawn()
                scheduleCheck()
                if (playing) send(AudioFocusRequestType.AUDIO_FOCUS_GAIN, Priority.CONTROL)
                // Comes right after the stream's STOP: keep it behind STOP and the last data.
                else if (!guidancePlaying) send(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, Priority.AUDIO)
            }
        }
        override val granted: Boolean get() = mediaFocus
    }

    /** Navigation voice: transient focus that ducks the car's other sound, then back to the music if it plays. */
    val guidance = object : CarAudioOut.Focus {
        override fun request(playing: Boolean) {
            guidanceAnswered = false
            guidancePlaying = playing
            send(when {
                playing -> AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK
                mediaPlaying -> AudioFocusRequestType.AUDIO_FOCUS_GAIN
                else -> AudioFocusRequestType.AUDIO_FOCUS_RELEASE
            }, if (playing) Priority.CONTROL else Priority.AUDIO)
        }
        override val granted: Boolean get() = mediaFocus || guidanceFocusState || guidanceAnswered
    }

    /**
     * The car's word on focus, an answer or its own decision (radio, a call). [mediaOnGainTransient]:
     * the vehicles.json rule that a GAIN_TRANSIENT means music too.
     */
    fun onCarFocus(notification: AudioFocusNotification, mediaOnGainTransient: Boolean) {
        // Any word from the car ends the asking. Before mediaFocus changes, which a check reads.
        val asked = synchronized(lock) { mediaRetry.onAnswered().also { scheduleCheck() } }
        if (asked != null) DiagLog.add("audio focus: the car answered after $asked asks")
        val state = notification.focusState
        val answersGuidance = !notification.unsolicited && guidancePlaying
        if (answersGuidance) {
            // A call or the car's own source taking over arrives as LOSS_TRANSIENT: not a grant.
            guidanceAnswered = state != AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT
            DiagLog.add("guidance focus: car answered ${state.name.removePrefix("AUDIO_FOCUS_STATE_")}")
        } else DiagLog.add("audio focus: car ${if (notification.unsolicited) "says" else "answered"} ${state.name.removePrefix("AUDIO_FOCUS_STATE_")}")
        mediaFocus = mediaAllowed(state, gainTransientIsMedia(answersGuidance, quirk = mediaOnGainTransient))
        guidanceFocusState = guidanceAllowed(state)
    }

    /**
     * The phone's call ended: the car took the media focus for it and doesn't hand it back by
     * itself, so it is asked again, releasing first as a retry does.
     */
    fun askAgainAfterCall() = synchronized(lock) {
        DiagLog.add("audio focus: asking again after the phone's call")
        mediaRetry.onRequested(clock())
        scheduleCheck()
        send(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, Priority.AUDIO)
        send(AudioFocusRequestType.AUDIO_FOCUS_GAIN, Priority.AUDIO)
    }

    /** The session ends: no more asking. */
    fun close() {
        closed = true
        scheduler.cancel(check)
    }

    /** With [lock] held. */
    private fun scheduleCheck() {
        scheduler.cancel(check)
        mediaRetry.nextCheckAtMs()?.let { scheduler.at(it, check) }
    }

    private fun checkMedia() {
        if (closed) return
        val action = synchronized(lock) {
            val now = clock()
            // Kept from before: the request changed nothing, so the car may not answer it.
            if (mediaFocus) mediaRetry.onAnswered()
            // Not over a prompt: when it ends, guidance asks for the music's focus again itself.
            val step = if (guidancePlaying) {
                mediaRetry.postpone(now)
                FocusRetry.Action.None
            } else mediaRetry.due(now)
            // Gains at the release's priority: the send queue is ordered by priority, and one at the
            // default could overtake a release still queued.
            when (step) {
                FocusRetry.Action.Ask -> send(AudioFocusRequestType.AUDIO_FOCUS_GAIN, Priority.AUDIO)
                FocusRetry.Action.Rearm -> {
                    send(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, Priority.AUDIO)
                    send(AudioFocusRequestType.AUDIO_FOCUS_GAIN, Priority.AUDIO)
                }
                else -> {}
            }
            scheduleCheck()
            step
        }
        if (action is FocusRetry.Action.GiveUp) {
            val line = "audio focus: the car never answered (asked ${action.asked} times)"
            Log.w(TAG, line)
            DiagLog.add(line)
        }
    }

    companion object {
        private const val TAG = "AareAudioFocus"

        /** Whether the car's focus [state] lets music play. */
        fun mediaAllowed(state: AudioFocusStateType, gainTransientIsMedia: Boolean): Boolean = when (state) {
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN, AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY,
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK -> true
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT -> gainTransientIsMedia
            else -> false
        }

        /**
         * Whether a GAIN_TRANSIENT lets music play. Answering our music's own request, or said by
         * the car on its own, it does, as before. Answering a navigation prompt's request it is for
         * navigation voice: the music waits until the prompt is over, unless a vehicles.json rule
         * ([quirk]) says the head unit means music too.
         */
        fun gainTransientIsMedia(answersGuidance: Boolean, quirk: Boolean): Boolean = quirk || !answersGuidance

        /** Whether the car's focus [state] lets navigation voice play (besides [mediaAllowed]). */
        fun guidanceAllowed(state: AudioFocusStateType): Boolean = when (state) {
            AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY, AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
            AudioFocusStateType.AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK -> true
            else -> false
        }
    }
}
