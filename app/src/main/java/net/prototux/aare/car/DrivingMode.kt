package net.prototux.aare.car

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import net.prototux.aare.DiagLog
import net.prototux.aare.Settings
import net.prototux.aare.notify.CarNotificationListener

/**
 * What changes on the phone while it drives the car, like Android Auto's driving settings: Do Not
 * Disturb (calls and priority conversations still come through), and an automatic reply to people
 * who write, once per conversation per drive.
 */
class DrivingMode(context: Context, private val settings: Settings) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val prefs = state(context)
    private var previousFilter: Int? = null
    private val replied = HashSet<String>()

    fun start() {
        restoreLeftOver(nm, prefs)
        if (!settings.dndWhileDriving || !nm.isNotificationPolicyAccessGranted) return
        val current = nm.currentInterruptionFilter
        // Already quieter than "priority only": leave it.
        if (current != NotificationManager.INTERRUPTION_FILTER_ALL) return
        previousFilter = current
        // Saved first (commit, not apply): if the app dies mid-drive, the next start undoes it.
        prefs.edit().putInt(KEY_PREVIOUS_FILTER, current).commit()
        runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
            .onSuccess { DiagLog.add("driving: Do Not Disturb on") }
            .onFailure { previousFilter = null; prefs.edit().remove(KEY_PREVIOUS_FILTER).apply() }
    }

    fun stop() {
        previousFilter?.let { f -> runCatching { if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(f) } }
        if (previousFilter != null) prefs.edit().remove(KEY_PREVIOUS_FILTER).apply()
        previousFilter = null
        replied.clear()
    }

    /** A message arrived (notification key [key]); answers it if auto-reply is on. Main thread. */
    fun onMessage(key: String, group: Boolean) {
        if (!settings.autoReply || group) return
        val listener = CarNotificationListener.instance ?: return
        val c = listener.conversations().firstOrNull { it.key == key } ?: return
        if (!c.canReply || !replied.add(c.packageName + "/" + c.title)) return
        if (listener.reply(c, settings.autoReplyText)) DiagLog.add("driving: auto-replied to ${c.title}")
    }

    companion object {
        private const val KEY_PREVIOUS_FILTER = "previous_interruption_filter"

        fun accessGranted(context: Context) = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

        /**
         * Undoes a Do Not Disturb left on by a drive that never ended (the app was killed or
         * crashed mid-drive). Call it when the app or service starts, never while a session runs.
         */
        fun recover(context: Context) = restoreLeftOver(context.getSystemService(NotificationManager::class.java), state(context))

        private fun state(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences("driving_mode", Context.MODE_PRIVATE)

        private fun restoreLeftOver(nm: NotificationManager, prefs: SharedPreferences) {
            if (!prefs.contains(KEY_PREVIOUS_FILTER)) return
            val f = prefs.getInt(KEY_PREVIOUS_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
            // Only if it's still the "priority only" we set: the user may have changed it since.
            runCatching {
                if (nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                    nm.setInterruptionFilter(f)
                    DiagLog.add("driving: Do Not Disturb left on by an earlier drive, now off")
                }
            }
            prefs.edit().remove(KEY_PREVIOUS_FILTER).apply()
        }
    }
}
