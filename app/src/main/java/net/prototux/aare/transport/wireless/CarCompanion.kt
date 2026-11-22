package net.prototux.aare.transport.wireless

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import net.prototux.aare.DiagLog

/**
 * Cars associated with AARE as companion devices (Android 13+): the user picks a car already used
 * wirelessly and confirms Android's dialog. Android then starts [CarPresenceService] when that
 * car's Bluetooth connects, and lets AARE start its foreground service from the background, so
 * the drive starts without a tap. Removing the association undoes both.
 *
 * Every call is a no-op (or empty) where [supported] is false.
 */
object CarCompanion {
    /** One association: the car's Bluetooth [address] and the name Android showed. */
    data class Car(val id: Int, val address: String, val name: String)

    fun supported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    /** AARE's associated cars. */
    fun cars(context: Context): List<Car> {
        if (!supported(context)) return emptyList()
        return runCatching { manager(context).myAssociations.mapNotNull(::car) }.getOrDefault(emptyList())
    }

    /** The Bluetooth (MAC) addresses of [cars], upper case. */
    fun addresses(context: Context): Set<String> = cars(context).mapTo(HashSet()) { it.address }

    /**
     * Asks Android to associate the bonded car at [address]. [onPending] gets the confirmation
     * dialog to launch; [onDone] gets true once associated (and observed), or false with Android's
     * reason. Callbacks on the main thread.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun associate(context: Context, address: String, onPending: (IntentSender) -> Unit, onDone: (Boolean, String?) -> Unit) {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        val app = context.applicationContext
        runCatching {
            manager(context).associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    DiagLog.add("wireless: $address associated as a companion device")
                    observe(app, associationInfo)
                    onDone(true, null)
                }

                override fun onFailure(error: CharSequence?) {
                    DiagLog.add("wireless: associating $address failed: $error")
                    onDone(false, error?.toString())
                }
            })
        }.onFailure { onDone(false, it.message) }
    }

    /** Ends the association with [car]: no more presence callbacks for it. */
    fun remove(context: Context, car: Car) {
        if (!supported(context)) return
        val cdm = manager(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                cdm.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(car.id).build())
            } else {
                @Suppress("DEPRECATION") cdm.stopObservingDevicePresence(car.address)
            }
        }
        runCatching { cdm.disassociate(car.id) }.onFailure { DiagLog.add("wireless: removing the association with ${car.address} failed: ${it.message}") }
        DiagLog.add("wireless: ${car.address} no longer associated")
    }

    /** The car at [associationId], for a presence event that only carries the id. */
    fun address(context: Context, associationId: Int): String? = cars(context).firstOrNull { it.id == associationId }?.address

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun observe(context: Context, info: AssociationInfo) {
        val cdm = manager(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build())
            } else {
                @Suppress("DEPRECATION") cdm.startObservingDevicePresence(info.deviceMacAddress.toString())
            }
        }.onFailure { DiagLog.add("wireless: can't watch for the car's Bluetooth: ${it.message}") }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun car(info: AssociationInfo): Car? {
        val mac = info.deviceMacAddress?.toString()?.uppercase() ?: return null
        return Car(info.id, mac, info.displayName?.toString().orEmpty().ifBlank { mac })
    }

    private fun manager(context: Context): CompanionDeviceManager = context.getSystemService(CompanionDeviceManager::class.java)
}
