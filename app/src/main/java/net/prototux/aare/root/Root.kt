package net.prototux.aare.root

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.TimeUnit
import net.prototux.aare.Settings

/**
 * Root, as one permission for the whole app: car audio, phone apps and the assistant on the car
 * screen, the phone's location switch. AARE never runs su before the user allows it ([ask]): until
 * then it only looks for it ([found]).
 */
object Root {
    /** Where root managers put su (Magisk, KernelSU, APatch…), besides PATH. */
    private val KNOWN_DIRS = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/product/bin", "/debug_ramdisk")

    /**
     * Whether an su binary is there, without running it. Only a hint: a root manager may hide it
     * from apps, so asking stays possible either way.
     */
    fun found(path: String? = System.getenv("PATH"), exists: (String) -> Boolean = { File(it).exists() }): Boolean =
        (path.orEmpty().split(':') + KNOWN_DIRS).filter { it.isNotBlank() }.distinct().any { exists("$it/su") }

    /** The user allowed root, and su granted it. */
    fun allowed(context: Context): Boolean = Settings(context).rootAllowed

    /**
     * Runs `su -c id` once, off the main thread (the root manager asks the user), and allows root if
     * it answers as uid 0. [onResult] runs on the main thread.
     */
    fun ask(context: Context, onResult: (Boolean) -> Unit) {
        val settings = Settings(context.applicationContext)
        Thread {
            val granted = runCatching {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().use { it.readText() }
                if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroy()
                out.contains("uid=0")
            }.getOrDefault(false)
            if (granted) settings.rootAllowed = true
            Handler(Looper.getMainLooper()).post { onResult(granted) }
        }.apply { isDaemon = true }.start()
    }

    /** No more root: nothing starts a root helper from now on. */
    fun forbid(context: Context) {
        Settings(context).rootAllowed = false
    }
}
