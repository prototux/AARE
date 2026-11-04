package net.prototux.aare.host

import net.prototux.aare.Settings
import net.prototux.aare.notify.CarNotificationListener
import net.prototux.aare.notify.Conversation
import net.prototux.aare.ui.car.CarAppKind
import net.prototux.aare.ui.car.CarScreen
import net.prototux.aare.ui.car.PhoneStatusInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a voice assistant may do with the car: the tools its LLM can call while it runs on AARE
 * (see CarControlProvider). Specs use the function-calling JSON schema: name, description,
 * parameters, plus "confirm" and "silent" flags in the style of Lapin's device tools.
 */
object CarTools {

    fun specs(): JSONArray = JSONArray().apply {
        put(tool("car_status", "What the car screen shows now: driving or parked, day or night, the app on screen, navigation " +
            "(next turn, distance, arrival), what is playing, the phone's battery and signal, and what the car's sensors report " +
            "(\"vehicle\": speed, fuel or battery and range, odometer, outside temperature, open doors, lights, tyre pressures, warnings; " +
            "only what this car provides). Use it before answering questions about the trip or the car."))
        put(tool("car_navigate", "Start navigation to a place in the car's navigation app, shown on the car screen.",
            param("destination", "string", "address, place name or category as the user said it") to true))
        put(tool("car_media", "Control the music or podcast playing in the car.",
            param("action", "string", "play, pause, next or previous", listOf("play", "pause", "next", "previous")) to true, silent = true))
        put(tool("car_open", "Show something on the car screen: an app by its name (\"CoMaps\", \"Spotify\", \"WhatsApp\"…), " +
            "\"home\" (map and media), \"apps\" (all apps) or \"phone screen\" (the phone's own screen).",
            param("what", "string", "app name, home, apps or phone screen") to true, silent = true))
        put(tool("car_messages", "Read the latest messages received on the phone (WhatsApp, Signal, SMS…), newest first, to read them out to the driver.",
            param("app", "string", "only this messaging app, if the user named one") to false))
        put(tool("car_reply", "Reply to a conversation from car_messages (WhatsApp, Signal, SMS…), with the user's words. " +
            "Read the message back first: the user confirms before it is sent.",
            param("conversation", "string", "the conversation's name, as car_messages gives it (a contact or a group)") to true,
            param("message", "string", "the reply, as the user wants it sent") to true,
            confirm = true, summary = "reply “{message}” to {conversation}"))
        put(tool("car_mark_read", "Mark a conversation from car_messages as read.",
            param("conversation", "string", "the conversation's name, as car_messages gives it") to true, silent = true))
        put(tool("car_display", "Switch the car screen to dark or light colors, or back to the car's own day/night setting.",
            param("mode", "string", "dark, light or auto", listOf("dark", "light", "auto")) to true, silent = true))
    }

    /** Runs [name] on [runtime] (main thread). */
    fun run(runtime: CarRuntime, name: String, args: JSONObject): JSONObject = when (name) {
        "car_status" -> status(runtime)
        "car_navigate" -> navigate(runtime, args.optString("destination"))
        "car_media" -> media(runtime, args.optString("action"))
        "car_open" -> open(runtime, args.optString("what"))
        "car_messages" -> messages(runtime, args.optString("app"))
        "car_reply" -> reply(runtime, args.optString("conversation"), args.optString("message"))
        "car_mark_read" -> markRead(args.optString("conversation"))
        "car_display" -> display(runtime, args.optString("mode"))
        else -> error("unknown car tool $name")
    }

