package net.prototux.aare.car;

/**
 * The Bluetooth helper Shizuku runs with adb (shell) rights (ShellBluetoothService), for pairing
 * with the connected car. Each call returns null when done, else what went wrong. The app only
 * ever passes the address the car announced on its Android Auto Bluetooth channel.
 */
interface IShellBluetooth {
    String createBond(String address) = 1;

    /** Accepts the numeric comparison of the pairing under way with [address]. */
    String confirmPairing(String address) = 2;

    String setPin(String address, String pin) = 3;

    /** Shizuku's user-service teardown call. */
    void destroy() = 16777114;
}
