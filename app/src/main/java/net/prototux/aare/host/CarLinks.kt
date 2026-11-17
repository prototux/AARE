package net.prototux.aare.host

/**
 * Links that act on the car, for assistants that can only write text (Claude, ChatGPT): the reply
 * puts `https://aare.internal/go/car_navigate?destination=Berlin` behind an emoji,
 * the voice reading it aloud skips the address, and a tap on the emoji hands it to AARE
 * (CarLinkActivity), which runs the car tool of the same name. The assistant never sees the
 * result, so AARE speaks what the driver asked to hear (messages, status) or a failure itself.
 */
object CarLinks {
    const val HOST = "aare.internal"
    const val PATH = "/go/"

    /**
     * The tool a link to [host] and [path] asks for, with its [query] as arguments, or null for
     * anything else. Only the car's voice assistant ([assistant], see Assistants.authorized) may send
     * one: [caller] is the app that opened the link, as Android reports it.
     */
    internal fun parse(host: String?, path: String, query: Map<String, String>, caller: String?, assistant: String?): Pair<String, Map<String, String>>? {
        if (host != HOST || !path.startsWith(PATH) || caller == null || caller != assistant) return null
        val tool = path.removePrefix(PATH).trimEnd('/')
        return if (tool.startsWith("car_") && '/' !in tool) tool to query else null
    }

    /** A project name is easiest to say, find and type without spaces. */
    fun goodProjectName(name: String): Boolean = name.isNotBlank() && name.none { it.isWhitespace() }

    /**
     * The instructions for the assistant's project, in English for every language: the assistant
     * follows them as well and answers in the language spoken. Each link sits behind an emoji the
     * car screen taps (CAR_LINK_TAP).
     */
    val INSTRUCTIONS = """
        You are my assistant in the car. Answer in one or two short sentences.
        Always answer in the language I speak.
        When I want something done in the car, answer briefly and add a last line with only an
        emoji that links to the car action. Only one such line per answer. Arguments are URL-encoded.

        - Navigate:      [🧭](https://$HOST${PATH}car_navigate?destination=<place>)
        - End the route: [🏁](https://$HOST${PATH}car_stop_navigation)
        - Play:          [▶️](https://$HOST${PATH}car_media?action=play)
        - Pause:         [⏸️](https://$HOST${PATH}car_media?action=pause)
        - Next:          [⏭️](https://$HOST${PATH}car_media?action=next)
        - Previous:      [⏮️](https://$HOST${PATH}car_media?action=previous)
        - Dark:          [🌙](https://$HOST${PATH}car_display?mode=dark)
        - Light:         [☀️](https://$HOST${PATH}car_display?mode=light)
        - Show an app:   [📱](https://$HOST${PATH}car_open?what=<app, home or apps>)
        - Car status:    [ℹ️](https://$HOST${PATH}car_status)
        - Read messages: [📨](https://$HOST${PATH}car_messages)
        - Reply:         [✉️](https://$HOST${PATH}car_reply?conversation=<name>&message=<text>)
        - Mark as read:  [✅](https://$HOST${PATH}car_mark_read?conversation=<name>)

        The car reads status and messages aloud itself; you don't see them, so don't make them up.
        Before a reply, read it back and wait for my yes; only then add the link.
        If a place, app or name is unclear, ask first and add no link.

        Example: "Take me to Berlin main station" →
        Alright, have a good trip!
        [🧭](https://$HOST${PATH}car_navigate?destination=Berlin%20Hauptbahnhof)
    """.trimIndent()
}
