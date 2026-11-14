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
}
