package net.prototux.aare

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import net.prototux.aare.car.calibration.TouchCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The touch calibration is kept per head unit (its address), and forgetting the car forgets it. */
class KnownHeadUnitsCalibrationTest {
    private val prefs = MemoryPrefs()
    private val context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }
    private val known = KnownHeadUnits(context)
    private val cal = TouchCalibration(0.0005, 0.0, 0.01, 0.0, 0.001, 0.17, 0.0, 0.2, 0.0, 0.2)

    @Test fun twoHeadUnitsWithTheSameNameKeepTheirOwn() {
        known.setCalibration("00:14:09:A6:C1:A7", cal, "Citroen")
        assertEquals(cal, known.calibration("00:14:09:a6:c1:a7"))
        assertNull(known.calibration("00:14:09:A6:C1:B8"))
        assertEquals(mapOf("00:14:09:A6:C1:A7" to "Citroen"), known.calibrated())
    }

    @Test fun forgettingTheCarForgetsItsCalibration() {
        known.setCalibration("00:14:09:A6:C1:A7", cal, "Citroen")
        known.setCalibration("AA:BB:CC:DD:EE:FF", cal, "Peugeot")
        known.forgetCar("Citroen")
        assertNull(known.calibration("00:14:09:A6:C1:A7"))
        assertEquals(cal, known.calibration("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun resettingRemovesOnlyTheCalibration() {
        known.name("00:14:09:A6:C1:A7", "Citroen")
        known.setCalibration("00:14:09:A6:C1:A7", cal, "Citroen")
        known.setCalibration("00:14:09:A6:C1:A7", null, "Citroen")
        assertNull(known.calibration("00:14:09:A6:C1:A7"))
        assertEquals("Citroen", known.carName("00:14:09:A6:C1:A7"))
        assertEquals(emptyMap<String, String>(), known.calibrated())
    }

    /** SharedPreferences in memory: unit tests only see the platform's stubs. */
    private class MemoryPrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = map.toMap()
        override fun getString(key: String, def: String?) = map[key] as String? ?: def
        override fun getStringSet(key: String, def: Set<String>?) = @Suppress("UNCHECKED_CAST") (map[key] as Set<String>? ?: def)
        override fun getInt(key: String, def: Int) = map[key] as Int? ?: def
        override fun getLong(key: String, def: Long) = map[key] as Long? ?: def
        override fun getFloat(key: String, def: Float) = map[key] as Float? ?: def
        override fun getBoolean(key: String, def: Boolean) = map[key] as Boolean? ?: def
        override fun contains(key: String) = key in map
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val puts = mutableMapOf<String, Any?>()
            val removes = mutableSetOf<String>()
            override fun putString(k: String, v: String?): SharedPreferences.Editor { puts[k] = v; return this }
            override fun putStringSet(k: String, v: Set<String>?): SharedPreferences.Editor { puts[k] = v; return this }
            override fun putInt(k: String, v: Int): SharedPreferences.Editor { puts[k] = v; return this }
            override fun putLong(k: String, v: Long): SharedPreferences.Editor { puts[k] = v; return this }
            override fun putFloat(k: String, v: Float): SharedPreferences.Editor { puts[k] = v; return this }
            override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor { puts[k] = v; return this }
            override fun remove(k: String): SharedPreferences.Editor { removes += k; return this }
            override fun clear(): SharedPreferences.Editor { removes += map.keys; return this }
            override fun commit(): Boolean { this.apply(); return true }
            override fun apply() { removes.forEach { map.remove(it) }; map.putAll(puts) }
        }
    }
}
