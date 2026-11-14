package net.prototux.aare.transport.wireless;

/** The Wi-Fi helper Shizuku runs with adb (shell) rights (ShellWifiService). */
interface IShellWifi {
    /**
     * Whether Android approves AARE's own Wi-Fi network requests without its "connect to device"
     * dialog. Returns null when done, else what went wrong.
     */
    String setNetworkRequestsApproved(boolean approved) = 1;

    /** Shizuku's user-service teardown call. */
    void destroy() = 16777114;
}
