package net.prototux.aare.devlog

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import net.prototux.aare.BuildConfig
import net.prototux.aare.DiagLog
import net.prototux.aare.PinnedHeadUnits
import net.prototux.aare.Settings
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Developer mode: copies every line AARE's process writes to logcat into rotating files in
 * a `debug` folder ([DevLogFiles]; private to the app, see [location]), through [LogRedactor] while the privacy filter is on.
 *
 * A `logcat` started by the app needs no READ_LOGS and raises no consent dialog: logd gives a reader
 * without log credentials only the lines written by its own UID. On Android 12+ that child is a
 * phantom process the system may kill; it is started again with `-T` (the time of the last line
 * written), which also recovers what logd still holds from while AARE was dead (a crash).
 *
 * Each start, stop and restart is a step on its own daemon thread that first waits for the previous
 * step to end: the caller never waits, and a start right after a stop opens its file only once the
 * stop has deleted the old ones.
 */
object DevLog {
    private const val TAG = "AareDevLog"
    private const val DIR = "debug"
    private const val LEARNED = "devlog-learned.txt"
    private const val TAIL_BYTES = 16 * 1024L
    private const val RESTART_DELAY_MS = 1000L
    private const val WRITER = "aare-devlog"
    private const val CLEANUP = "aare-devlog-cleanup"
    private const val REDACTION_FAILED = "<line not written: redaction failed>"
    private val LOGCAT_PREFIX = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+ [A-Z] """)

    private val lock = Any()
    @SuppressLint("StaticFieldLeak") // the application context only
    private var session: Session? = null
    private var lastStep: Thread? = null

    private val running: Boolean
        get() = synchronized(lock) { session != null }

    fun start(context: Context) {
        val app = context.applicationContext
        if (running) return
        val privacy = Settings(app).developerLogPrivacy
        if (privacy && !filterWorks()) return
        synchronized(lock) { if (session == null) begin(app, privacy, emptySet()) }
    }

    /** Stops the reader, then deletes every file in the folder, the folder and the learned values; returns at once. */
    fun stop(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            session?.let(::end)
            session = null
            step(CLEANUP) {
                File(app.filesDir, LEARNED).delete()
                val dir = location(app)
                delete(dir) { true }
                dir?.delete()
                // Written there on Android 10, before an upgrade to 11.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val old = File(app.filesDir, DIR)
                    if (old.exists()) {
                        delete(old) { true }
                        old.delete()
                    }
                }
            }
        }
    }

    /**
     * Applies the current privacy filter: a new file, whose header shows it; turning the filter on also
     * deletes the files written with it off. Starts the reader even if it was not running (it stopped
     * on an error), so call it whenever the filter changes while developer mode is on, never when off.
     */
    fun restart(context: Context) {
        val app = context.applicationContext
        val privacy = Settings(app).developerLogPrivacy
        val works = !privacy || filterWorks()
        synchronized(lock) {
            val old = session?.also(::end)
            session = null
            // A writer deletes the files written with the filter off itself, before its first file.
            if (works) begin(app, privacy, old?.learned ?: emptySet())
            else step(CLEANUP) { delete(location(app), DevLogFiles::isRaw) }
        }
    }

    /**
     * Masks [ssid] as `<ssid>` in the lines that follow, and in those replayed after a restart, until
     * developer mode is turned off. Does nothing when not running.
     */
    fun learn(ssid: String?) {
        if (ssid.isNullOrBlank()) return
        synchronized(lock) {
            val s = session ?: return
            if (!s.learned.add(ssid)) return
            s.rebuild()
            // Before the writer has loaded the file (a stop's cleanup may still delete it), it saves them.
            if (s.loaded) save(s, listOf(ssid))
        }
    }

    /**
     * Why the privacy filter can't run on this device (a pattern its regex engine rejects fails the
     * redactor's class initialisation, an Error), or null when it can.
     */
    internal fun filterUnavailable(probe: () -> Unit = { LogRedactor(mapOf("abc" to "<x>")).redact("abc") }): Throwable? =
        try {
            probe()
            null
        } catch (e: Throwable) {
            e
        }

    /** Never start with the filter on when it can't run: no crash at launch, and no raw line in a filtered file. */
    private fun filterWorks(): Boolean {
        val e = filterUnavailable() ?: return true
        DiagLog.add("developer log: privacy filter unavailable on this device: ${e.javaClass.simpleName}")
        return false
    }

    /** Writes every log file, oldest first, to [out]; false when there is none. */
    fun export(context: Context, out: OutputStream): Boolean {
        val writer = synchronized(lock) { session?.out?.writer }
        try {
            writer?.flush()
        } catch (_: IOException) {
            // closed by a rotation or a stop, so already flushed
        }
        val dir = location(context.applicationContext) ?: return false
        var any = false
        for (f in logFiles(dir).sortedBy { it.name }) {
            // the size cap or a stop may delete a file meanwhile
            val input = try { FileInputStream(f) } catch (_: FileNotFoundException) { continue }
            input.use { it.copyTo(out) }
            any = true
        }
        out.flush()
        return any
    }

    /** The `-T` argument from the end of a log file; [chunk] starts mid-line unless it is the [wholeFile]. */
    internal fun lastTailArg(chunk: String, wholeFile: Boolean): String? =
        chunk.lines().let { if (wholeFile) it else it.drop(1) }.asReversed().firstNotNullOfOrNull(DevLogFiles::tailArg)

    /**
     * [line] with logcat's prefix (time, pid, tid, level) kept verbatim and the rest through [redact], so
     * a known value can't alter the time the next start resumes from. If [redact] throws, the rest is
     * not written.
     */
    internal fun redactLine(line: String, redact: (String) -> String, onFailure: (Exception) -> Unit = {}): String {
        val prefix = LOGCAT_PREFIX.matchAt(line, 0)?.value.orEmpty()
        val rest = try {
            redact(line.substring(prefix.length))
        } catch (e: Exception) {
            onFailure(e)
            REDACTION_FAILED
        }
        return prefix + rest
    }

    private class Session(val app: Context, val privacy: Boolean, val learnedFile: File) {
        private val stopSignal = CountDownLatch(1)
        val stopping: Boolean get() = stopSignal.count == 0L
        fun signalStop() = stopSignal.countDown()
        fun awaitStop(ms: Long): Boolean = stopSignal.await(ms, TimeUnit.MILLISECONDS)

        // Guarded by lock.
        var process: Process? = null
        val learned = LinkedHashSet<String>()
        /** Only grows: a name stays masked after its device is unpaired, or while Bluetooth is off. */
        var known: Map<String, String> = emptyMap()
        var loaded = false

        /** Built only with the filter on. */
        @Volatile var redactor: LogRedactor? = null
        @Volatile var out: Output? = null
        /** Set when a source of known values changes; the writer reads them again before the next line. */
        @Volatile var stale = false

        // Writer thread only.
        var headerNote: String? = null
        var noteLogged = false

        fun rebuild() {
            if (privacy) redactor = LogRedactor(learned.associateWith { "<ssid>" } + known)
        }
    }

    private class Output(file: File) {
        private val counter = CountingStream(FileOutputStream(file))
        val writer = BufferedWriter(OutputStreamWriter(counter, Charsets.UTF_8))
        val full: Boolean get() = counter.count >= DevLogFiles.MAX_FILE_BYTES
    }

    /** Counts the bytes that reach the file, so rotation needs no stat per line. */
    private class CountingStream(out: OutputStream) : FilterOutputStream(out) {
        @Volatile var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    /** Android 11+ keeps `Android/data` private to the app; before it other apps could read it, so the files stay in internal storage. */
    private fun location(app: Context): File? =
        // getExternalFilesDir(DIR) would create the folder: with the mode off nothing must be left behind.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) app.getExternalFilesDir(null)?.let { File(it, DIR) } else File(app.filesDir, DIR)

    /** Absolute path of the developer-log folder, without creating it (only the app's own `files` folder, if missing). */
    fun folderPath(context: Context): String = location(context.applicationContext)?.absolutePath ?: DIR

    private fun folder(app: Context): File? =
        location(app)?.takeIf { (it.isDirectory || it.mkdirs()) && it.canWrite() }

    private fun logFiles(dir: File): List<File> = dir.listFiles().orEmpty().filter { it.isFile && DevLogFiles.isLog(it.name) }

    /** Under [lock]. */
    private fun begin(app: Context, privacy: Boolean, carried: Set<String>) {
        val s = Session(app, privacy, File(app.filesDir, LEARNED))
        s.learned += carried
        s.rebuild()
        session = s
        step(WRITER) { run(s) }
    }

    /** Under [lock]. */
    private fun end(s: Session) {
        s.signalStop()
        s.process?.destroy()
    }

    /** Under [lock]: runs [work] on a new daemon thread once the previous step has ended. */
    private fun step(name: String, work: () -> Unit) {
        val previous = lastStep
        lastStep = Thread({
            while (previous?.isAlive == true) runCatching { previous.join() }
            work()
        }, name).apply { isDaemon = true; start() }
    }

    private fun delete(dir: File?, which: (String) -> Boolean) {
        if (dir == null) return DiagLog.add("developer log: no storage available, files not deleted")
        val left = dir.listFiles().orEmpty().filter { it.isFile && which(it.name) }.count { !it.delete() }
        if (left > 0) DiagLog.add("developer log: could not delete $left file(s)")
    }

    private fun run(s: Session) {
        var failure: String? = null
        val undo = ArrayList<() -> Unit>()
        try {
            val dir = if (s.stopping) null else folder(s.app)
            if (dir == null) {
                if (!s.stopping) failure = "developer log: no storage available"
            } else {
                // Also when a restart's deletion was cut short: no file written with the filter off survives it on.
                if (s.privacy) delete(dir, DevLogFiles::isRaw)
                load(s)
                if (s.privacy) watch(s, undo)
                failure = copy(s, dir)
            }
        } catch (e: Exception) {
            if (!s.stopping) failure = "developer log stopped: ${e.message ?: e}"
        } finally {
            undo.forEach { runCatching { it() } }
            s.out?.let { runCatching { it.writer.close() } }
            synchronized(lock) { if (session === s) session = null }
        }
        failure?.let(DiagLog::add)
    }

    /** Loads the learned values, and saves those learned before. */
    private fun load(s: Session) {
        val saved = try { s.learnedFile.readLines().filter { it.isNotBlank() }.toSet() } catch (_: IOException) { emptySet() }
        synchronized(lock) {
            val unsaved = s.learned - saved
            s.learned += saved
            s.loaded = true
            s.rebuild()
            if (unsaved.isNotEmpty()) save(s, unsaved)
        }
    }

    private fun save(s: Session, values: Collection<String>) {
        try {
            s.learnedFile.appendText(values.joinToString("") { "$it\n" })
        } catch (e: IOException) {
            Log.w(TAG, "developer log: could not save learned values", e)
        }
    }

    /**
     * Marks the known values stale whenever one of their sources changes, until [undo] runs. Bonded
     * devices only read as empty while Bluetooth is off, hence STATE_ON. Residual window: lines read
     * before the callback has run (one logged right after the change) and the line being written when
     * it runs are filtered with the previous values. The Bluetooth receiver needs BLUETOOTH_CONNECT
     * when the reader starts: granted later, the bonded names are read at the next file or refresh,
     * the receiver only from the next start.
     */
    private fun watch(s: Session, undo: MutableList<() -> Unit>) {
        val app = s.app
        // SharedPreferences holds its listeners weakly: undo keeps them alive.
        undo += PinnedHeadUnits(app).listen { s.stale = true }
        val settings = Settings(app)
        val cars = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "known_cars") s.stale = true }
        settings.registerListener(cars)
        undo += { settings.unregisterListener(cars) }
        if (!bluetoothAllowed(app)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
                if (on || intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) s.stale = true
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) }
        // Protected system broadcasts, the bond one sent by the Bluetooth stack, which is not our uid: exported.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        undo += { app.unregisterReceiver(receiver) }
    }

    /**
     * Copies logcat to the files until stopped (null) or until it gives up (why). Lines are flushed only
     * once logcat has nothing more buffered, never synced one by one: logd silently skips lines for a
     * reader that falls behind.
     */
    private fun copy(s: Session, dir: File): String? {
        var tail = resumePoint(dir)
        var out = open(s, dir)
        var lastLine: String? = null
        var filterFailed = false
        val budget = RestartBudget()
        while (true) {
            val check = tail?.let { DevLogFiles.resume(it, System.currentTimeMillis(), ZoneId.systemDefault()) }
            if (check != null && check != DevLogFiles.Resume.FROM_TAIL) {
                val why = if (check == DevLogFiles.Resume.AHEAD) "is ahead of the clock" else "has no readable time"
                Log.i(TAG, "developer log: the last line written ($tail) $why, reading all logd holds")
            }
            val resume = tail.takeIf { check == DevLogFiles.Resume.FROM_TAIL }
            val p = try {
                ProcessBuilder(command(resume)).redirectErrorStream(true).start()
            } catch (e: IOException) {
                return "developer log stopped: ${e.message}"
            }
            val adopted = synchronized(lock) {
                if (!s.stopping) s.process = p
                !s.stopping
            }
            if (!adopted) {
                p.destroy()
                return null
            }
            try {
                val reader = p.inputStream.bufferedReader()
                while (true) {
                    val line = try { reader.readLine() } catch (_: IOException) { null } ?: break
                    lastLine = line
                    if (s.stale) refresh(s)
                    val text = if (!s.privacy) line else redactLine(line, { checkNotNull(s.redactor).redact(it) }) { e ->
                        if (!filterFailed) {
                            filterFailed = true
                            DiagLog.add("developer log: lines the privacy filter failed on are not written (${e.javaClass.simpleName})")
                        }
                    }
                    out.writer.write(text)
                    out.writer.write("\n")
                    if (!ready(reader)) out.writer.flush()
                    if (out.full) {
                        out.writer.close()
                        out = open(s, dir)
                    }
                }
                out.writer.flush()
            } finally {
                synchronized(lock) { s.process = null }
                p.destroy()
                if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
            }
            if (s.stopping) return null
            if (!budget.allow(SystemClock.elapsedRealtime())) return "developer log stopped: logcat keeps exiting"
            Log.i(TAG, "developer log: logcat exited (${runCatching { p.exitValue() }.getOrNull()}), restarting")
            if (s.awaitStop(RESTART_DELAY_MS)) return null
            tail = lastLine?.let(DevLogFiles::tailArg) ?: resumePoint(dir)
        }
    }

    private fun ready(reader: BufferedReader): Boolean = try { reader.ready() } catch (_: IOException) { false }

    private fun command(tail: String?): List<String> =
        listOf("logcat", "-v", "threadtime", "-v", "year", "-b", "main", "-b", "system", "-b", "crash") +
            if (tail == null) emptyList() else listOf("-T", tail)

    private fun resumePoint(dir: File): String? =
        logFiles(dir).sortedByDescending { it.name }.firstNotNullOfOrNull { f ->
            runCatching {
                RandomAccessFile(f, "r").use { raf ->
                    val length = raf.length()
                    val from = maxOf(0L, length - TAIL_BYTES)
                    val bytes = ByteArray((length - from).toInt())
                    raf.seek(from)
                    raf.readFully(bytes)
                    lastTailArg(String(bytes, Charsets.UTF_8), wholeFile = from == 0L)
                }
            }.getOrNull()
        }

    /** Reads the known values again, on the writer thread (the Bluetooth call is a binder call). */
    private fun refresh(s: Session) {
        s.stale = false
        val (known, bluetoothNote) = knownValues(s.app)
        if (bluetoothNote != null && bluetoothNote != s.headerNote && !s.noteLogged) {
            s.noteLogged = true
            Log.w(TAG, "developer log: Bluetooth device names not available: $bluetoothNote")
        }
        synchronized(lock) {
            s.known = s.known + known
            s.rebuild()
        }
    }

    /** A new file: the caps applied first, then the header. */
    private fun open(s: Session, dir: File): Output {
        val sizes = dir.listFiles().orEmpty().filter { it.isFile }.map { it.name to it.length() }
        val doomed = DevLogFiles.toDelete(sizes)
        val deleted = doomed.count { File(dir, it).delete() }
        s.stale = false
        val (known, bluetoothNote) = if (s.privacy) knownValues(s.app) else emptyMap<String, String>() to null
        s.headerNote = bluetoothNote
        synchronized(lock) {
            s.known = s.known + known
            s.rebuild()
        }
        val name = DevLogFiles.newFileName(System.currentTimeMillis(), ZoneId.systemDefault(), raw = !s.privacy) { File(dir, it).exists() }
        val out = Output(File(dir, name))
        s.out = out
        out.writer.write(buildString {
            append("# AARE ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("# Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("# privacy filter: ${if (s.privacy) "on" else "off"}\n")
            if (deleted > 0) {
                append("# deleted $deleted older file(s) to stay under ${DevLogFiles.MAX_TOTAL_BYTES / (1024 * 1024)} MB")
                append(" and ${DevLogFiles.MAX_FILES} files\n")
            }
            if (deleted < doomed.size) append("# could not delete ${doomed.size - deleted} older file(s)\n")
            if (bluetoothNote != null) append("# Bluetooth device names not available: $bluetoothNote\n")
        })
        out.writer.flush()
        return out
    }

    private fun bluetoothAllowed(app: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * The values to mask wherever they appear, and why the Bluetooth names are missing, if they are.
     * Bonded devices read as none while Bluetooth is off: names in the lines replayed then (logd's
     * backlog at a start, a `-T` replay) are not masked unless this session read them earlier.
     */
    @SuppressLint("MissingPermission") // bluetoothAllowed() first
    private fun knownValues(app: Context): Pair<Map<String, String>, String?> {
        val known = HashMap<String, String>()
        var bluetoothNote: String? = null
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (!bluetoothAllowed(app)) {
            bluetoothNote = "permission not granted"
        } else if (adapter != null) {
            try {
                if (adapter.state != BluetoothAdapter.STATE_ON) bluetoothNote = "Bluetooth off"
                else adapter.bondedDevices.orEmpty().forEach { d -> d.name?.let { known[it] = "<name>" } }
            } catch (e: Exception) {
                // the class only: the header is not filtered
                bluetoothNote = e.javaClass.simpleName
            }
        }
        Settings(app).knownCars.keys.forEach { known[it] = "<name>" }
        // Bluetooth-address keys are masked by the MAC rule, and "tcp" is not personal.
        for (key in PinnedHeadUnits(app).all.keys) {
            if (key.startsWith("usb:")) key.split('|', limit = 3).getOrNull(2)?.let { known[it] = "<serial>" }
        }
        return known to bluetoothNote
    }
}

/** At most [max] logcat restarts in any [windowMs]: a logcat that keeps exiting must not become a busy loop. */
internal class RestartBudget(private val max: Int = 5, private val windowMs: Long = 10 * 60_000L) {
    private val times = ArrayDeque<Long>()

    fun allow(nowMs: Long): Boolean {
        while (times.isNotEmpty() && nowMs - times.first() >= windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(nowMs)
        return true
    }
}
