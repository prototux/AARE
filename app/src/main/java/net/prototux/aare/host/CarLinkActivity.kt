package net.prototux.aare.host

import android.app.Activity
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
        val uri = intent?.data
        // The app that opened the link; Android fills it in for links opened from another app.
        val caller = referrer?.takeIf { it.scheme == "android-app" }?.host
        val link = uri?.let { u ->
            CarLinks.parse(u.host, u.path.orEmpty(), u.queryParameterNames.associateWith { u.getQueryParameter(it).orEmpty() },
                caller, Assistants.authorized(this, Settings(this).assistantPackage))
        }
        val rt = CarRuntime.active
        when {
            link == null -> Log.w(TAG, "not a car link from the assistant ($caller): $uri")
            rt == null -> Log.w(TAG, "the car isn't connected: $uri")
            // Anyone may send a link: a failing tool must not take the car screen down with it.
            else -> {
                val result = runCatching { CarTools.run(rt, link.first, JSONObject(link.second), link = true) }
                    .getOrElse { JSONObject().put("ok", false).put("error", it.toString()) }
                Log.i(TAG, "${link.first}: $result")
                spoken(link.first, result, rt)?.let { CarNotificationListener.instance?.speak(it) ?: Log.w(TAG, "no voice to say: $it") }
            }
        }
        finish()
    }

    /** What the driver hears: the assistant never sees a link's result, so the messages, the status or a failure. */
    private fun spoken(tool: String, result: JSONObject, rt: CarRuntime): String? {
        if (result.has("error")) return getString(R.string.car_link_failed)
        return when (tool) {
            "car_messages" -> result.optJSONArray("conversations")?.let { list ->
                (0 until list.length()).map { list.getJSONObject(it) }.joinToString(". ") { c ->
                    val msgs = c.optJSONArray("messages")
                    c.optString("conversation") + ". " + (0 until (msgs?.length() ?: 0)).joinToString(". ") { msgs!!.getString(it) }
                }
            }?.ifBlank { null } ?: getString(R.string.car_link_no_messages)
            "car_status" -> listOfNotNull(
                rt.ui.nowPlaying?.takeIf { it.playing }?.let { getString(R.string.car_link_playing, it.title, it.subtitle) },
                rt.ui.phoneGuidance?.primary?.takeIf { it.isNotBlank() }?.let { getString(R.string.car_link_next_turn, it) },
                result.optJSONObject("phone")?.let { getString(R.string.car_link_battery, it.optInt("battery_percent")) },
            ).joinToString(" ")
            else -> null
        }
    }

    private companion object {
        const val TAG = "AareCarLink"
    }
}
