package net.prototux.aare.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeJsonTest {
    private val config = HomeConfig(
        enabled = true,
        mqtt = MqttBroker("broker.lan", 8883, tls = true, username = "car", password = "hunter22", clientId = "aare-1"),
        http = HttpServer("https://ha.lan", listOf(Header("Authorization", "Bearer tok-1")), allowLanHttp = true),
        actions = listOf(
            HomeAction("a1", "Garage", Send.Http("POST", "/api/services/cover/toggle", listOf(Header("X-Key", "k-2")), """{"entity_id":"cover.g"}"""),
                confirm = true, arrival = Arrival(48.1, 2.2, 200, enabled = true)),
            HomeAction("a2", "Lights", Send.Mqtt("home/light/set", "ON", qos = 1, retain = true), onCard = false),
        ),
        tiles = listOf(
            HomeTile("t1", "Garage", Source.Mqtt("home/garage/state", "$.state")),
            HomeTile("t2", "Temp", Source.Http("/api/states/sensor.t", listOf(Header("X-Tile", "k-3")), "$.state", 120), "°C"),
        ),
    )

    @Test
    fun roundTripsThroughBothParts() {
        val (pub, sec) = HomeJson.encode(config)
        assertEquals(config, HomeJson.decode(pub, sec))
        assertEquals(HomeJson.VERSION, JSONObject(pub).getInt("version"))
        assertEquals(HomeJson.VERSION, JSONObject(sec).getInt("version"))
    }

    @Test
    fun theSettingsPartHoldsNoSecret() {
        val (pub, sec) = HomeJson.encode(config)
        for (secret in listOf("hunter22", "tok-1", "k-2", "k-3")) {
            assertFalse("$secret in the settings part", secret in pub)
            assertTrue("$secret missing from the secrets", secret in sec)
        }
    }

    @Test
    fun withoutTheSecretsTheRestStillLoads() {
        // A settings file imported on another phone: no password, no header values.
        val back = HomeJson.decode(HomeJson.encode(config).first, null)
        assertEquals("", back.mqtt.password)
        assertTrue(back.http.headers.isEmpty())
        assertEquals(config.actions.map { it.id }, back.actions.map { it.id })
        assertEquals("/api/services/cover/toggle", (back.actions[0].send as Send.Http).url)
    }

    @Test
    fun brokenOrMissingJsonGivesTheDefault() {
        assertEquals(HomeConfig(), HomeJson.decode(null, null))
        assertEquals(HomeConfig(), HomeJson.decode("{not json", "{}"))
    }

    @Test
    fun unknownKindsFromANewerVersionAreSkipped() {
        val pub = """{"version":2,"enabled":true,"actions":[{"id":"x","type":"zigbee"},{"id":"y","name":"Y","type":"mqtt","topic":"t"}],"tiles":[{"type":"mqtt"}]}"""
        val c = HomeJson.decode(pub, null)
        assertEquals(listOf("y"), c.actions.map { it.id })
        assertTrue(c.tiles.isEmpty()) // no id
        // Saved before the option existed: plain http stays off.
        assertFalse(c.http.allowLanHttp)
    }
}
