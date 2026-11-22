package net.prototux.aare.host

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.car.Calls
import org.json.JSONObject

/**
 * Entry point for `aare://` links in an assistant's reply (a notification action, a link it
 * opens…): maps a handful of safe actions to the car tools already offered to an in-car
 * assistant (see [CarTools], [CarControlProvider]). The format is documented in
 * docs/assistant-links.md.
 *
 * The activity is exported (another app has to be able to start it) but not BROWSABLE, so a web
 * page can't open it, and it acts only when the app that started it is the user's chosen
 * assistant ([AssistantLinks.callerPackage], [AssistantLinks.callerAllowed]). Then
 * [AssistantLinks.parse] is the whole allowlist, a link acts only while a car session is already
 * running (so it can't silently start one), and navigating, opening an app or calling waits for
 * the driver to confirm on the car screen; only the music controls run straight away.
 *
 * Logs say which action ran or why a link was refused, never the link itself: it can hold a
 * destination or a phone number.
 */
class AssistantLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.dataString
        val rt = CarRuntime.active
        val settings = Settings(this)
        val caller = caller()
        when {
            !settings.assistantLinks -> DiagLog.add("assistant link ignored, links are off in AARE's settings")
            !AssistantLinks.callerAllowed(caller, Assistants.authorized(this, settings.assistantPackage), packageName) ->
                DiagLog.add("assistant link ignored, opened by ${caller ?: "an unknown app"}, not the chosen assistant")
            rt == null -> DiagLog.add("assistant link ignored, no car session")
            link == null -> Unit
            else -> when (val action = AssistantLinks.parse(link)) {
                is AssistantLink.Navigate -> confirm(rt, "navigate", getString(R.string.notif_navigate_confirm_title, action.query),
                    getString(R.string.notif_action_navigate)) { runTool(rt, "car_navigate", JSONObject().put("destination", action.query)) }
                is AssistantLink.Play -> { DiagLog.add("assistant link: play"); runTool(rt, "car_media", JSONObject().put("action", action.action)) }
                is AssistantLink.Open -> confirm(rt, "open", getString(R.string.notif_open_confirm_title, action.what),
                    getString(R.string.notif_action_open)) { runTool(rt, "car_open", JSONObject().put("what", action.what)) }
                is AssistantLink.Call -> confirm(rt, "call", getString(R.string.notif_call_confirm_title, action.number),
                    getString(R.string.common_call)) { Calls.place(rt.context, action.number) }
                null -> DiagLog.add("assistant link rejected, not on the allowlist")
            }
        }
        finish()
    }

    /** The package Android says started this activity; a referrer the intent carries itself doesn't count. */
    private fun caller(): String? {
        val supplied = runCatching {
            intent?.hasExtra(Intent.EXTRA_REFERRER) == true || intent?.hasExtra(Intent.EXTRA_REFERRER_NAME) == true
        }.getOrDefault(true) // extras that don't even unparcel: trust nothing about this intent
        return AssistantLinks.callerPackage(callingPackage, referrer?.toString(), supplied)
    }

    private fun runTool(rt: CarRuntime, name: String, args: JSONObject) {
        val result = CarTools.run(rt, name, args)
        if (!result.optBoolean("ok", true)) rt.notice(getString(R.string.notif_assistant_title), result.optString("error"))
    }

    /** Never acts straight from a link: the driver taps the action on the car screen first. */
    private fun confirm(rt: CarRuntime, kind: String, title: String, button: String, act: () -> Unit) {
        DiagLog.add("assistant link: $kind, asking the driver")
        rt.notice(title, getString(R.string.notif_asked_by_link), durationMs = 10_000, actions = listOf(button to act))
    }
}
