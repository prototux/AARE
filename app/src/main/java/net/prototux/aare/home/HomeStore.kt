package net.prototux.aare.home

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import net.prototux.aare.Settings

/**
 * Where [HomeConfig] is kept: the actions and tiles with AARE's settings ([Settings.homeActions]),
 * the broker password and header values in a private file of their own. Keeping them out of the
 * main settings file means nothing that copies that file (a settings export) can carry them.
 */
class HomeStore(context: Context) {
    private val settings = Settings(context)
    private val secrets: SharedPreferences = context.applicationContext.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)

    fun load(): HomeConfig = HomeJson.decode(settings.homeActions, secrets.getString(SECRETS_KEY, null))

    fun save(config: HomeConfig) {
        val (public, secret) = HomeJson.encode(config)
        // The secrets first: a reader woken by the settings change finds them already there
        // (apply() updates the in-memory values at once).
        secrets.edit { putString(SECRETS_KEY, secret) }
        settings.homeActions = public
    }

    /**
     * [onChange] runs (on the main thread) when either part changes. Keep the returned listener:
     * SharedPreferences only holds it weakly.
     */
    fun listen(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Settings.HOME_ACTIONS_KEY || key == SECRETS_KEY) onChange()
        }
        settings.registerListener(l)
        secrets.registerOnSharedPreferenceChangeListener(l)
        return l
    }

    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        settings.unregisterListener(l)
        secrets.unregisterOnSharedPreferenceChangeListener(l)
    }

    companion object {
        const val SECRETS_FILE = "home_secrets"
        private const val SECRETS_KEY = "secrets"
    }
}
