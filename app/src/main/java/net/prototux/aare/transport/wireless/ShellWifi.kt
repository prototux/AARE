package net.prototux.aare.transport.wireless

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import net.prototux.aare.BuildConfig
import net.prototux.aare.aap.AapLog
import net.prototux.aare.audio.CaptureMethods
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** App side of [ShellWifiService]: one bind per join, released with the approval it gave. */
internal object ShellWifi {
    /**
     * Has Android approve AARE's Wi-Fi network requests until the returned handle is closed, which
     * takes the approval back and stops the helper. Null if Shizuku isn't usable or refused: then
     * Android asks the user as usual. Blocks for a few seconds at most; not on the main thread
     * (Shizuku answers the bind there).
     */
    fun approve(context: Context, log: AapLog): AutoCloseable? {
        if (!CaptureMethods.shizukuAvailable()) return null
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellWifiService::class.java.name))
            .daemon(false).processNameSuffix("wifi").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
        val connection = Connection()
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
            log.w("wireless: can't start the Shizuku Wi-Fi helper", it)
            return null
        }
        fun unbind() = runCatching { Shizuku.unbindUserService(args, connection, true) }
        val service = connection.await()
        if (service == null) {
            log.w("wireless: the Shizuku Wi-Fi helper didn't start; Android will ask to approve the network")
            unbind()
            return null
        }
        val error = runCatching { service.setNetworkRequestsApproved(true) }.getOrElse { it.toString() }
        if (error != null) {
            log.w("wireless: Shizuku couldn't approve the network request ($error); Android will ask")
            unbind()
            return null
        }
        log.i("wireless: AARE's network request approved through Shizuku for this join")
        return AutoCloseable {
            runCatching { service.setNetworkRequestsApproved(false) }
                .onSuccess { e -> if (e != null) log.w("wireless: Shizuku couldn't take the network approval back ($e)") }
            unbind()
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
