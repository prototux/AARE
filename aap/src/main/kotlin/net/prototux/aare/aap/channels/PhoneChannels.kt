package net.prototux.aare.aap.channels

import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.BatteryStatusNotificationOuterClass.BatteryStatusNotification
import aap_protobuf.service.phonestatus.message.PhoneStatusInputOuterClass.PhoneStatusInput
import aap_protobuf.service.phonestatus.message.PhoneStatusOuterClass.PhoneStatus
import net.prototux.aare.aap.AapMessage
import net.prototux.aare.aap.AapSession
import net.prototux.aare.aap.ControlMsg
import net.prototux.aare.aap.PhoneStatusMsg
import net.prototux.aare.aap.Priority
import net.prototux.aare.aap.ServiceChannel

/**
 * Calls and signal strength for head units that draw their own phone screen or show calls in the
 * instrument cluster. The call audio itself stays on Bluetooth hands-free.
 */
class PhoneStatusChannel(service: Service, private val onInput: (PhoneStatusInput) -> Unit = {}) :
    ServiceChannel(service, "phonestatus") {

    override fun onMessage(msg: AapMessage) {
        when (msg.id) {
            // The car's own call buttons (cluster or phone screen): answer, decline, hang up.
            PhoneStatusMsg.INPUT -> runCatching { PhoneStatusInput.parseFrom(msg.bodyBytes()) }.getOrNull()?.let(onInput)
        }
    }

    fun status(s: PhoneStatus) {
        if (opened) send(PhoneStatusMsg.STATUS, s, Priority.INPUT)
    }
}

/** The phone's battery, for the car's status bar (control message 23). */
fun AapSession.sendBatteryStatus(levelPercent: Int, critical: Boolean, timeRemainingS: Int? = null) {
    val b = BatteryStatusNotification.newBuilder().setBatteryLevel(levelPercent.coerceIn(0, 100)).setCriticalBattery(critical)
    timeRemainingS?.let { b.setTimeRemainingS(it) }
    sendControl(ControlMsg.BATTERY_STATUS_NOTIFICATION, b.build())
}
