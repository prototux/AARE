package net.prototux.aare.car

import android.content.Context
import kotlin.system.exitProcess

/**
 * Runs in a separate process that Shizuku starts with adb (shell) rights, which may turn
 * Bluetooth on without asking, as `adb shell cmd bluetooth_manager enable` does. It only does
 * that, once, and AARE removes it right after.
 */
class ShellBluetoothPowerService : IShellBluetoothPower.Stub {
    constructor() : super()

    /** Shizuku passes a Context when the service has this constructor. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context?) : super()

    override fun enable(): String? = PhoneBluetoothPower.run(listOf("sh", "-c", PhoneBluetoothPower.ENABLE_COMMAND))

    override fun destroy() = exitProcess(0)
}
