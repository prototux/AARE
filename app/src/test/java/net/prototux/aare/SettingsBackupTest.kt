package net.prototux.aare

import java.io.ByteArrayInputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Export/import of AARE's settings as JSON: the pure mapping, without SharedPreferences. */
class SettingsBackupTest {
    private fun entry(type: String, value: Any) = JSONObject().put("t", type).put("v", value)
    private fun file(settings: JSONObject) = JSONObject().put("aareSettings", 1).put("settings", settings)

    @Test
    fun exportTagsAndroidPreferenceTypesRoundTrip() {
        val all = mapOf(
            "wireless" to true,
            "max_video_height" to 1080,
            "call_bar_x" to 12.5f,
            "style_theme" to "aare",
            "hidden_apps" to setOf("com.example.a", "com.example.b"),
        )
        val restored = SettingsBackup.import(SettingsBackup.export(all))
        assertEquals(all, restored.values)
        assertTrue(restored.skipped.isEmpty())
    }

    @Test
    fun intAndFloatSurviveATextRoundTripTooNotJustInMemory() {
        // Re-parsing the JSON text is what tells an Int from a Long or a Float from a Double:
        // org.json's own tokenizer picks a number type on its own.
        val json = JSONObject(SettingsBackup.export(mapOf("max_video_height" to 1080, "call_bar_x" to 12.5f)).toString())
        val restored = SettingsBackup.import(json).values
        assertEquals(1080, restored["max_video_height"])
        assertEquals(12.5f, restored["call_bar_x"])
    }

    @Test
    fun onlyAllowedKeysAreExported() {
        val all = mapOf(
            "instance_id" to "abc-123",
            "phone_bt_address" to "AA:BB:CC:DD:EE:FF",
            "known_cars" to setOf("My Car\t1700000000000"),
            "requested_permissions" to setOf("android.permission.BLUETOOTH_CONNECT"),
            "update_latest_version" to "2.0",
            "cert_index" to 1,
            "home_actions" to "[{\"url\":\"http://192.168.1.2:8123\"}]", // a later feature's server data: not allowed
            "mqtt_password" to "secret",
            "wireless" to true, // kept, to show the filtering is selective, not everything
        )
        val settings = SettingsBackup.export(all).getJSONObject("settings")
        assertEquals(listOf("wireless"), settings.keys().asSequence().toList())
    }

    @Test
    fun theDeveloperLogsSwitchesStayOutOfFiles() {
        // A file must not turn the developer log's privacy filter off on another phone.
        val all = mapOf("developer_mode" to true, "developer_log_privacy" to false, "wireless" to true)
        assertEquals(listOf("wireless"), SettingsBackup.export(all).getJSONObject("settings").keys().asSequence().toList())
        val restored = SettingsBackup.import(file(JSONObject().put("developer_log_privacy", entry("b", false)).put("wireless", entry("b", true))))
        assertEquals(mapOf("wireless" to true), restored.values)
        assertTrue(restored.blocked.isEmpty())
    }

    @Test
    fun blockedKeysAreNeverExportedNorImported() {
        val blocked = SettingsBackup.BLOCKED.associateWith { true }
        assertEquals(0, SettingsBackup.export(blocked).getJSONObject("settings").length())

        // A hand-edited file carrying them, with the right types, still doesn't switch them on.
        val tampered = JSONObject()
        for (key in SettingsBackup.BLOCKED) tampered.put(key, entry("b", true))
        tampered.put("wireless", entry("b", false))
        val restored = SettingsBackup.import(file(tampered))
        assertEquals(mapOf("wireless" to false), restored.values)
        assertEquals(SettingsBackup.BLOCKED, restored.blocked.toSet())
        assertTrue(restored.skipped.isEmpty())
    }

    @Test
    fun importLeavesTheServerStandaloneAndCertificateSwitchesUnchanged() {
        // The phone's own values before the import.
        val prefs = mutableMapOf<String, Any>("hu_server_localhost" to false, "standalone_apps" to false, "cert_auto_update" to false)
        val file = file(
            JSONObject()
                .put("hu_server_localhost", entry("b", true))
                .put("standalone_apps", entry("b", true))
                .put("cert_auto_update", entry("b", true))
                .put("wireless", entry("b", true)),
        )
        val imported = SettingsBackup.import(file)
        prefs.putAll(imported.values)
        assertEquals(false, prefs["hu_server_localhost"])
        assertEquals(false, prefs["standalone_apps"])
        assertEquals(false, prefs["cert_auto_update"])
        assertEquals(true, prefs["wireless"])
        // Reported as blocked (the import's summary names them), not as unknown.
        assertEquals(setOf("hu_server_localhost", "standalone_apps", "cert_auto_update"), imported.blocked.toSet())
        assertTrue(imported.skipped.isEmpty())
    }

    @Test
    fun theSafetyAndNetworkKeysAreBlockedAndNoneIsAllowed() {
        for (key in listOf("hu_server", "hu_server_lan", "hu_server_lan_once", "head_unit_server_mirror", "ignore_driving", "root_allowed", "root_audio", "assistant_links",
            "assistant_on_car_screen", "media_video", "car_mic_for_phone_apps", "auto_pair_car_bluetooth", "companion_wifi_shizuku", "standalone_driving_rules")) {
            assertTrue(key, key in SettingsBackup.BLOCKED)
        }
        assertTrue(SettingsBackup.BLOCKED.intersect(SettingsBackup.ALLOWED.keys).isEmpty())
    }

