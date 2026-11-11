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
import net.prototux.aare.aap.wireless.WirelessRetryPolicy

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
    private val policy = WirelessRetryPolicy()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = deviceOf(intent) ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onAclConnected(device)
                BluetoothDevice.ACTION_UUID -> if (isSdpPending(context, device.address)) {
                    clearSdpPending(context, device.address)
                    if (isHeadUnit(device)) {
                        log.i("wireless: ${deviceLabel(device)} offers Android Auto wireless (SDP)")
                        onHeadUnit(device)
                    }
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
        if (isHeadUnit(device)) {
            log.i("wireless: head unit ${deviceLabel(device)} connected")
            onHeadUnit(device)
        } else {
            querySdpIfNeeded(context, device, log, "ACL_CONNECTED", policy)
        }
    }

    companion object {
        val ACTIONS = listOf(BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_UUID)
        private const val SDP_PREFS = "wireless_sdp"
        private const val KEY_TS = "ts:"
        private const val KEY_PENDING = "pending:"
        private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

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

        /**
         * Runs `fetchUuidsWithSdp()` only when [policy] allows it (cached UUIDs are null/empty, or
         * this bonded MAC has not been queried in the last ~24 h), and remembers the timestamp in
         * SharedPreferences so the car's own HFP/A2DP Bluetooth isn't queried on every start.
         */
        @SuppressLint("MissingPermission")
        fun querySdpIfNeeded(
            context: Context,
            device: BluetoothDevice,
            log: AapLog,
            trigger: String,
            policy: WirelessRetryPolicy = WirelessRetryPolicy(),
            nowMs: Long = System.currentTimeMillis(),
        ): Boolean {
            val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
            val cached = runCatching { device.uuids }.getOrNull()
            val lastMs = lastSdpQueryMs(context, device.address)
            if (!policy.shouldQuerySdp(
                    bonded = bonded,
                    hasAaUuid = WirelessConnector.hasAaUuid(cached),
                    cachedUuidsEmpty = cached.isNullOrEmpty(),
                    lastQueriedAtMs = lastMs,
                    nowMs = nowMs,
                )
            ) return false
            recordSdpQuery(context, device.address, pending = true, nowMs = nowMs)
            val started = runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
            if (!started) clearSdpPending(context, device.address)
            log.i("wireless: $trigger ${deviceLabel(device)} uuids=${formatUuids(cached)} sdp=${if (started) "requested" else "refused"}")
            return started
        }

        fun lastSdpQueryMs(context: Context, address: String): Long? {
            val prefs = context.applicationContext.getSharedPreferences(SDP_PREFS, Context.MODE_PRIVATE)
            val key = KEY_TS + address.uppercase()
            return if (prefs.contains(key)) prefs.getLong(key, 0L) else null
        }

        fun recordSdpQuery(context: Context, address: String, pending: Boolean, nowMs: Long = System.currentTimeMillis()) {
            val norm = address.uppercase()
            context.applicationContext.getSharedPreferences(SDP_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_TS + norm, nowMs)
                .putBoolean(KEY_PENDING + norm, pending)
                .apply()
        }

        fun isSdpPending(context: Context, address: String): Boolean =
            context.applicationContext.getSharedPreferences(SDP_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PENDING + address.uppercase(), false)

        fun clearSdpPending(context: Context, address: String) {
            context.applicationContext.getSharedPreferences(SDP_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PENDING + address.uppercase(), false)
                .apply()
        }

        @SuppressLint("MissingPermission")
        fun deviceLabel(device: BluetoothDevice): String {
            val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "?"
            return "$name (${device.address})"
        }

        fun formatUuids(uuids: Array<out ParcelUuid>?): String {
            if (uuids.isNullOrEmpty()) return "[]"
            return uuids.joinToString(prefix = "[", postfix = "]") { parcel ->
                val u = parcel.uuid
                val s = u.toString().lowercase()
                when {
                    u == WirelessConnector.AA_UUID -> "AA_WIRELESS"
                    s == "669a0c20-0008-f4bd-e611-cb52007ae14d" -> "AA_WIRELESS_REV"
                    s.startsWith("0000") && s.endsWith(BASE_UUID_SUFFIX) -> "0x" + s.substring(4, 8)
                    else -> s
                }
            }
        }

        private fun deviceOf(intent: Intent): BluetoothDevice? =
            IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    }
}
