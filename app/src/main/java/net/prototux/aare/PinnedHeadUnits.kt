package net.prototux.aare

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import net.prototux.aare.aap.PinStore

/**
 * Head unit certificates the user trusts, in a private preferences file of their own: a set of
 * SHA-256 fingerprints per head unit, keyed by Bluetooth address (wireless), USB accessory
 * (manufacturer, model, serial) or "tcp" (developer server).
 */
class PinnedHeadUnits(context: Context) : PinStore {
    private val prefs = context.applicationContext.getSharedPreferences("head_unit_pins", Context.MODE_PRIVATE)
    private val knownHeadUnits = KnownHeadUnits(context)

    override fun get(peerKey: String): Set<String> = prefs.getStringSet(peerKey, null).orEmpty()

    override fun add(peerKey: String, sha256: String) {
        prefs.edit(commit = true) { putStringSet(peerKey, get(peerKey) + sha256) }
        DiagLog.add("trusted head unit certificate for $peerKey: SHA-256 $sha256")
    }

    /** Every trusted head unit: key to fingerprints. */
    val all: Map<String, Set<String>>
        get() = prefs.all.mapNotNull { (k, v) -> (v as? Set<*>)?.let { k to it.filterIsInstance<String>().toSet() } }.toMap()

    /** Also forgets who the head unit said it was (KnownHeadUnits), when [peerKey] is its Bluetooth address. */
    fun forget(peerKey: String) {
        prefs.edit(commit = true) { remove(peerKey) }
        knownHeadUnits.forget(peerKey)
    }

    /** Calls [onChange] whenever the trusted set changes; returns the unsubscribe call. */
    fun listen(onChange: () -> Unit): () -> Unit {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(l)
        return { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
}