    @Test
    fun theRailAndScreenSettingsMove() {
        val all = mapOf<String, Any?>("rail_apps" to "phone\nmaps", "compact_rail" to true, "split_screen" to true, "calendar_agenda_count" to 3,
            "car_key_map" to setOf("5=home"))
        val restored = SettingsBackup.import(JSONObject(SettingsBackup.export(all).toString()))
        assertEquals(all, restored.values)
        assertTrue(restored.skipped.isEmpty())
    }

    @Test
    fun unknownKeysAreSkipped() {
        val restored = SettingsBackup.import(
            file(JSONObject().put("instance_id", entry("s", "not-mine")).put("no_such_setting", entry("b", true)).put("wireless", entry("b", true))),
        )
        assertEquals(mapOf("wireless" to true), restored.values)
        assertEquals(setOf("instance_id", "no_such_setting"), restored.skipped.toSet())
    }

    @Test
    fun aWrongTypeIsSkippedNotWritten() {
        // Each would make Settings' getter throw ClassCastException on every launch.
        val restored = SettingsBackup.import(
            file(
                JSONObject()
                    .put("wireless", entry("i", 1)) // a boolean setting, tagged int
                    .put("max_video_height", entry("s", "1080")) // an int setting, tagged string
                    .put("car_theme", entry("s", 42)) // tagged string, but holding a number
                    .put("prefer_60fps", entry("b", "true")) // tagged boolean, but holding a string
                    .put("call_bar_x", entry("f", "1.5"))
                    .put("hidden_apps", entry("set", JSONArray().put("com.example.a").put(7))) // one non-string item
                    .put("dark_bar", "true") // not tagged at all
                    .put("show_weather", entry("b", false)), // the one valid entry
            ),
        )
        assertEquals(mapOf("show_weather" to false), restored.values)
        assertEquals(7, restored.skipped.size)
    }

    @Test
    fun anIntSettingRefusesAFractionOrAnOutOfRangeNumber() {
        assertTrue(SettingsBackup.import(file(JSONObject().put("max_video_height", entry("i", 1080.5)))).values.isEmpty())
        assertTrue(SettingsBackup.import(file(JSONObject().put("max_video_height", entry("i", 5_000_000_000L)))).values.isEmpty())
    }

    @Test
    fun aCustomWallpaperIsLeftOutSinceThePhotoIsNotInTheFile() {
        val restored = SettingsBackup.import(SettingsBackup.export(mapOf("wallpaper" to Settings.WALLPAPER_CUSTOM)))
        assertFalse(restored.values.containsKey("wallpaper"))
        assertEquals(listOf("wallpaper"), restored.skipped)
    }

    @Test
    fun anUnsupportedFormatVersionIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.import(JSONObject().put("aareSettings", 99)) }
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.import(JSONObject()) }
    }

    @Test
    fun readLimitedRefusesAFileOverTheLimit() {
        val small = ByteArray(100) { it.toByte() }
        assertArrayEquals(small, SettingsBackup.readLimited(ByteArrayInputStream(small), limit = 100))
        assertThrows(IllegalArgumentException::class.java) { SettingsBackup.readLimited(ByteArrayInputStream(ByteArray(101)), limit = 100) }
    }

    /**
     * Every key Settings.kt reads or writes has a backup decision: [SettingsBackup.ALLOWED],
     * [SettingsBackup.BLOCKED] or [SettingsBackup.NOT_BACKED_UP]. A new setting fails here until
     * it's put in one, so it can't be left out of the backup by accident. Reads the source text
     * (relative to the app module), so a key built at runtime isn't seen.
     */
    @Test
    fun everySettingsKeyHasABackupDecision() {
        val source = File("src/main/java/net/prototux/aare/Settings.kt").readText()
        val literal = Regex("""\b(?:get|put)(?:Boolean|String|Int|Long|Float|StringSet)\(\s*"([a-z0-9_]+)"|\b(?:remove|contains)\(\s*"([a-z0-9_]+)"|const val \w+_KEY = "([a-z0-9_]+)"""")
        val keys = literal.findAll(source).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }.toSet()
        assertTrue("found no keys: has Settings.kt moved?", keys.size > 50)
        val decided = SettingsBackup.ALLOWED.keys + SettingsBackup.BLOCKED + SettingsBackup.NOT_BACKED_UP
        assertEquals("keys with no backup decision: add each to ALLOWED, BLOCKED or NOT_BACKED_UP in SettingsBackup.kt",
            emptySet<String>(), keys - decided)
    }

    @Test
    fun theThreeBackupListsNeverOverlap() {
        val allowed = SettingsBackup.ALLOWED.keys
        assertEquals(emptySet<String>(), allowed intersect SettingsBackup.NOT_BACKED_UP)
        assertEquals(emptySet<String>(), SettingsBackup.BLOCKED intersect SettingsBackup.NOT_BACKED_UP)
    }

    @Test
    fun aKeyThatIsNotBackedUpIsNeitherExportedNorApplied() {
        val all = SettingsBackup.NOT_BACKED_UP.associateWith { "x" }
        assertEquals(0, SettingsBackup.export(all).getJSONObject("settings").length())
        val tampered = JSONObject()
        for (key in SettingsBackup.NOT_BACKED_UP) tampered.put(key, entry("s", "x"))
        assertTrue(SettingsBackup.import(file(tampered)).values.isEmpty())
    }
}
