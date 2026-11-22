package net.prototux.aare.home

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigDecimal

/**
 * The small subset of JSONPath a tile needs to pick one value out of a reply: `$`, `.key`, `[0]`
 * and `['key with dots']`, e.g. `$.state` or `$.attributes.temperature`. The leading `$.` is
 * optional. No filters, wildcards or recursion: a tile shows one value.
 */
object JsonPath {
    /** Tile texts are cut there: a card has room for a short value only. */
    const val MAX_TEXT = 64

    /**
     * The value at [path] in [text], as display text. A blank path shows the payload itself (an
     * MQTT state topic often sends a bare "ON" or "21.5"). Null when the path is malformed, the
     * text isn't JSON or the value isn't there.
     */
    fun extract(text: String, path: String): String? {
        if (path.isBlank()) return text.trim().take(MAX_TEXT)
        val steps = parse(path) ?: return null
        var node: Any? = runCatching { JSONTokener(text).nextValue() }.getOrNull() ?: return null
        for (step in steps) {
            node = when {
                step is String && node is JSONObject -> if (node.has(step)) node.get(step) else return null
                step is Int && node is JSONArray -> if (step in 0 until node.length()) node.get(step) else return null
                else -> return null
            }
        }
        return format(node)?.take(MAX_TEXT)
    }

    /** The steps of [path]: keys (String) and indexes (Int); null if it is malformed. */
    fun parse(path: String): List<Any>? {
        val s = path.trim()
        var i = 0
        if (s.startsWith("$")) i = 1
        val steps = ArrayList<Any>()
        // "state" or "attributes.x": a path without "$" starts with a key.
        if (i == 0 && s.isNotEmpty() && s[0] != '.' && s[0] != '[') {
            val end = keyEnd(s, 0)
            steps += s.substring(0, end)
            i = end
        }
        while (i < s.length) {
            when (s[i]) {
                '.' -> {
                    val end = keyEnd(s, i + 1)
                    if (end == i + 1) return null
                    steps += s.substring(i + 1, end)
                    i = end
                }
                '[' -> {
                    val close = s.indexOf(']', i)
                    if (close < 0) return null
                    val inner = s.substring(i + 1, close).trim()
                    val quoted = inner.length >= 2 && (inner[0] == '\'' || inner[0] == '"') && inner.last() == inner[0]
                    steps += when {
                        quoted -> inner.substring(1, inner.length - 1)
                        else -> inner.toIntOrNull()?.takeIf { it >= 0 } ?: return null
                    }
                    i = close + 1
                }
                else -> return null
            }
        }
        return steps
    }

    private fun keyEnd(s: String, from: Int): Int {
        var j = from
        while (j < s.length && s[j] != '.' && s[j] != '[') j++
        return j
    }

    /** Numbers without a trailing ".0", so 21.0 shows as "21" whichever way the JSON library parsed it. */
    private fun format(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        is String -> v
        is Boolean -> v.toString()
        is Number -> runCatching { BigDecimal(v.toString()).stripTrailingZeros().toPlainString() }.getOrDefault(v.toString())
        else -> v.toString() // an object or array: its compact JSON
    }
}
