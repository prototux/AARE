package net.prototux.aare.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

/** The runtime permissions AARE's setup asks for. */
object SetupPermissions {
    /**
     * Only the permissions [sdk] has: a newer one reads as denied on older releases and can't be
     * requested, so setup would never finish. Before 12, Bluetooth and Wi-Fi scans need location.
     * Android 12+ ignores a request for fine location without coarse; precise stays required
     * (wireless before 13, car apps' location).
     */
    fun forSdk(sdk: Int): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_COARSE_LOCATION); add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (sdk >= Build.VERSION_CODES.S) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
        if (sdk >= Build.VERSION_CODES.TIRAMISU) { add(Manifest.permission.POST_NOTIFICATIONS); add(Manifest.permission.NEARBY_WIFI_DEVICES) }
        if (sdk >= Build.VERSION_CODES.CINNAMON_BUN) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        addAll(listOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALENDAR))
    }
}

/**
 * The commands the Shizuku setup runs as the shell ([ShellSetupService]), and nothing else: grant
 * AARE's runtime permissions and its notification access, and disable or enable Google's Android
 * Auto for the user (never uninstall it, never clear its data). Each is an argv, run without a shell.
 */
object ShizukuSetup {
    const val ANDROID_AUTO = "com.google.android.projection.gearhead"
    /** Uids per Android user (UserHandle.PER_USER_RANGE). */
    private const val PER_USER_RANGE = 100_000

    /** The Android user a uid belongs to: the commands act for the user AARE runs as. */
    fun userId(uid: Int): Int = uid / PER_USER_RANGE

    /** What the setup asks for and isn't granted yet, in the setup's order. */
    fun toGrant(asked: List<String>, granted: (String) -> Boolean): List<String> = asked.filterNot(granted)

    /** The helper grants only what the setup asks for on [sdk]: never another permission. */
    fun mayGrant(permission: String, sdk: Int): Boolean = permission in SetupPermissions.forSdk(sdk)

    fun grant(pkg: String, permission: String, userId: Int) = listOf("pm", "grant", "--user", "$userId", pkg, permission)

    /** [component] is the listener's flattened ComponentName ("package/class"). */
    fun allowListener(component: String, userId: Int) = listOf("cmd", "notification", "allow_listener", component, "$userId")

    fun disableAndroidAuto(userId: Int) = listOf("pm", "disable-user", "--user", "$userId", ANDROID_AUTO)

    fun enableAndroidAuto(userId: Int) = listOf("pm", "enable", "--user", "$userId", ANDROID_AUTO)

    /**
     * Whether a command worked. pm and cmd print their errors ("Error: …", "Exception occurred…",
     * "Invalid listener…"), and some releases still exit with 0 then.
     */
    fun succeeded(exit: Int, output: String): Boolean = exit == 0 && output.lineSequence().none { line ->
        line.startsWith("Error") || line.startsWith("Invalid") || line.contains("Exception") || line.startsWith("Failure")
    }

    enum class AndroidAutoState { NOT_INSTALLED, ENABLED, DISABLED }

    /**
     * Android Auto's state from PackageManager's enabled setting for its package, null when it isn't
     * installed. "Disabled until used" counts as disabled: it doesn't answer a car until enabled.
     */
    fun androidAutoState(enabledSetting: Int?): AndroidAutoState = when (enabledSetting) {
        null -> AndroidAutoState.NOT_INSTALLED
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> AndroidAutoState.DISABLED
        else -> AndroidAutoState.ENABLED
    }
}
