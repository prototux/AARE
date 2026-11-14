package net.prototux.aare.car

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import net.prototux.aare.BuildConfig
import net.prototux.aare.DiagLog
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Opt-in (Settings.autoPairCarBluetooth): pairs the phone with the connected car's Bluetooth for
 * calls without Android's pairing dialog, through Shizuku ([ShellBluetoothService]). Only
 * [carAddress], the address the head unit announced on its Android Auto Bluetooth channel; a
 * pairing request is only accepted when its passkey is the one the car sent over Android Auto
 * ([AutoPairing.answer]). Every step is logged; on any failure the pairing goes on in Android's
 * dialog as it would without this.
 *
 * One instance per pairing; it ends itself when the bond is made or failed, after [TIMEOUT_MS],
 * or with [stop] (the session ended).
 */
internal class BluetoothAutoPair(context: Context, private val carAddress: String) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    /** Binder calls block: one at a time, in order, off the main and AAP threads. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "aare-autopair").apply { isDaemon = true } }
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellBluetoothService::class.java.name))
        .daemon(false).processNameSuffix("bluetooth").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    private val connection = Connection()
    private var receiver: BroadcastReceiver? = null
    @Volatile private var helper: IShellBluetooth? = null
    @Volatile private var stopped = false

    // Main thread only.
    private var authData: String? = null
    /** A pairing request that came before the car's passkey: (variant, key). */
    private var waiting: Pair<Int, Int?>? = null

    fun start() {
        main.post {
            if (stopped) return@post
            listen()
            main.postDelayed({ stop("no pairing within ${TIMEOUT_MS / 1000} s") }, TIMEOUT_MS)
            work {
                val h = bind() ?: return@work stop("the Shizuku Bluetooth helper didn't start")
                log("asking Bluetooth to pair with the car")
                runCatching { h.createBond(carAddress) }.getOrElse { it.toString() }?.let { stop("couldn't start pairing ($it)") }
            }
        }
    }

    /** The car's passkey or PIN, from its Android Auto Bluetooth channel. Any thread. */
    fun onAuthenticationData(data: String) {
        main.post {
            authData = data
            waiting?.let { (variant, key) -> waiting = null; answer(carAddress, variant, key) }
        }
    }

    /** Ends this pairing helper; [why] is logged. Any thread, idempotent. */
    fun stop(why: String? = null) {
        if (stopped) return
        stopped = true
        why?.let { log("$it; leaving the pairing to Android") }
        main.post {
            main.removeCallbacksAndMessages(null)
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            receiver = null
        }
        work { runCatching { Shizuku.unbindUserService(args, connection, true) } }
        worker.shutdown()
    }

    private fun listen() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                // Never another device: only the car this session is connected to.
                if (!device.address.equals(carAddress, ignoreCase = true) || stopped) return
                when (intent.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> answer(device.address,
                        intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1),
                        intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1).takeIf { it >= 0 },
                    )
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDED -> { log("paired with the car"); stop() }
                        BluetoothDevice.BOND_NONE -> if (intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1) == BluetoothDevice.BOND_BONDING) stop("pairing failed")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            // Ordered: hear the request before the dialog is up; it is not aborted, so the dialog stays the fallback.
            priority = 999
        }
        // Sent by the Bluetooth stack, which is not our uid: exported.
        runCatching { ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED) }
            .onSuccess { receiver = r }
            .onFailure { stop("can't listen for the pairing request (${it.message})") }
    }

    private fun answer(requestAddress: String, variant: Int, key: Int?) {
        when (val a = AutoPairing.answer(carAddress, requestAddress, variant, key, authData)) {
            AutoPairing.Answer.Wait -> { log("pairing request before the car's passkey; waiting for it"); waiting = variant to key }
            AutoPairing.Answer.Confirm -> call("confirming the passkey the car showed") { it.confirmPairing(carAddress) }
            is AutoPairing.Answer.Pin -> call("entering the PIN the car sent") { it.setPin(carAddress, a.pin) }
            is AutoPairing.Answer.Leave -> stop(a.why)
        }
    }

    private fun call(what: String, block: (IShellBluetooth) -> String?) {
        if (stopped) return
        work {
            val h = helper ?: return@work stop("the Shizuku Bluetooth helper isn't connected")
            log(what)
            runCatching { block(h) }.getOrElse { it.toString() }?.let { stop("Shizuku couldn't answer the pairing ($it)") }
        }
    }

    /** Runs [task] on the worker, unless [stop] already shut it down. */
    private fun work(task: () -> Unit) {
        runCatching { worker.execute(task) }
    }

    /** Binds the helper (on the worker thread: Shizuku answers on the main one). */
    private fun bind(): IShellBluetooth? {
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { return null }
        return connection.await().also { helper = it }
    }

    private fun log(line: String) = DiagLog.add("car bluetooth: $line ($carAddress)")

    private class Connection : ServiceConnection {
        private val bound = CountDownLatch(1)
        @Volatile private var service: IShellBluetooth? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let(IShellBluetooth.Stub::asInterface)
            bound.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }

        fun await(): IShellBluetooth? = if (bound.await(5, TimeUnit.SECONDS)) service else null
    }

    private companion object {
        /** The car shows its pairing prompt for about this long. */
        const val TIMEOUT_MS = 60_000L
    }
}
