package net.prototux.aare.car;

/**
 * The Shizuku helper that turns the phone's Bluetooth on with the shell's rights, for
 * PhoneBluetoothPower. Implemented by ShellBluetoothPowerService.
 */
interface IShellBluetoothPower {
    /** Turns Bluetooth on. Returns null when the command ran, else what went wrong. */
    String enable() = 1;

    /** Shizuku's user-service teardown call. */
    void destroy() = 16777114;
}
