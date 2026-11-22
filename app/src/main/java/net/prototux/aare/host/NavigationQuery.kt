package net.prototux.aare.host

/** A coordinate pair as the geo: URI's q= takes it: "lat,lng", e.g. "48.8584,2.2945". */
private val COORDINATES = Regex("""^\s*-?\d{1,2}(\.\d+)?\s*,\s*-?\d{1,3}(\.\d+)?\s*$""")

/** A comma with a digit on each side, spaces around it allowed: "6-8, 1017 SG". */
private val COMMA_BETWEEN_NUMBERS = Regex("""(\d)\s*,\s*(\d)""")

/**
 * The search text of a `geo:0,0?q=` navigation intent for [place] (an address from a calendar
 * event, a contact or a spoken destination).
 *
 * The geo: URI scheme reads a q= that starts with "number,number" as coordinates, and some map
 * apps look for that pair anywhere in the text: an address with a comma between the house number
 * and the postcode ("Weteringschans 6-8, 1017 SG Amsterdam") then points at an impossible place.
 * That comma becomes a space, which no map app's address search needs; a real coordinate pair is
 * left as it is.
 */
fun navigationQuery(place: String): String {
    val text = place.trim()
    if (COORDINATES.matches(text)) return text
    return COMMA_BETWEEN_NUMBERS.replace(text, "$1 $2")
}

/**
 * The geo: URI that searches for [place]: a hosted navigation app's ACTION_NAVIGATE, or the
 * ACTION_VIEW a phone navigation app on the car screen gets.
 */
fun geoQueryUri(place: String): String = "geo:0,0?q=" + encodeUriComponent(navigationQuery(place))

/**
 * Percent-encodes [text] the way android.net.Uri.encode does (letters, digits and -_.!~*'() stay,
 * the rest becomes UTF-8 %XX), so the URI is the same in JVM tests, where Uri is a stub.
 */
internal fun encodeUriComponent(text: String): String {
    val out = StringBuilder(text.length)
    for (b in text.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.!~*'()")) out.append(ch)
        else out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
