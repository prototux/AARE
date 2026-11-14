package net.prototux.aare.setup

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import net.prototux.aare.BuildConfig
import net.prototux.aare.notify.CarNotificationListener

/**
 * Runs AARE's setup commands in a process that Shizuku starts with adb (shell) rights: granting
 * AARE's own permissions and notification access, and disabling or enabling Google's Android
 * Auto. Only the commands [ShizukuSetup] builds, for AARE's package and the caller's user: no call
 * takes a command line.
 */
class ShellSetupService : IShellSetup.Stub {
    constructor()

    /** Shizuku passes a Context when the service has this constructor. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context?)

    override fun grantPermission(permission: String): Bundle {
        val user = callerUser()
        if (!ShizukuSetup.mayGrant(permission, Build.VERSION.SDK_INT)) return result("pm grant $permission", -1, "not a permission AARE's setup asks for")
        return run(ShizukuSetup.grant(BuildConfig.APPLICATION_ID, permission, user))
    }

    override fun allowNotificationListener(): Bundle {
        val component = ComponentName(BuildConfig.APPLICATION_ID, CarNotificationListener::class.java.name).flattenToString()
        return run(ShizukuSetup.allowListener(component, callerUser()))
    }

    override fun setAndroidAutoEnabled(enabled: Boolean): Bundle {
        val user = callerUser()
        return run(if (enabled) ShizukuSetup.enableAndroidAuto(user) else ShizukuSetup.disableAndroidAuto(user))
    }

    override fun destroy() = exitProcess(0)

    private fun callerUser() = ShizukuSetup.userId(Binder.getCallingUid())

    /** Runs [argv] as this process (no shell in between), waits for it, and logs what it said. */
    private fun run(argv: List<String>): Bundle {
        val line = argv.joinToString(" ")
        val (exit, output) = runCatching {
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            // Read before waiting: a full pipe would stall the command.
            val out = p.inputStream.bufferedReader().use { it.readText() }.take(MAX_OUTPUT).trim()
            if (!p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) { p.destroy(); -1 to "timed out" } else p.exitValue() to out
        }.getOrElse { -1 to (it.message ?: it.toString()) }
        Log.i(TAG, "$line -> $exit${if (output.isNotEmpty()) ": $output" else ""}")
        return result(line, exit, output)
    }

    private fun result(command: String, exit: Int, output: String) = Bundle().apply {
        putString(KEY_COMMAND, command)
        putInt(KEY_EXIT, exit)
        putString(KEY_OUTPUT, output)
    }

    companion object {
        private const val TAG = "AareShellSetup"
        private const val TIMEOUT_S = 15L
        private const val MAX_OUTPUT = 2000
        const val KEY_COMMAND = "command"
        const val KEY_EXIT = "exit"
        const val KEY_OUTPUT = "output"
    }
}
