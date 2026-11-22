package net.prototux.aare.transport.wireless

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import net.prototux.aare.service.ProjectionService

/**
 * Android binds this when the Bluetooth of a car associated through [CarCompanion] connects:
 * start the wireless connection, as a tap on Connect would. Enabled from Android 13 only
 * (res/values-v33/bools.xml). Android 16 reports presence through [onDevicePresenceEvent]; older
 * versions through [onDeviceAppeared]. A second start for the same car is ignored by the service.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class CarPresenceService : CompanionDeviceService() {
    @Deprecated("Android 16 calls onDevicePresenceEvent instead")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        appeared(associationInfo.deviceMacAddress?.toString())
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BT_CONNECTED) appeared(CarCompanion.address(this, event.associationId))
    }

    private fun appeared(address: String?) {
        if (address == null || !Settings(this).wireless) return
        DiagLog.add("wireless: associated car ${address.uppercase()} is in Bluetooth range, connecting")
        ProjectionService.startWireless(this, address.uppercase())
    }
}
