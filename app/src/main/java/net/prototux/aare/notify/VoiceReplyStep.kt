package net.prototux.aare.notify

/**
 * Where a voice reply is: listening, waiting for the user to confirm what was heard, or failed.
 * Nothing is sent from here: only Send, or a spoken "yes" to the read-back, sends from [Confirm].
 */
internal sealed interface VoiceReplyStep {
    /** Listening; [heard] is the recognizer's partial text so far. */
    data class Listening(val heard: String = "") : VoiceReplyStep
    /** [text] was heard: read back, sent on Send or a spoken "yes". */
    data class Confirm(val text: String) : VoiceReplyStep
    /** Nothing understood: the user may try again. */
    data object NotHeard : VoiceReplyStep
    /** The recognizer didn't answer (weak signal, server, busy, timeout): the user may try again. */
    data object TryAgain : VoiceReplyStep
    /** No speech recognition on this phone (or it broke): only cancelling is offered. */
    data object Unavailable : VoiceReplyStep

    enum class Answer { Yes, No, Unclear }

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
            failure == VoiceDictation.Failure.TryAgain -> TryAgain
            else -> Unavailable
        }

        /**
         * The spoken answer to "…, send it to Anna?": [yesWords] and [noWords] are the language's
         * words (msg_voice_reply_yes_words / _no_words), the English ones always count too. A "no"
         * anywhere wins over a "yes", so "no, don't send it" never sends; anything else is
         * [Answer.Unclear] and leaves the buttons to decide.
         */
        fun answerOf(heard: String, yesWords: List<String>, noWords: List<String>): Answer {
            // Recognizers may write "don’t" with a typographic apostrophe: one word either way.
            val words = heard.lowercase().replace('\u2019', '\'').split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
            val text = " " + words.joinToString(" ") + " "
            fun said(list: List<String>) = list.any { w -> w.isNotBlank() && text.contains(" " + w.trim().lowercase() + " ") }
            return when {
                said(noWords + ENGLISH_NO) -> Answer.No
                said(yesWords + ENGLISH_YES) -> Answer.Yes
                else -> Answer.Unclear
            }
        }

        private val ENGLISH_YES = listOf("yes", "yeah", "yep", "send", "send it", "ok", "okay", "sure")
        private val ENGLISH_NO = listOf("no", "nope", "cancel", "don't", "dont", "stop")

        /** Try again: from anything but [Unavailable], back to listening. */
        fun retry(step: VoiceReplyStep): VoiceReplyStep = if (step == Unavailable) step else Listening()
    }
}
