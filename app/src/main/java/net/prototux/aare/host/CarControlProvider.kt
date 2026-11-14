package net.prototux.aare.host

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import net.prototux.aare.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lets the voice assistant act on the car (Lapin and any assistant that adopts it), through
 * `ContentResolver.call` on `content://net.prototux.aare.car`:
 *
 * - `call("tools")` returns `{"json": <JSON array of tool specs>}`, empty when no car is connected;
 * - `call("run", <tool name>, {"args": <JSON object>})` returns `{"result": <JSON object>}`.
 *
 * Only the app chosen as the car's voice assistant (or else the phone's default digital assistant
 * setting, never a guess) may call it.
 */
class CarControlProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        val caller = callingPackage
        val allowed = Assistants.authorized(context, Settings(context).assistantPackage)
        if (caller == null || caller != allowed) throw SecurityException("Only the car's voice assistant (${allowed ?: "none chosen"}) may control the car")
        return when (method) {
            "tools" -> Bundle().apply { putString("json", onMain { if (CarRuntime.active != null) CarTools.specs() else JSONArray() }.toString()) }
            "run" -> {
                val name = arg ?: return null
                val args = runCatching { JSONObject(extras?.getString("args") ?: "{}") }.getOrDefault(JSONObject())
                val result = onMain {
                    val rt = CarRuntime.active ?: return@onMain JSONObject().put("ok", false).put("error", "the car isn't connected")
                    CarTools.run(rt, name, args)
                }
                Bundle().apply { putString("result", result.toString()) }
            }
            else -> null
        }
    }

    /** The car runtime lives on the main thread; provider calls arrive on binder threads. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post { result = runCatching(block); done.countDown() }
        if (!done.await(10, TimeUnit.SECONDS)) throw IllegalStateException("the car screen is busy")
        return result!!.getOrThrow()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
