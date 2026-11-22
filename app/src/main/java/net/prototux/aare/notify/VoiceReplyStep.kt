package net.prototux.aare.notify

/**
 * Where a voice reply is: listening, waiting for the user to confirm what was heard, or failed.
 * Nothing is sent from here: only the Send button sends, from [Confirm].
 */
internal sealed interface VoiceReplyStep {
    /** Listening; [heard] is the recognizer's partial text so far. */
    data class Listening(val heard: String = "") : VoiceReplyStep
    /** [text] was heard: read back, sent only on Send. */
    data class Confirm(val text: String) : VoiceReplyStep
    /** Nothing understood: the user may try again. */
    data object NotHeard : VoiceReplyStep
    /** No speech recognition on this phone (or it broke): only cancelling is offered. */
    data object Unavailable : VoiceReplyStep

    companion object {
        fun partial(step: VoiceReplyStep, text: String): VoiceReplyStep =
            if (step is Listening && text.isNotBlank()) Listening(text.trim()) else step

        /** A final result only counts while listening; blank means nothing was heard. */
        fun result(step: VoiceReplyStep, text: String): VoiceReplyStep = when {
            step !is Listening -> step
            text.isBlank() -> NotHeard
            else -> Confirm(text.trim())
        }

        fun failure(step: VoiceReplyStep, failure: VoiceDictation.Failure): VoiceReplyStep = when {
            step !is Listening -> step
            failure == VoiceDictation.Failure.NotHeard -> NotHeard
            else -> Unavailable
        }

        /** Try again: from anything but [Unavailable], back to listening. */
        fun retry(step: VoiceReplyStep): VoiceReplyStep = if (step == Unavailable) step else Listening()
    }
}
