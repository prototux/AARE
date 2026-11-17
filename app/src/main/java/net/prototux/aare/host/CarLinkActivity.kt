package net.prototux.aare.host

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.notify.CarNotificationListener
import org.json.JSONObject

/** Runs a car link (see CarLinks) tapped in the assistant's reply, without showing anything itself. */
class CarLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app that opened the link; Android fills it in for links opened from another app.
        val caller = referrer?.takeIf { it.scheme == "android-app" }?.host
        intent?.data?.let { runCarLink(this, it, caller) } ?: Log.w(TAG, "no link")
        finish()
    }

    companion object {
        private const val TAG = "AareCarLink"

        /**
         * Runs the car link [uri] if [caller], the app it came from, is the assistant chosen in AARE and the car
         * is connected, and says what the driver needs to hear. Also for links AARE reads off the car screen itself.
         */
        fun runCarLink(context: Context, uri: Uri, caller: String?) {
            val link = CarLinks.parse(uri.host, uri.path.orEmpty(), uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() },
                caller, Assistants.authorized(context, Settings(context).assistantPackage))
            val rt = CarRuntime.active
            when {
                link == null -> Log.w(TAG, "not a car link from the assistant ($caller): $uri")
                rt == null -> Log.w(TAG, "the car isn't connected: $uri")
                // Anyone may send a link: a failing tool must not take the car screen down with it.
                else -> {
                    val result = runCatching { CarTools.run(rt, link.first, JSONObject(link.second), link = true) }
                        .getOrElse { JSONObject().put("ok", false).put("error", it.toString()) }
                    Log.i(TAG, "${link.first}: $result")
                    spoken(context, link.first, result, rt)?.let { CarNotificationListener.instance?.speak(it) ?: Log.w(TAG, "no voice to say: $it") }
                }
            }
        }

        /** What the driver hears: the assistant never sees a link's result, so the messages, the status or a failure. */
        private fun spoken(context: Context, tool: String, result: JSONObject, rt: CarRuntime): String? {
            if (result.has("error")) return context.getString(R.string.car_link_failed)
            return when (tool) {
                "car_messages" -> result.optJSONArray("conversations")?.let { list ->
                    (0 until list.length()).map { list.getJSONObject(it) }.joinToString(". ") { c ->
                        val msgs = c.optJSONArray("messages")
                        c.optString("conversation") + ". " + (0 until (msgs?.length() ?: 0)).joinToString(". ") { msgs!!.getString(it) }
                    }
                }?.ifBlank { null } ?: context.getString(R.string.car_link_no_messages)
                "car_status" -> listOfNotNull(
                    rt.ui.nowPlaying?.takeIf { it.playing }?.let { context.getString(R.string.car_link_playing, it.title, it.subtitle) },
                    rt.ui.phoneGuidance?.primary?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.car_link_next_turn, it) },
                    result.optJSONObject("phone")?.let { context.getString(R.string.car_link_battery, it.optInt("battery_percent")) },
                ).joinToString(" ")
                else -> null
            }
        }
    }
}
