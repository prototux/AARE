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
