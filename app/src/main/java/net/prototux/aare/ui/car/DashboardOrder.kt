package net.prototux.aare.ui.car

/** The dashboard cards in display order: the map first, unless [mediaFirst] is set. */
internal fun <T> dashboardOrder(mediaFirst: Boolean, map: T, media: T): List<T> =
    if (mediaFirst) listOf(media, map) else listOf(map, media)
