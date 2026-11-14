package net.prototux.aare

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The connection log shown in the app. Kept on disk, so what happened in the car (a wireless
 * attempt, a refused certificate…) can still be read on the phone afterwards. Thread-safe.
 */
object DiagLog {
    private const val TAG = "Aare"
    // Enough for a whole drive's session start and failure with the verbose protocol log on
    // (which used to fill 300 lines with pings in two and a half minutes, #96).
    private const val MAX_LINES = 2000
    /** The file is appended to, and cut back to [MAX_LINES] when it outgrows this. */
    private const val MAX_FILE_BYTES = 512 * 1024

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private var file: File? = null

    /** Loads the saved log. Called by [AareApp]; lines added before go to logcat only. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "connection.log")
        file = f
        _lines.value = runCatching { f.readLines() }.getOrDefault(emptyList()).takeLast(MAX_LINES)
    }

    @Synchronized
    fun add(line: String) {
        Log.i(TAG, line)
        val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
        val all = (_lines.value + "$stamp  $line").takeLast(MAX_LINES)
        _lines.value = all
        val f = file ?: return
        runCatching {
            // Rewrite now and then to drop old lines; append otherwise.
            if (all.size == MAX_LINES && f.length() > MAX_FILE_BYTES) f.writeText(all.joinToString("\n", postfix = "\n"))
            else f.appendText("$stamp  $line\n")
        }
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        file?.let { runCatching { it.writeText("") } }
    }
}
