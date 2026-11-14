package net.prototux.aare.setup;

import android.os.Bundle;

/**
 * The setup helper Shizuku runs with adb (shell) rights (ShellSetupService). Each call runs one
 * command that ShizukuSetup builds, for the caller's user, and returns a Bundle with "command",
 * "exit" and "output". It runs nothing else.
 */
interface IShellSetup {
    /** pm grant, for one of the runtime permissions AARE's setup asks for (others are refused). */
    Bundle grantPermission(String permission) = 1;

    /** cmd notification allow_listener, for AARE's notification listener. */
    Bundle allowNotificationListener() = 2;

    /** pm enable, or pm disable-user, for Google's Android Auto. */
    Bundle setAndroidAutoEnabled(boolean enabled) = 3;

    /** Shizuku's user-service teardown call. */
    void destroy() = 16777114;
}
