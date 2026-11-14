package net.prototux.aare.transport.wireless

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import net.prototux.aare.BuildConfig
import net.prototux.aare.aap.AapLog
import net.prototux.aare.audio.CaptureMethods
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App side of [ShellWifiService]: one bind per join, released with the approval it gave.
 *
 * The approval Android keeps is for the whole app (every network request AARE makes is approved
 * while it is on), and it stays on if AARE is killed before taking it back. So it is on only for
 * the join, taken back in a finally whatever happens, and noted in a file of its own before it is
 * given: [recover] takes back one a killed process left behind.
 */
internal object ShellWifi {
    private const val KEY_LEFT_ON = "approval_left_on"

    /** Held while an approval is given, taken back or recovered, so [recover] never undoes a live one. */
    private val lock = Any()
    /** Approvals this process gave and hasn't taken back yet (guarded by [lock]). */
    private var live = 0

    /**
     * Has Android approve AARE's Wi-Fi network requests until the returned handle is closed, which
     * takes the approval back and stops the helper. Null if Shizuku isn't usable or refused: then
     * Android asks the user as usual. Blocks for a few seconds at most; not on the main thread
     * (Shizuku answers the bind there).
     */
    fun approve(context: Context, log: AapLog): AutoCloseable? = synchronized(lock) {
        if (!CaptureMethods.shizukuAvailable()) return null
        val helper = Helper.bind(context, log) ?: return null
        // Written before the approval exists (commit, not apply): a process killed from here on
        // leaves the note for recover() at the next start.
        state(context).edit().putBoolean(KEY_LEFT_ON, true).commit()
        live++
        var handedOver = false
        try {
            val error = runCatching { helper.service.setNetworkRequestsApproved(true) }.getOrElse { it.toString() }
            if (error != null) {
                log.w("wireless: Shizuku couldn't approve the network request ($error); Android will ask")
                return null
            }
            log.i("wireless: AARE's network requests approved through Shizuku while it joins the car's network")
            handedOver = true
            val closed = AtomicBoolean(false)
            AutoCloseable { if (closed.compareAndSet(false, true)) synchronized(lock) { revoke(context, helper, log) } }
        } finally {
            // A failed "yes" is taken back too: the command may have run before the error came back.
            if (!handedOver) revoke(context, helper, log)
        }
    }

    /**
     * Takes back an approval an earlier process gave and couldn't take back (killed or crashed
     * mid-join). Call when the service starts; does nothing without one. If Shizuku isn't usable
     * now, the note stays and the next start tries again. Blocks; not on the main thread.
     */
    fun recover(context: Context, log: AapLog) {
        synchronized(lock) {
            if (live > 0 || !state(context).getBoolean(KEY_LEFT_ON, false)) return
            if (!CaptureMethods.shizukuAvailable()) {
                log.w("wireless: a Wi-Fi approval was left on by an earlier run, and Shizuku isn't available to take it back; trying at the next start")
                return
            }
            val helper = Helper.bind(context, log) ?: return
            live++
            log.i("wireless: taking back a Wi-Fi approval left on by an earlier run")
            revoke(context, helper, log)
        }
    }

    /** "no", the note cleared only if that worked, and the helper stopped. Called with [lock] held. */
    private fun revoke(context: Context, helper: Helper, log: AapLog) {
        try {
            val error = runCatching { helper.service.setNetworkRequestsApproved(false) }.getOrElse { it.toString() }
            if (error == null) {
                if (live == 1) state(context).edit().remove(KEY_LEFT_ON).commit()
            } else {
                log.w("wireless: Shizuku couldn't take the network approval back ($error); trying again at the next start")
            }
        } finally {
            live--
            helper.unbind()
        }
    }

    private fun state(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("shell_wifi", Context.MODE_PRIVATE)

    /** A bound [ShellWifiService]. */
    private class Helper(val service: IShellWifi, private val args: Shizuku.UserServiceArgs, private val connection: Connection) {
        fun unbind() {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }

        companion object {
            fun bind(context: Context, log: AapLog): Helper? {
                val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellWifiService::class.java.name))
                    .daemon(false).processNameSuffix("wifi").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
                val connection = Connection()
                runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
                    log.w("wireless: can't start the Shizuku Wi-Fi helper", it)
                    return null
                }
                val service = connection.await()
                if (service == null) {
                    log.w("wireless: the Shizuku Wi-Fi helper didn't start; Android will ask to approve the network")
                    runCatching { Shizuku.unbindUserService(args, connection, true) }
                    return null
                }
                return Helper(service, args, connection)
            }
        }
    }

    private class Connection : ServiceConnection {
        private val bound = CountDownLatch(1)
        @Volatile private var service: IShellWifi? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let(IShellWifi.Stub::asInterface)
            bound.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }

        fun await(): IShellWifi? = if (bound.await(5, TimeUnit.SECONDS)) service else null
    }
}
