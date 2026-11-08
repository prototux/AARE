package net.prototux.aare

import android.util.Log
import net.prototux.aare.aap.AapLog

/** Protocol logs go to logcat under "AareAap". */
object AndroidAapLog : AapLog {
    override fun d(msg: String) { Log.d("AareAap", msg) }
    override fun i(msg: String) { Log.i("AareAap", msg) }
    override fun w(msg: String, t: Throwable?) { Log.w("AareAap", msg, t) }
    override fun e(msg: String, t: Throwable?) { Log.e("AareAap", msg, t) }
}

/**
 * The car session's protocol log. Always to logcat (tag "AareAap"); when [verbose] is on, every
 * line — including the per-frame debug trace — also goes to the in-app [DiagLog], so a reporter can
 * capture the protocol trace and export it without adb. Off by default (Settings.verboseProtocolLog).
 */
object SessionAapLog : AapLog {
    @Volatile var verbose = false
    private fun diag(msg: String) { if (verbose) DiagLog.add(msg) }
    private fun with(msg: String, t: Throwable?) = if (t != null) "$msg: ${t.message ?: t}" else msg
    override fun d(msg: String) { Log.d("AareAap", msg); diag(msg) }
    override fun i(msg: String) { Log.i("AareAap", msg); diag(msg) }
    override fun w(msg: String, t: Throwable?) { Log.w("AareAap", msg, t); diag(with(msg, t)) }
    override fun e(msg: String, t: Throwable?) { Log.e("AareAap", msg, t); diag(with(msg, t)) }
}

/** Also writes to the [DiagLog] the user sees in the app (not the debug lines). */
object DiagAapLog : AapLog {
    override fun d(msg: String) { Log.d("AareAap", msg) }
    override fun i(msg: String) { DiagLog.add(msg) }
    override fun w(msg: String, t: Throwable?) { Log.w("AareAap", msg, t); DiagLog.add(if (t != null) "$msg: ${t.message ?: t}" else msg) }
    override fun e(msg: String, t: Throwable?) { Log.e("AareAap", msg, t); DiagLog.add(if (t != null) "$msg: ${t.message ?: t}" else msg) }
}
