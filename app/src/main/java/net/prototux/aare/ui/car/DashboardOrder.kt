package net.prototux.aare.ui.car

/** A place in the dashboard's order: one of AARE's cards ([DashCard]) or one of the user's own ([FaceCard]). */
sealed interface DashItem {
    /** Stored in the settings: never change it once released. */
    val id: String
}

/** One of the user's own cards, drawn from its file (`FaceStore`). */
data class FaceCard(val faceId: String) : DashItem {
    override val id: String get() = PREFIX + faceId

    companion object {
        const val PREFIX = "face:"
    }
}

/** A card of AARE's the dashboard can show. */
enum class DashCard(override val id: String) : DashItem {
    MAP("map"),
    MEDIA("media"),
    CALENDAR("calendar"),
    NOTIFICATION("notification"),
    WEATHER("weather"),
    /** The time, in the design picked on the phone. */
    CLOCK("clock"),
    /** Smart-home values and buttons; shown only once some are set up. */
    HOME("home"),

    /** The car from the OBD2 adapter (the Cockpit app): only with that setting on, and while it reads. */
    CAR("car");

    companion object {
        /** The cards of a saved list of ids, in order: unknown ids dropped, each card once. */
        fun parse(ids: List<String>): List<DashItem> = ids.mapNotNull { id ->
            if (id.startsWith(FaceCard.PREFIX)) FaceCard(id.removePrefix(FaceCard.PREFIX)) else entries.firstOrNull { it.id == id }
        }.distinct()

        /** Before the dashboard was configurable: the map, the music, the next event, else the latest notification. */
        val DEFAULT = listOf(MAP, MEDIA, CALENDAR, NOTIFICATION)
    }
}

/**
 * The cards to draw, in slot order: the user's [order], skipping those with nothing to show now
 * ([shown]: no calendar event, no notification…), at most [slots]. The first slot is the big one.
 * The map if nothing is left, so the dashboard is never empty.
 */
internal fun dashboardSlots(order: List<DashItem>, slots: Int, shown: (DashItem) -> Boolean): List<DashItem> =
    order.filter(shown).take(slots).ifEmpty { listOf(DashCard.MAP) }

/**
 * The cards to draw, in drawing order, and the index in [cards] of the one that gets the big size.
 * [bigFirst]: the big card comes before the others in the user's order, so in the default row its slot is drawn first.
 */
internal data class DashSlots(val cards: List<DashItem>, val big: Int, val bigFirst: Boolean)

/** 0 for the big card, then 1, 2… for the others in their order: the position in the size list. */
internal fun DashSlots.rank(i: Int): Int = when {
    i == big -> 0
    i < big -> i + 1
    else -> i
}

/**
 * [dashboardSlots] and which card is big: the first, or with [mapBig] the map wherever it is in [order].
 * The map then takes a slot even past the [slots] limit; the other cards fill the rest, in order.
 * Its side follows [order], not the cards drawn: [DashSlots.bigFirst] only when the map is first in [order].
 */
internal fun dashboardLayout(order: List<DashItem>, slots: Int, mapBig: Boolean, shown: (DashItem) -> Boolean): DashSlots {
    if (!mapBig || DashCard.MAP !in order) return DashSlots(dashboardSlots(order, slots, shown), 0, bigFirst = true)
    val others = order.filter { it != DashCard.MAP && shown(it) }.take(slots - 1).toSet()
    val cards = order.filter { it == DashCard.MAP || it in others }
    return DashSlots(cards, cards.indexOf(DashCard.MAP), bigFirst = order.first() == DashCard.MAP)
}

/**
 * The cards sharing the last slot of [layout], to swipe through: the one that has the slot, then
 * those of [order] the screen had no room for. The map never shares (its surface would be torn down
 * and rebuilt on every change): when it is last, the slot before it is the shared one. Empty when
 * the map is the only card.
 */
internal fun dashboardStack(order: List<DashItem>, layout: DashSlots, shown: (DashItem) -> Boolean): List<DashItem> {
    val holder = layout.cards.lastOrNull { it != DashCard.MAP } ?: return emptyList()
    return listOf(holder) + order.filter { it != DashCard.MAP && it !in layout.cards && shown(it) }
}

/** The card of [turning] that comes after [current] when the cards change by themselves, round and round; null to stay. */
internal fun nextInTurn(turning: List<DashItem>, current: DashItem): DashItem? {
    val i = turning.indexOf(current)
    return if (i < 0 || turning.size < 2) null else turning[(i + 1) % turning.size]
}

/** What a card's place in the order means, for the settings' list. */
internal enum class DashZone { BIG, FIXED, TURNING }

/**
 * Each card of [order]'s zone on the usual screen: the big one, the one that always shows beside it,
 * and those that take turns in the last place.
 */
internal fun dashboardZones(order: List<DashItem>, mapBig: Boolean): Map<DashItem, DashZone> {
    val layout = dashboardLayout(order, 3, mapBig) { true }
    val stack = if (layout.cards.size > 2) dashboardStack(order, layout) { true } else emptyList()
    return order.associateWith { card ->
        when {
            card == layout.cards[layout.big] -> DashZone.BIG
            card in stack -> DashZone.TURNING
            else -> DashZone.FIXED
        }
    }
}

/** The calendar card's three states, in the order a tap on its box goes through them. */
enum class CalendarCardMode { OFF, ALWAYS, AUTO }

/** What a tap on the calendar's box does; [canHide] false (the last card shown) skips [CalendarCardMode.OFF]. */
internal fun CalendarCardMode.next(canHide: Boolean): CalendarCardMode = when (this) {
    CalendarCardMode.OFF -> CalendarCardMode.ALWAYS
    CalendarCardMode.ALWAYS -> CalendarCardMode.AUTO
    CalendarCardMode.AUTO -> if (canHide) CalendarCardMode.OFF else CalendarCardMode.ALWAYS
}

/** This order with [card] shown (added at the end) or hidden. The last card stays: an empty dashboard is no use. */
internal fun List<DashItem>.withCard(card: DashItem, shown: Boolean): List<DashItem> = when {
    shown -> if (card in this) this else this + card
    size <= 1 -> this
    else -> this - card
}

/**
 * This list with the item at [index] moved [by] places (clamped to the list); out of range, the
 * list unchanged. Generic on purpose: every user-ordered list (the dashboard's cards, the rail's
 * apps, and any order added later) moves its items with this one helper.
 */
internal fun <T> List<T>.movedBy(index: Int, by: Int): List<T> {
    if (index !in indices) return this
    val list = toMutableList()
    list.add((index + by).coerceIn(0, lastIndex), list.removeAt(index))
    return list
}

/**
 * This saved order with the item at [index] of the [shown] ones moved [by] places. Items not shown
 * now (hidden, uninstalled) keep their slots, so one coming back is where it was: the shown ones
 * fill their old slots in the new order, new ones go at the end.
 */
internal fun <T> List<T>.movedAmong(shown: List<T>, index: Int, by: Int): List<T> {
    val queue = ArrayDeque(shown.movedBy(index, by))
    val set = shown.toSet()
    return mapNotNull { if (it in set) queue.removeFirstOrNull() else it } + queue
}
