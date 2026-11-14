package net.prototux.aare.car

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import aap_protobuf.shared.MessageStatusOuterClass.MessageStatus
import net.prototux.aare.DiagLog
import net.prototux.aare.aap.channels.BluetoothChannel

/**
 * Answers the car's Bluetooth authentication data: Android's pairing
 * request for the car and the car's passkey or PIN are matched up ([PairingCheck]), the car's PIN
 * is entered for it, and the car gets the result.
 *
 * A matching numeric comparison is also confirmed itself, using Android's pairing
 * dialog away; confirming needs BLUETOOTH_PRIVILEGED, which an app can't hold, so AARE leaves
 * the dialog to the user and only tells the car whether the passkeys match. Everything runs on
 * [handler]'s thread.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT: without it no pairing request arrives, and setPin is wrapped
class BluetoothPairing(private val context: Context, private val channel: BluetoothChannel, private val handler: Handler) {
    private var registered = false
    @Volatile private var stopped = false

    private var variant: Int? = null
    private var passkey = Int.MIN_VALUE
    private var authData: String? = null
    private var device: BluetoothDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onPairingRequest(i)
    }

    fun start() {
        // An empty or placeholder car address: no pairing request can be the car's.
        if (!BluetoothAdapter.checkBluetoothAddress(channel.carAddress)) return
        // A protected system broadcast: only the Bluetooth stack can send it.
        runCatching {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST), null, handler, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }.onFailure { Log.w(TAG, "no pairing requests", it) }
    }

    fun stop() {
        stopped = true
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    /** The passkey or PIN the car shows. Any thread. */
    fun onAuthenticationData(data: String) {
        handler.post {
            if (stopped) return@post
            authData = data
            authenticateIfReady()
        }
    }

    private fun onPairingRequest(i: Intent) {
        if (stopped) return
        val d = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        if (!d.address.equals(channel.carAddress, ignoreCase = true)) return
        val v = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, Int.MIN_VALUE)
        if (v == Int.MIN_VALUE) return
        if (v != PairingCheck.VARIANT_PIN && v != PairingCheck.VARIANT_PASSKEY_CONFIRMATION) {
            // Consent and the like: nothing to check, the car gets no answer. Auth data the car
            // sent for an earlier pairing must not be matched with the next one.
            DiagLog.add("car bluetooth: pairing variant $v, nothing to check against the car")
            variant = null; passkey = Int.MIN_VALUE; authData = null; device = null
            return
        }
        variant = v
        passkey = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, Int.MIN_VALUE)
        device = d
        // Not aborted: Android's dialog is how the user finishes a pairing
        // AARE can't finish itself.
        authenticateIfReady()
    }

    private fun authenticateIfReady() {
        val decision = PairingCheck.decide(variant, passkey, authData) ?: return
        val d = device
        // One answer per pairing: the next one starts from scratch.
        variant = null; passkey = Int.MIN_VALUE; authData = null; device = null
        val pin = decision.pin
        val line = if (pin != null) {
            val entered = runCatching { d?.setPin(pin.toByteArray(Charsets.UTF_8)) == true }.getOrDefault(false)
            if (entered) "entered the car's PIN" else "couldn't enter the car's PIN; type it in Android's dialog"
        } else when (decision.result) {
            MessageStatus.STATUS_SUCCESS -> "the car's passkey matches; confirm it on the phone"
            else -> "the car's passkey doesn't match the phone's; don't confirm"
        }
        DiagLog.add("car bluetooth: $line")
        if (!channel.sendAuthenticationResult(decision.result)) Log.i(TAG, "auth result not sent: head unit before protocol 1.5")
    }

    companion object {
        private const val TAG = "AareBtPairing"
    }
}
