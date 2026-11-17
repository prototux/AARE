package net.prototux.aare.ui

/** Where an app shows in the car: the rail, the shortcuts, the app overview, or not at all. */
enum class AppPlace { RAIL, SHORTCUTS, OVERVIEW, OFF }

/**
 * The car's apps by place, as the settings keep them: [rail] and [shortcuts] in their order, the
 * overview's [order], the apps switched off ([hidden]), and the apps that show only once picked
 * ([picked]: phone apps, the phone screen, the browser). Each app has one place.
 */
data class AppPlaces(
    val rail: List<String>,
    val shortcuts: List<String>,
    val order: List<String>,
    val hidden: Set<String>,
    val picked: Set<String>,
) {
    /** Where [key] is. [optIn]: it shows only once picked, as a phone app does. */
    fun placeOf(key: String, optIn: Boolean): AppPlace = when {
        key in rail -> AppPlace.RAIL
        key in shortcuts -> AppPlace.SHORTCUTS
        if (optIn) key !in picked else key in hidden -> AppPlace.OFF
        else -> AppPlace.OVERVIEW
    }

    /** [key] moved to [to], at the end of that place's list. */
    fun moved(key: String, to: AppPlace, optIn: Boolean): AppPlaces {
        val off = to == AppPlace.OFF
        return copy(
            rail = (rail - key).let { if (to == AppPlace.RAIL) it + key else it },
            shortcuts = (shortcuts - key).let { if (to == AppPlace.SHORTCUTS) it + key else it },
            order = (order - key).let { if (to == AppPlace.OVERVIEW) it + key else it },
            hidden = if (!optIn && off) hidden + key else hidden - key,
            picked = if (optIn && !off) picked + key else picked - key,
        )
    }
}

/** This list with the item at [index] moved [by] places, kept inside the list. */
fun <T> List<T>.shifted(index: Int, by: Int): List<T> = toMutableList().apply { add((index + by).coerceIn(0, lastIndex), removeAt(index)) }

/**
 * This place's new order: the [shown] apps as dragged, then the ones the page didn't list (phone
 * apps still loading, or with no helper), so a drag never drops them.
 */
fun List<String>.sortedAs(shown: List<String>): List<String> = shown + filter { it !in shown }
