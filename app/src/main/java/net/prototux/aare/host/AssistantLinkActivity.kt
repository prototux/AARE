package net.prototux.aare.host

import android.app.Activity
import android.os.Bundle
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.car.Calls
import org.json.JSONObject

/**
 * Exported entry point for `aare://` links in an assistant's reply (a notification action, a link
 * it opens…): maps a handful of safe actions to the car tools already offered to an in-car
 * assistant (see [CarTools], [CarControlProvider]). The format is documented in
 * docs/assistant-links.md.
 *
 * What AARE can and can't tell about the caller: for an ordinary link tap (`ACTION_VIEW`, no
 * `startActivityForResult`), [getCallingPackage] is null, and [getReferrer] is only set when the
 * caller supplied `EXTRA_REFERRER`, which notification actions and most browsers don't for a
 * custom scheme. So, like any `http://` link, any app on the phone could launch this one; AARE
 * cannot confirm it came from an assistant's reply. The defence is in what the link is allowed to
 * do: [AssistantLinks.parse] is the whole allowlist (nothing outside its four actions runs), it
 * only acts while a car session is already running (so a link can't silently start one), and a
 * call is never placed without the driver confirming it on the car screen.
 */
class AssistantLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.dataString
        val rt = CarRuntime.active
        when {
            rt == null -> DiagLog.add("assistant link ignored, no car session: $link")
            link == null -> Unit
            else -> when (val action = AssistantLinks.parse(link)) {
                is AssistantLink.Navigate -> runTool(rt, "car_navigate", JSONObject().put("destination", action.query))
                is AssistantLink.Play -> runTool(rt, "car_media", JSONObject().put("action", action.action))
                is AssistantLink.Open -> runTool(rt, "car_open", JSONObject().put("what", action.what))
                is AssistantLink.Call -> confirmCall(rt, action.number)
                null -> DiagLog.add("assistant link rejected, not on the allowlist: $link")
            }
        }
        finish()
    }

    private fun runTool(rt: CarRuntime, name: String, args: JSONObject) {
        val result = CarTools.run(rt, name, args)
        if (!result.optBoolean("ok", true)) rt.notice(getString(R.string.notif_assistant_title), result.optString("error"))
    }

    /** Never dials straight from a link: the driver taps "Call" on the car screen first. */
    private fun confirmCall(rt: CarRuntime, number: String) {
        rt.notice(
            getString(R.string.notif_call_confirm_title, number),
            getString(R.string.notif_asked_by_link),
            durationMs = 10_000,
            actions = listOf(getString(R.string.common_call) to { Calls.place(rt.context, number) }),
        )
    }
}
