package net.prototux.aare.host

import java.net.URI
import java.net.URLDecoder

/** An `aare://` link an assistant's reply can trigger, once [AssistantLinks.parse] has allowed it. */
internal sealed interface AssistantLink {
    data class Navigate(val query: String) : AssistantLink
    data class Play(val action: String) : AssistantLink
    data class Open(val what: String) : AssistantLink
    data class Call(val number: String) : AssistantLink
}

/**
 * Parses an `aare://` deep link (see docs/assistant-links.md) into one of the allowed
 * [AssistantLink]s, or null for anything else: a different scheme, an unknown host, or a
 * missing/malformed parameter. This is the allowlist itself, so it accepts nothing beyond the
 * four actions below, whatever the link asks for.
 *
 * Pure string parsing, deliberately not `android.net.Uri` (its JVM stub returns nulls in unit
 * tests without Robolectric, which this module doesn't pull in): [java.net.URI] and manual query
 * decoding work the same in a unit test and on a device.
 */
internal object AssistantLinks {
    private val MEDIA_ACTIONS = setOf("play", "pause", "next", "previous")

    fun parse(link: String): AssistantLink? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (!uri.scheme.equals("aare", ignoreCase = true)) return null
        val params = queryParams(uri.rawQuery)
        return when (uri.host?.lowercase()) {
            "navigate" -> text(params, "q")?.let(AssistantLink::Navigate)
            "play" -> when (val action = (params["action"] ?: params["q"])?.trim()?.lowercase()) {
                null -> AssistantLink.Play("play")
                in MEDIA_ACTIONS -> AssistantLink.Play(action)
                else -> null
            }
            "open" -> (text(params, "app") ?: text(params, "q"))?.let(AssistantLink::Open)
            "call" -> number(params["n"])?.let(AssistantLink::Call)
            else -> null
        }
    }

    private fun text(params: Map<String, String>, key: String): String? = params[key]?.trim()?.takeIf { it.isNotEmpty() }

    /** Only ASCII digits and a leading '+', capped like the number CarRuntime accepts for a dialled call. */
    private fun number(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        val digits = trimmed.filter { it in '0'..'9' }.take(20)
        if (digits.isEmpty()) return null
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    private fun queryParams(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split("&").mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val i = pair.indexOf('=')
            val key = if (i < 0) pair else pair.substring(0, i)
            val value = if (i < 0) "" else pair.substring(i + 1)
            runCatching { URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8") }.getOrNull()
        }.toMap()
    }
}
