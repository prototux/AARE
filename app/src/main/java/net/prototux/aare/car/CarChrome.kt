package net.prototux.aare.car

import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiElement
import net.prototux.aare.Settings

/** The status items of the car UI's bar: the clock, the phone's signal and its battery. */
data class CarChrome(val clock: Boolean, val phoneSignal: Boolean, val battery: Boolean) {
    override fun toString() = listOfNotNull("clock".takeIf { clock }, "phone signal".takeIf { phoneSignal },
        "battery level".takeIf { battery }).joinToString().ifEmpty { "none" }

    companion object {
        val ALL = CarChrome(clock = true, phoneSignal = true, battery = true)
        val NONE = CarChrome(clock = false, phoneSignal = false, battery = false)

        /**
         * The items the head unit draws itself. Head units on protocol 4.3 and later list them in
         * the UI config of their video configuration (ui_elements); their session_configuration
         * bits are not used then. Older ones set those bits in their service discovery response:
         * 1 clock, 2 phone signal, 4 battery level.
         */
        fun drawnByHeadUnit(atLeast43: Boolean, sessionBits: Int, uiElements: Collection<UiElement>): CarChrome =
            if (atLeast43) CarChrome(
                clock = UiElement.UI_ELEMENT_CLOCK in uiElements,
                phoneSignal = UiElement.UI_ELEMENT_PHONE_SIGNAL in uiElements,
                battery = UiElement.UI_ELEMENT_BATTERY_LEVEL in uiElements,
            ) else CarChrome(clock = sessionBits and 1 != 0, phoneSignal = sessionBits and 2 != 0, battery = sessionBits and 4 != 0)

        /**
         * The items the car UI shows: by default ([Settings.CHROME_AUTO]) those the head unit
         * doesn't draw itself ([drawnByCar]), so the screen doesn't show two clocks; the setting
         * can also always show or always hide them.
         */
        fun shown(setting: String, drawnByCar: CarChrome): CarChrome = when (setting) {
            Settings.CHROME_SHOW -> ALL
            Settings.CHROME_HIDE -> NONE
            else -> CarChrome(clock = !drawnByCar.clock, phoneSignal = !drawnByCar.phoneSignal, battery = !drawnByCar.battery)
        }
    }
}
