package net.prototux.aare.host

import android.content.Intent
import android.location.Location
import androidx.car.app.model.Alert
import androidx.car.app.navigation.model.Trip

/**
 * What hosted car apps see of the car: provided by the car runtime to every [CarAppConnection].
 * Called from the main thread unless noted.
 */
interface HostEnvironment {
    /** Car moving (driving restrictions apply). */
    val driving: Boolean

    val night: Boolean

    /** The area apps draw in, in dp, and its density. */
    val screenWidthDp: Int
    val screenHeightDp: Int
    val densityDpi: Int

    /** Content limits per ConstraintManager content type. */
    fun contentLimit(type: Int): Int

    fun toast(app: CarAppConnection, text: CharSequence, long: Boolean)

    /** An app asked to start another car app (ACTION_NAVIGATE, a component, a geo: URI...). */
    fun startCarApp(from: CarAppConnection, intent: Intent)

    /** The app asked to be closed (CarContext.finishCarApp). */
    fun finish(app: CarAppConnection)

    fun navigationStarted(app: CarAppConnection) {}
    fun navigationEnded(app: CarAppConnection) {}
    fun tripUpdated(app: CarAppConnection, trip: Trip) {}

    /** The app showed an alert, or its alert went away ([alert] null). */
    fun alertChanged(app: CarAppConnection, alert: Alert?) {}

    /**
     * Opens the car microphone for an app. PCM 16 kHz mono 16-bit little endian is written to
     * [sink] (from any thread) until [MicHandle.close]. Returns null if no microphone is available.
     */
    fun openMicrophone(app: CarAppConnection, sink: (ByteArray, Int, Int) -> Unit, onStopped: () -> Unit): MicHandle?

    /** Latest location from the car's GPS, if it has one. Any thread. */
    val carLocation: Location?

    /** Latest heading from the car's compass (degrees), if any. Any thread. */
    val carHeading: Float?
}

fun interface MicHandle {
    fun close()
}
