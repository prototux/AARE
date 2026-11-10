package net.prototux.aare.car

import net.prototux.aare.media.MediaHub

/**
 * Pauses the current playback when the car session ends while its audio was going to the car,
 * as players do when any audio output disappears; otherwise it would fall back to the phone speaker.
 */
internal fun pauseIfFeedingCar(media: MediaHub, feedingCar: Boolean) {
    if (feedingCar && media.playback.value?.playing == true) media.pause()
}
