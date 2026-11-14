package net.prototux.aare.car

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.AttributionSource
import android.content.Context
import android.os.Build
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.system.exitProcess

/**
 * Runs in a process Shizuku starts with adb (shell) rights, which may answer a Bluetooth pairing
 * (setPairingConfirmation needs BLUETOOTH_PRIVILEGED, which apps can't hold). Used by
 * [BluetoothAutoPair] for the connected car only; every failure is returned, not thrown, and the
 * app then leaves the pairing to Android's dialog.
 */
class ShellBluetoothService : IShellBluetooth.Stub {
    constructor() : this(null)

    /** Shizuku passes a Context when the service has this constructor. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context?)

    // The shell holds the Bluetooth permissions; lint only sees AARE's manifest.
    @SuppressLint("MissingPermission")
    override fun createBond(address: String): String? = withDevice(address) { device ->
        when {
            device.bondState == BluetoothDevice.BOND_BONDED -> null
            // The car may have started the pairing itself: nothing to start then.
            device.bondState == BluetoothDevice.BOND_BONDING -> null
            device.createBond() -> null
            else -> "Bluetooth refused to start pairing"
        }
    }

    @SuppressLint("MissingPermission")
    override fun confirmPairing(address: String): String? = withDevice(address) { device ->
        if (device.setPairingConfirmation(true)) null else "Bluetooth refused the confirmation"
    }

    @SuppressLint("MissingPermission")
    override fun setPin(address: String, pin: String): String? = withDevice(address) { device ->
        if (device.setPin(pin.toByteArray(Charsets.UTF_8))) null else "Bluetooth refused the PIN"
    }

    override fun destroy() {
        exitProcess(0)
    }

    private inline fun withDevice(address: String, block: (BluetoothDevice) -> String?): String? = try {
        val adapter = shellAdapter() ?: return "no Bluetooth adapter"
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return "invalid address"
        block(adapter.getRemoteDevice(address))
    } catch (e: Exception) {
        e.toString()
    }

    /**
     * An adapter whose calls are attributed to the shell, the uid running this process: the default
     * one would carry no package, or AARE's, and Bluetooth's permission checks would refuse it.
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun shellAdapter(): BluetoothAdapter? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/BluetoothAdapter;")
                val source = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
                return BluetoothAdapter::class.java.getMethod("createAdapter", AttributionSource::class.java).invoke(null, source) as BluetoothAdapter
            }
        }
        @Suppress("DEPRECATION")
        return BluetoothAdapter.getDefaultAdapter()
    }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
