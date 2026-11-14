package net.prototux.aare.ui.car

/** A card the dashboard can show. */
enum class DashCard(
    /** Stored in the settings: never change it once released. */
    val id: String,
) {
    MAP("map"),
    MEDIA("media"),
    CALENDAR("calendar"),
    NOTIFICATION("notification"),
    WEATHER("weather");

    companion object {
        /** The cards of a saved list of ids, in order: unknown ids dropped, each card once. */
        fun parse(ids: List<String>): List<DashCard> = ids.mapNotNull { id -> entries.firstOrNull { it.id == id } }.distinct()

        /** Before the dashboard was configurable: the map, the music, the next event, else the latest notification. */
        val DEFAULT = listOf(MAP, MEDIA, CALENDAR, NOTIFICATION)
    }
}

/**
 * The cards to draw, in slot order: the user's [order], skipping those with nothing to show now
 * ([shown]: no calendar event, no notification…), at most [slots]. The first slot is the big one.
 * The map if nothing is left, so the dashboard is never empty.
 */
internal fun dashboardSlots(order: List<DashCard>, slots: Int, shown: (DashCard) -> Boolean): List<DashCard> =
    order.filter(shown).take(slots).ifEmpty { listOf(DashCard.MAP) }

/** This order with [card] shown (added at the end) or hidden. The last card stays: an empty dashboard is no use. */
internal fun List<DashCard>.withCard(card: DashCard, shown: Boolean): List<DashCard> = when {
    shown -> if (card in this) this else this + card
    size <= 1 -> this
    else -> this - card
}

/** This order with the card at [index] moved [by] places (clamped to the list). */
internal fun List<DashCard>.moved(index: Int, by: Int): List<DashCard> {
    if (index !in indices) return this
    val list = toMutableList()
    list.add((index + by).coerceIn(0, lastIndex), list.removeAt(index))
    return list
}
