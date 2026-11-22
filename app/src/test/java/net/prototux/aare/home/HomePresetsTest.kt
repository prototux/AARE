package net.prototux.aare.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePresetsTest {
    private var n = 0
    private val ids = { "id${++n}" }

    @Test
    fun homeAssistantServiceCallsTheEntitysDomain() {
        val c = HomePreset.HA_SERVICE.apply(HomeConfig(), mapOf(
            PresetField.NAME to "Garage", PresetField.ENTITY_ID to "cover.garage_door", PresetField.SERVICE to "open_cover",
            PresetField.BASE_URL to "https://ha.example.org/", PresetField.TOKEN to "abc123",
        ), ids)
        val send = c.actions.single().send as Send.Http
        assertEquals("POST", send.method)
        assertEquals("/api/services/cover/open_cover", send.url)
        assertEquals("cover.garage_door", JSONObject(send.body).getString("entity_id"))
        assertEquals("https://ha.example.org", c.http.baseUrl)
        assertEquals(listOf(Header("Authorization", "Bearer abc123")), c.http.headers)
    }

    @Test
    fun aBlankTokenKeepsTheSavedOneAndANewOneReplacesIt() {
        val saved = HomeConfig(http = HttpServer("https://ha", listOf(Header("authorization", "Bearer old"), Header("X-Other", "1"))))
        val kept = HomePreset.HA_STATE.apply(saved, mapOf(PresetField.ENTITY_ID to "sensor.t", PresetField.BASE_URL to "https://ha"), ids)
        assertEquals(saved.http.headers, kept.http.headers)
        val replaced = HomePreset.HA_STATE.apply(saved, mapOf(PresetField.ENTITY_ID to "sensor.t", PresetField.TOKEN to "Bearer new"), ids)
        assertEquals(listOf(Header("X-Other", "1"), Header("Authorization", "Bearer new")), replaced.http.headers)
    }

    @Test
    fun homeAssistantStateReadsTheStateField() {
        val c = HomePreset.HA_STATE.apply(HomeConfig(), mapOf(PresetField.ENTITY_ID to "sensor.garage_temp", PresetField.UNIT to "°C"), ids)
        val tile = c.tiles.single()
        assertEquals("sensor.garage_temp", tile.name) // no name given: the entity
        assertEquals(Source.Http("/api/states/sensor.garage_temp", jsonPath = "$.state"), tile.source)
        assertEquals("°C", tile.unit)
    }

    @Test
    fun anEntityWithoutDomainUsesTheGenericService() {
        val c = HomePreset.HA_SERVICE.apply(HomeConfig(), mapOf(PresetField.ENTITY_ID to "garage"), ids)
        assertEquals("/api/services/homeassistant/toggle", (c.actions.single().send as Send.Http).url)
    }

    @Test
    fun webhookAndMqttPresets() {
        val hook = HomePreset.HA_WEBHOOK.apply(HomeConfig(), mapOf(PresetField.NAME to "Gate", PresetField.WEBHOOK_ID to "gate-123"), ids)
        assertEquals(Send.Http("POST", "/api/webhook/gate-123"), hook.actions.single().send)

        val mqtt = HomePreset.HA_MQTT.apply(HomeConfig(), HomePreset.HA_MQTT.initialValues(HomeConfig()) + (PresetField.NAME to "Garage"), ids)
        assertEquals(Send.Mqtt("homeassistant/switch/garage/set", "ON"), mqtt.actions.single().send)
        assertEquals(Source.Mqtt("homeassistant/switch/garage/state"), mqtt.tiles.single().source)
        assertEquals(listOf("id1", "id2", "id3"), listOf(hook.actions[0].id, mqtt.actions[0].id, mqtt.tiles[0].id))

        val noState = HomePreset.HA_MQTT.apply(HomeConfig(), mapOf(PresetField.COMMAND_TOPIC to "a/set", PresetField.STATE_TOPIC to ""), ids)
        assertTrue(noState.tiles.isEmpty())
    }

    @Test
    fun genericPresets() {
        val button = HomePreset.MQTT_BUTTON.apply(HomeConfig(), mapOf(PresetField.NAME to "Lights", PresetField.COMMAND_TOPIC to "x/set", PresetField.PAYLOAD to "1"), ids)
        assertEquals(Send.Mqtt("x/set", "1"), button.actions.single().send)
        val hook = HomePreset.HTTP_WEBHOOK.apply(HomeConfig(), mapOf(PresetField.URL to "https://example.org/hook"), ids)
        assertEquals(Send.Http("POST", "https://example.org/hook"), hook.actions.single().send)
        val value = HomePreset.HTTP_JSON_VALUE.apply(HomeConfig(), mapOf(PresetField.URL to "https://e/v", PresetField.JSON_PATH to "$.a", PresetField.UNIT to "%"), ids)
        assertEquals(Source.Http("https://e/v", jsonPath = "$.a"), value.tiles.single().source)
    }

    @Test
    fun initialValuesOfferTheSavedServerButNeverTheToken() {
        val c = HomeConfig(http = HttpServer("https://mine", listOf(Header("Authorization", "Bearer secret"))))
        val v = HomePreset.HA_SERVICE.initialValues(c)
        assertEquals("https://mine", v[PresetField.BASE_URL])
        assertEquals("", v[PresetField.TOKEN])
        assertEquals("toggle", v[PresetField.SERVICE])
    }
}
