package net.prototux.aare.car;

/**
 * The Bluetooth helper Shizuku runs with adb (shell) rights (ShellBluetoothService), for pairing
 * with the connected car and turning Bluetooth on while it's connected: only what an app can't do
 * itself. Each call returns null when done, else what went wrong. The app only ever passes the
 * address the car announced on its Android Auto Bluetooth channel, and only for a pairing
 * PairingCheck accepted.
 */
interface IShellBluetooth {
    /** Accepts the numeric comparison of the pairing under way with [address]. */
    String confirmPairing(String address) = 2;

    String setPin(String address, String pin) = 3;

    /** Turns the phone's Bluetooth on (PhoneBluetoothPower: a car is connected and it was off). */
    String enableBluetooth() = 4;

    /** Shizuku's user-service teardown call. */
    void destroy() = 16777114;
}
