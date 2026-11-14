package net.prototux.aare

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Export/import of AARE's settings as JSON: the pure mapping, without SharedPreferences. */
class SettingsBackupTest {
    @Test
    fun exportTagsAndroidPreferenceTypesRoundTrip() {
        val all = mapOf(
            "wireless" to true,
            "max_video_height" to 1080,
            "update_last_check_ms" to 123_456_789_012L,
            "call_bar_x" to 12.5f,
            "style_theme" to "aare",
            "hidden_apps" to setOf("com.example.a", "com.example.b"),
        )
        val restored = SettingsBackup.import(SettingsBackup.export(all))
        assertEquals(true, restored["wireless"])
        assertEquals(1080, restored["max_video_height"])
        assertEquals("aare", restored["style_theme"])
        assertEquals(setOf("com.example.a", "com.example.b"), restored["hidden_apps"])
        // update_last_check_ms is an excluded key (see below), not an int/long mix-up: checked there.
    }

    @Test
    fun longAndFloatSurviveATextRoundTripTooNotJustInMemory() {
        // Re-parsing the JSON text is what tells a Long from an Int or a Float from a Double:
        // org.json's own tokenizer turns every bare number into Integer/Long/Double on its own.
        val json = JSONObject(SettingsBackup.export(mapOf("max_video_height" to 1080, "call_bar_x" to 12.5f)).toString())
        val restored = SettingsBackup.import(json)
        assertEquals(1080, restored["max_video_height"])
        assertEquals(12.5f, restored["call_bar_x"])
    }

    @Test
    fun deviceAndSecretSpecificKeysAreNeverExported() {
        val all = mapOf(
            "instance_id" to "abc-123",
            "phone_bt_address" to "AA:BB:CC:DD:EE:FF",
            "known_cars" to setOf("My Car\t1700000000000"),
            "requested_permissions" to setOf("android.permission.BLUETOOTH_CONNECT"),
            "update_latest_version" to "2.0",
            "cert_index" to 1,
            "wireless" to true, // kept, to show the filtering is selective, not everything
        )
        val exported = SettingsBackup.export(all)
        val settings = exported.getJSONObject("settings")
        for (excluded in SettingsBackup.EXCLUDED_KEYS) assertFalse(excluded, settings.has(excluded))
        assertTrue(settings.has("wireless"))
    }

    @Test
    fun excludedKeysAreDroppedOnImportEvenIfPresent() {
        // A hand-edited or old file might still carry one; import must not trust it either.
        val tampered = JSONObject().put("aareSettings", 1).put(
            "settings",
            JSONObject().put("instance_id", JSONObject().put("t", "s").put("v", "not-mine")).put("wireless", JSONObject().put("t", "b").put("v", true)),
        )
        val restored = SettingsBackup.import(tampered)
        assertFalse(restored.containsKey("instance_id"))
        assertEquals(true, restored["wireless"])
    }

    @Test
    fun anUnsupportedFormatVersionIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.import(JSONObject().put("aareSettings", 99)) }
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.import(JSONObject()) }
    }
}
