package net.prototux.aare.transport.wireless

import android.content.Context
import net.prototux.aare.BuildConfig
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs in a process Shizuku starts with adb (shell) rights. Its only job: tell the Wi-Fi service
 * that AARE's own network requests are approved, so joining an associated car's network skips the
 * "connect to device" dialog. The request stays AARE's (a WifiNetworkSpecifier: app-only,
 * local-only, never the phone's default network); the shell only answers the dialog's question.
 *
 * The command and the package are fixed here: the helper can do nothing else.
 */
class ShellWifiService : IShellWifi.Stub {
    constructor() : this(null)

    /** Shizuku passes a Context when the service has this constructor. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context?)

    override fun setNetworkRequestsApproved(approved: Boolean): String? = try {
        val process = ProcessBuilder("cmd", "wifi", "network-requests-set-user-approved", BuildConfig.APPLICATION_ID, if (approved) "yes" else "no")
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            "the Wi-Fi service did not answer"
        } else {
            val output = process.inputStream.bufferedReader().readText().trim()
            // Success prints nothing; an older Android answers an unknown command with an error and a non-zero exit.
            if (process.exitValue() == 0 && output.isEmpty()) null else "exit ${process.exitValue()}: ${output.take(200)}"
        }
    } catch (e: Exception) {
        e.toString()
    }

    override fun destroy() {
        exitProcess(0)
    }
}
