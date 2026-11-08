package net.prototux.aare.transport.wireless

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import net.prototux.aare.aap.AapLog

/**
 * Notices a wireless head unit coming into Bluetooth range: when a bonded device connects (the
 * car's hands-free link usually comes up on its own), calls [onHeadUnit] if it publishes the
 * Android Auto wireless service. Starting the session is the caller's decision.
 *
 * Devices whose services are unknown, or that did not list Android Auto when they were paired,
 * get one SDP query per process so a head unit that enabled wireless later is still found.
 *
 * Use [register]/[unregister] from a running (foreground) service, or declare a manifest receiver
 * for [ACTIONS] and call [headUnitIn] from it: ACL_CONNECTED and ACTION_UUID are among the
 * implicit broadcasts still delivered to manifest receivers.
 */
@SuppressLint("MissingPermission") // register() checks BLUETOOTH_CONNECT
class WirelessAutoStarter(
    context: Context,
    private val log: AapLog,
    /** Which devices count as head units; e.g. narrow it to the ones the user enabled. */
    private val isHeadUnit: (BluetoothDevice) -> Boolean = WirelessConnector::isHeadUnit,
    private val onHeadUnit: (BluetoothDevice) -> Unit,
) {
    private val context = context.applicationContext
    private val sdpQueried = HashSet<String>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = deviceOf(intent) ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onAclConnected(device)
                BluetoothDevice.ACTION_UUID -> if (device.address in sdpQueried && isHeadUnit(device)) {
                    log.i("wireless: ${device.address} offers Android Auto wireless (SDP)")
                    onHeadUnit(device)
                }
            }
        }
    }

    /** Starts listening. Throws [WirelessPermissionException] without BLUETOOTH_CONNECT. */
    fun register() {
        if (registered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) throw WirelessPermissionException(listOf(Manifest.permission.BLUETOOTH_CONNECT))
        val filter = IntentFilter().apply { ACTIONS.forEach(::addAction) }
        // Protected system broadcasts sent by the Bluetooth stack, which is not our uid: exported.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun onAclConnected(device: BluetoothDevice) {
        if (device.bondState != BluetoothDevice.BOND_BONDED) return
        when {
            isHeadUnit(device) -> {
                log.i("wireless: head unit ${device.address} connected")
                onHeadUnit(device)
            }
            // Phones cache SDP results from pairing time; refresh once, the answer is ACTION_UUID.
            sdpQueried.add(device.address) -> device.fetchUuidsWithSdp()
        }
    }

    companion object {
        val ACTIONS = listOf(BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_UUID)

        /**
         * For a manifest-declared receiver: the head unit [intent] (one of [ACTIONS]) is about, or
         * null. Uses the services Android has cached, or those carried by ACTION_UUID.
         */
        @SuppressLint("MissingPermission")
        fun headUnitIn(intent: Intent): BluetoothDevice? {
            val device = deviceOf(intent) ?: return null
            return when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    device.takeIf { it.bondState == BluetoothDevice.BOND_BONDED && WirelessConnector.isHeadUnit(it) }
                BluetoothDevice.ACTION_UUID -> {
                    val uuids = IntentCompat.getParcelableArrayExtra(intent, BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                        ?.filterIsInstance<ParcelUuid>()?.toTypedArray()
                    // Any app's SDP query broadcasts this, for any nearby device. BONDING, not only BONDED: on
                    // first pairing the UUIDs arrive before Android reports the bond, and nothing else fires then.
                    device.takeIf {
                        it.bondState != BluetoothDevice.BOND_NONE &&
                            (WirelessConnector.hasAaUuid(uuids) || WirelessConnector.isHeadUnit(it))
                    }
                }
                else -> null
            }
        }

        private fun deviceOf(intent: Intent): BluetoothDevice? =
            IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    }
}
