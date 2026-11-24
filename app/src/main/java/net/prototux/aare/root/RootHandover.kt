package net.prototux.aare.root

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import net.prototux.aare.BuildConfig

/**
 * How a root helper of AARE's own (`su` + `app_process`, the scrcpy way) hands its binder to the
 * app. A pipe can't carry a live binder, so the helper broadcasts it, targeted at AARE. AARE's
 * receiver has to be exported (the helper runs as another uid), so any installed app could send it
 * a binder of its own as well: each start makes a one-time [newToken], written to the helper's
 * stdin, and a broadcast without it is dropped. Only AARE and the helper see the token: it isn't on
 * the command line, nor in the root manager's log.
 *
 * The helper also ends when its stdin does ([exitWithApp]): AARE closes it, or dies. Killing the su
 * process alone may not reach the helper.
 */
object RootHandover {
    private const val TAG = "AareRootHandover"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_BUNDLE = "bundle"
    const val EXTRA_BINDER = "binder"
    private const val TOKEN_BYTES = 32

    fun newToken(random: SecureRandom = SecureRandom()): String =
        ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** Whether a broadcast's [sent] token is this start's [expected] one (in constant time). */
    fun matches(expected: String, sent: String?): Boolean =
        sent != null && MessageDigest.isEqual(expected.toByteArray(), sent.toByteArray())

    /** A running helper: [binder] is what it handed back. */
    class Helper(private val process: Process, val binder: IBinder) {
        /** Ends the helper: its stdin closes, so it exits; the su process goes too. */
        fun stop() {
            runCatching { process.outputStream.close() }
            runCatching { process.destroy() }
        }
    }

    /**
     * Starts [mainClass] (in AARE's APK) as root with [args], and waits up to [timeoutS] seconds for
     * its binder, sent as [action]. Null if su refused, the helper failed, or nothing came in time.
     * Blocks: call it off the main thread.
     */
    fun start(context: Context, mainClass: String, action: String, args: List<String>, timeoutS: Long = 8): Helper? {
        val token = newToken()
        val binder = AtomicReference<IBinder?>()
        val got = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (!matches(token, intent?.getStringExtra(EXTRA_TOKEN))) {
                    Log.w(TAG, "dropped a $action broadcast without this start's token")
                    return
                }
                binder.set(intent?.getBundleExtra(EXTRA_BUNDLE)?.getBinder(EXTRA_BINDER))
                got.countDown()
            }
        }
        val filter = IntentFilter(action)
        // The broadcast comes from the root process (another uid), so it must be exported.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        try {
            val apk = context.applicationInfo.sourceDir
            val cmd = "CLASSPATH='$apk' app_process / $mainClass ${args.joinToString(" ")}"
            val p = runCatching { ProcessBuilder("su", "-c", cmd).start() }
                .onFailure { Log.w(TAG, "su launch failed", it) }.getOrNull() ?: return null
            // Drain stderr so a chatty app_process can't fill its pipe and stall the helper.
            Thread { runCatching { p.errorStream.bufferedReader().forEachLine { if (BuildConfig.DEBUG) Log.d(TAG, "su: $it") } } }
                .apply { isDaemon = true }.start()
            try {
                p.outputStream.write("$token\n".toByteArray())
                p.outputStream.flush()
            } catch (e: IOException) {
                Log.w(TAG, "the helper's stdin closed before the token", e)
                p.destroy()
                return null
            }
            val handed = if (got.await(timeoutS, TimeUnit.SECONDS)) binder.get() else null
            if (handed == null) {
                Log.w(TAG, "no binder from $mainClass")
                runCatching { p.outputStream.close() }
                p.destroy()
                return null
            }
            return Helper(p, handed)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // ------------------------------------------------------------------ in the helper

    /** The token AARE wrote first on stdin; read a byte at a time, so nothing past its line is taken. */
    fun readToken(): String? {
        val b = StringBuilder()
        while (true) {
            val c = System.`in`.read()
            if (c < 0) return null
            if (c == '\n'.code) return b.toString()
            if (b.length >= 2 * TOKEN_BYTES) return null
            b.append(c.toChar())
        }
    }

    /** Sends [binder] to [appPackage] only, with [token]; false if Android refused the broadcast. */
    fun handBack(context: Context, action: String, appPackage: String, token: String, binder: IBinder): Boolean = runCatching {
        val bundle = Bundle().apply { putBinder(EXTRA_BINDER, binder) }
        context.sendBroadcast(Intent(action).setPackage(appPackage).putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_BUNDLE, bundle))
    }.onFailure { Log.w(TAG, "could not hand the binder back", it) }.isSuccess

    /** Ends this process once stdin ends (AARE closed it, or died), so no root helper is left behind. */
    fun exitWithApp() {
        Thread {
            runCatching { while (System.`in`.read() >= 0) Unit }
            exitProcess(0)
        }.apply { isDaemon = true }.start()
    }
}