    private fun status(rt: CarRuntime): JSONObject {
        val ui = rt.ui
        val o = JSONObject()
            .put("car", ui.headUnitName)
            .put("driving", ui.drivingRestricted)
            .put("night", ui.night)
            .put("on_screen", when (val s = ui.screen) {
                CarScreen.Dashboard -> "home"
                CarScreen.Launcher -> "app list"
                is CarScreen.App -> ui.app(s.appId)?.label ?: "an app"
            })
        ui.phoneGuidance?.let { g ->
            o.put("navigation", JSONObject().put("app", g.appLabel).put("next", listOf(g.primary, g.secondary).filter { it.isNotBlank() }.joinToString(", "))
                .put("trip", g.detail))
        } ?: ui.app(ui.activeNavAppId)?.let { o.put("navigation_app", it.label) }
        ui.nowPlaying?.let { np ->
            o.put("now_playing", JSONObject().put("title", np.title).put("artist", np.subtitle).put("playing", np.playing)
                .put("app", ui.app(np.appId)?.label ?: ""))
        }
        runCatching { PhoneStatusInfo.read(rt.context) }.getOrNull()?.let { p ->
            o.put("phone", JSONObject().put("battery_percent", p.battery).put("charging", p.charging).put("signal_bars", p.signal ?: 0))
        }
        VehicleStatus.json(net.prototux.aare.car.CarData.state.value)?.let { o.put("vehicle", it) }
        return o
    }

    private fun navigate(rt: CarRuntime, destination: String): JSONObject {
        if (destination.isBlank()) return error("no destination")
        // The Car App Library's navigation intent: a geo: query the app resolves itself.
        if (!rt.navigateTo(destination)) return error("no navigation app in the car; the phone's own navigation app can be used instead")
        val nav = rt.ui.app(rt.ui.activeNavAppId)?.label ?: "the navigation app"
        return ok("searching for \"$destination\" in $nav on the car screen; the driver picks the result if there are several")
    }

    private fun media(rt: CarRuntime, action: String): JSONObject {
        val playing = rt.ui.nowPlaying?.playing == true
        when (action) {
            "play" -> if (!playing) rt.media.playPause()
            "pause" -> if (playing) rt.media.playPause()
            "next" -> rt.media.next()
            "previous" -> rt.media.previous()
            else -> return error("unknown action $action")
        }
        return ok(action)
    }

    private fun open(rt: CarRuntime, what: String): JSONObject {
        val w = what.trim().lowercase()
        val ui = rt.ui
        when (w) {
            "home", "dashboard", "map" -> { ui.screen = CarScreen.Dashboard; return ok("home") }
            "apps", "app list", "launcher" -> { ui.screen = CarScreen.Launcher; return ok("app list") }
        }
        // "Exit to car" isn't an app: a loose match on "car" mustn't take the screen away.
        val apps = ui.apps.filter { it.kind != CarAppKind.EXIT }
        val app = apps.firstOrNull { it.label.equals(what.trim(), ignoreCase = true) }
            ?: apps.firstOrNull { it.label.lowercase().contains(w) || w.contains(it.label.lowercase()) }
            ?: return error("no app called \"$what\" in the car; apps: " + apps.joinToString { it.label })
        rt.actions.openApp(app)
        return ok("${app.label} is on the car screen")
    }

    private fun messages(rt: CarRuntime, app: String): JSONObject {
        val listener = CarNotificationListener.instance ?: return noListener(rt.context)
        val list = listener.conversations()
        val filtered = if (app.isBlank()) list else list.filter { label(it.packageName).contains(app, ignoreCase = true) }
        val out = JSONArray()
        filtered.take(8).forEach { c ->
            out.put(JSONObject().put("app", label(c.packageName)).put("conversation", c.title).put("can_reply", c.canReply)
                .put("messages", JSONArray(c.messages.takeLast(5).map { m -> if (m.sender.isNotBlank()) "${m.sender}: ${m.text}" else m.text })))
        }
        return JSONObject().put("conversations", out).put("count", filtered.size)
    }

    /**
     * The items whose title matches [name]: the exact matches if there are any, else the titles that contain it.
     * Blank names and blank titles never match, and a title is never matched by being part of the name ("Al" in
     * "Alice"): a reply must not go to a conversation the driver didn't name.
     */
    internal fun <T> matches(items: List<T>, name: String, title: (T) -> String): List<T> {
        val n = name.trim()
        if (n.isEmpty()) return emptyList()
        val named = items.filter { title(it).isNotBlank() }
        return named.filter { title(it).trim().equals(n, ignoreCase = true) }.ifEmpty { named.filter { title(it).contains(n, ignoreCase = true) } }
    }

