package net.prototux.aare.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import androidx.core.content.IntentCompat
import net.prototux.aare.DiagAapLog
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import net.prototux.aare.transport.wireless.WirelessAutoStarter
import net.prototux.aare.transport.wireless.WirelessConnector

/**
 * A paired head unit offering wireless Android Auto just connected over Bluetooth (or its
 * services were refreshed): start the projection service, which runs the Wi-Fi bootstrap.
 *
 * Both `ACL_CONNECTED` and `ACTION_UUID` are delivered to this manifest receiver, so SDP
 * refreshes triggered on `ACL_CONNECTED` are answered by a later `ACTION_UUID` broadcast
 * without registering a duplicate dynamic receiver. Non-AA devices (like a car's native
 * HFP/A2DP Bluetooth) are throttled via [WirelessAutoStarter.querySdpIfNeeded] so their
 * connection doesn't collide with a wireless dongle's bootstrap.
 */
class WirelessReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (!Settings(context).wireless) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val label = WirelessAutoStarter.deviceLabel(device)
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val cached = runCatching { device.uuids }.getOrNull()
                val uuidsText = WirelessAutoStarter.formatUuids(cached)
                if (!bonded(device)) {
                    DiagLog.add("wireless: ACL_CONNECTED $label bonded=false uuids=$uuidsText sdp=no -> ignored")
                    return
                }
                val headUnit = WirelessAutoStarter.headUnitIn(intent)
                if (headUnit != null) {
                    DiagLog.add("wireless: ACL_CONNECTED $label uuids=$uuidsText sdp=no -> offers Android Auto wireless, connecting")
                    ProjectionService.startWireless(context, headUnit.address)
                } else if (!WirelessAutoStarter.querySdpIfNeeded(context, device, DiagAapLog, "ACL_CONNECTED")) {
                    DiagLog.add("wireless: ACL_CONNECTED $label uuids=$uuidsText sdp=skipped (non-AA cached <24h)")
                }
            }
            BluetoothDevice.ACTION_UUID -> {
                val extra = IntentCompat.getParcelableArrayExtra(intent, BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                    ?.filterIsInstance<ParcelUuid>()?.toTypedArray()
                val uuidsText = WirelessAutoStarter.formatUuids(extra ?: runCatching { device.uuids }.getOrNull())
                val pending = WirelessAutoStarter.isSdpPending(context, device.address)
                WirelessAutoStarter.clearSdpPending(context, device.address)
                val connected = WirelessConnector.isConnected(device)
                val headUnit = WirelessAutoStarter.headUnitIn(intent)
                if (headUnit != null && (connected || pending)) {
                    DiagLog.add("wireless: ACTION_UUID $label acl=$connected pendingSdp=$pending uuids=$uuidsText -> offers Android Auto wireless, connecting")
                    ProjectionService.startWireless(context, headUnit.address)
                } else if (pending || connected) {
                    DiagLog.add("wireless: ACTION_UUID $label acl=$connected pendingSdp=$pending uuids=$uuidsText -> does not offer Android Auto wireless")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bonded(device: BluetoothDevice) = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
}
