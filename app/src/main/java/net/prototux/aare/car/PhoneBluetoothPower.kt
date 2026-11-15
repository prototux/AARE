package net.prototux.aare.car

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import net.prototux.aare.BuildConfig
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.audio.CaptureMethods
import net.prototux.aare.service.ProjectionService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Turns the phone's Bluetooth on when a car that offers calls through it (it lists a Bluetooth
 * service) connects while it is off. Without Bluetooth the car can't connect hands-free, so calls
 * stay on the phone, and nothing said why.
 *
 * Since Android 13 an app may turn Bluetooth on only by asking the user. So: through Shizuku or
 * root when AARE may already use them (the same `cmd bluetooth_manager enable` as over adb), else
 * a notification whose tap shows Android's own "turn on Bluetooth?" prompt. [onTurnedOn] runs on
 * the main thread once Bluetooth is on, so the car can be asked to connect again.
 */
class PhoneBluetoothPower(private val context: Context, private val onTurnedOn: () -> Unit) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    @Volatile private var stopped = false

    /** Main thread. Does nothing when Bluetooth is already on, or the phone has none. */
    fun start() {
        val a = adapter ?: return
        if (a.isEnabled) return
        DiagLog.add("car bluetooth: the phone's Bluetooth is off, so calls stay on the phone; turning it on")
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON || stopped) return
                DiagLog.add("car bluetooth: the phone's Bluetooth is on")
                stop()
                onTurnedOn()
            }
        }
        receiver = r
        // A protected system broadcast: delivered whatever the export flag.
        runCatching { ContextCompat.registerReceiver(context, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onFailure { Log.w(TAG, "register", it) }
        Thread({ turnOn() }, "aare-bt-power").apply { isDaemon = true }.start()
    }

    /** Main thread: the session ended, or Bluetooth is on. */
    fun stop() {
        stopped = true
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun turnOn() {
        val (how, error) = when {
            CaptureMethods.shizukuAvailable() -> "Shizuku" to viaShizuku()
            CaptureMethods.rootAvailable(context) -> "root" to run(listOf("su", "-c", ENABLE_COMMAND))
            else -> null to null
        }
        if (how != null) {
            if (error == null && waitUntilOn()) return
            DiagLog.add("car bluetooth: couldn't turn Bluetooth on through $how (${error ?: "still off after ${WAIT_S} s"}); asking on the phone")
        }
        if (!stopped && adapter?.isEnabled != true) main.post { if (!stopped) notifyOff() }
    }

    private fun waitUntilOn(): Boolean {
        val until = System.currentTimeMillis() + WAIT_S * 1000
        while (System.currentTimeMillis() < until && !stopped) {
            if (adapter?.isEnabled == true) return true
            Thread.sleep(250)
        }
        return adapter?.isEnabled == true || stopped
    }

    /** Binds the helper, asks it once, removes it. Null when the command ran, else what went wrong. */
    private fun viaShizuku(): String? {
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellBluetoothPowerService::class.java.name))
            .daemon(false).processNameSuffix("bluetooth").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
        val bound = CountDownLatch(1)
        var helper: IShellBluetoothPower? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                helper = binder?.takeIf { it.pingBinder() }?.let(IShellBluetoothPower.Stub::asInterface)
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { return "bind: ${it.message}" }
        return try {
            if (!bound.await(8, TimeUnit.SECONDS)) "the helper didn't start"
            else helper?.let { h -> runCatching { h.enable() }.getOrElse { "helper: ${it.message}" } } ?: "the helper didn't start"
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    private fun notifyOff() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(ProjectionService.CHANNEL,
            context.getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_LOW))
        // Android's prompt needs BLUETOOTH_CONNECT; without it, the Bluetooth settings.
        val canAsk = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val open = if (canAsk) Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE) else Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.getString(R.string.notif_bluetooth_off_text)
        val n = Notification.Builder(context, ProjectionService.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_bluetooth_off_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }.onFailure { Log.w(TAG, "notify", it) }
    }

    companion object {
        private const val TAG = "AareBtPower"
        private const val NOTIFICATION_ID = 0xB7
        private const val WAIT_S = 10L

        /** `cmd bluetooth_manager` is Android 13+; `svc bluetooth` covers Android 12. */
        const val ENABLE_COMMAND = "cmd bluetooth_manager enable || svc bluetooth enable"

        /** Runs [command]. Null when it exited with 0, else its output or why it didn't run. */
        fun run(command: List<String>): String? = runCatching {
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroy(); return "timed out" }
            if (p.exitValue() == 0) null else out.ifEmpty { "exit ${p.exitValue()}" }
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }
}