    /**
     * Runs [use] on the one conversation called [name], or gives back the error. The assistant's confirmation shows
     * the name the LLM sent, so anything but a single match is refused, and the result names the conversation used.
     */
    private fun withConversation(name: String, where: String, use: (Conversation) -> JSONObject): JSONObject {
        if (name.isBlank()) return error("which conversation?")
        val found = matches(CarNotificationListener.instance?.conversations().orEmpty(), name) { it.title }
        return when (found.size) {
            1 -> use(found[0])
            0 -> error("no conversation called \"$name\"$where")
            else -> error("several conversations match \"$name\", ask which one: " + found.joinToString { "${it.title} (${label(it.packageName)})" })
        }
    }

    private fun reply(rt: CarRuntime, name: String, message: String): JSONObject {
        val listener = CarNotificationListener.instance ?: return if (CarNotificationListener.accessGranted(rt.context)) {
            CarNotificationListener.rebind(rt.context)
            error("the messages aren't available yet, try again in a moment")
        } else error("AARE has no notification access on the phone")
        if (message.isBlank()) return error("empty message")
        return withConversation(name, " among the unread messages") { c ->
            when {
                !c.canReply -> error("${c.title}'s app doesn't allow replies from notifications")
                listener.reply(c, message.trim()) -> resolved(ok("sent to ${c.title}"), c)
                else -> error("the app refused the reply")
            }
        }
    }

    private fun markRead(name: String): JSONObject = withConversation(name, "") { c ->
        val pi = c.markRead ?: return@withConversation error("${c.title}'s app has no mark-as-read action")
        if (runCatching { pi.send() }.isSuccess) resolved(ok("marked as read"), c) else error("the app refused")
    }

    /** Adds the conversation actually used to a result. */
    private fun resolved(result: JSONObject, c: Conversation) = result.put("conversation", c.title).put("app", label(c.packageName))

    /** An app's name for the assistant, or its package if unknown. */
    private fun label(pkg: String): String {
        val pm = CarNotificationListener.instance?.packageManager ?: return pkg
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun display(rt: CarRuntime, mode: String): JSONObject {
        val theme = when (mode) {
            "dark" -> Settings.THEME_DARK
            "light" -> Settings.THEME_LIGHT
            "auto" -> Settings.THEME_AUTO
            else -> return error("unknown mode $mode")
        }
        Settings(rt.context).carTheme = theme
        return ok(mode)
    }

    /** No listener bound: either no access at all, or Android hasn't (re)bound it yet. */
    private fun noListener(context: android.content.Context): JSONObject =
        if (CarNotificationListener.accessGranted(context)) {
            CarNotificationListener.rebind(context)
            JSONObject().put("conversations", JSONArray()).put("count", 0)
        } else error("AARE has no notification access on the phone")

    private fun ok(what: String) = JSONObject().put("ok", true).put("result", what)
    private fun error(what: String) = JSONObject().put("ok", false).put("error", what)

    private class Param(val name: String, val json: JSONObject)

    private fun param(name: String, type: String, description: String, enum: List<String>? = null) =
        Param(name, JSONObject().put("type", type).put("description", description).apply { enum?.let { put("enum", JSONArray(it)) } })

    private fun tool(name: String, description: String, vararg params: Pair<Param, Boolean>, silent: Boolean = false,
                     confirm: Boolean = false, summary: String = ""): JSONObject {
        val props = JSONObject()
        params.forEach { (p, _) -> props.put(p.name, p.json) }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(params.filter { it.second }.map { it.first.name })))
            .put("confirm", confirm)
            .put("silent", silent)
            .apply { if (summary.isNotEmpty()) put("summary", summary) }
    }
}
