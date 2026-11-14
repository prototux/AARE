package net.prototux.aare.home

import org.json.JSONObject

/** What a preset asks for. */
enum class PresetField { NAME, BASE_URL, TOKEN, ENTITY_ID, SERVICE, WEBHOOK_ID, COMMAND_TOPIC, PAYLOAD, STATE_TOPIC, URL, JSON_PATH, UNIT }

/**
 * Ready-made actions and tiles for common setups. A preset only fills in fields: what it adds is
 * an ordinary action or tile, editable like any other.
 */
enum class HomePreset(val fields: List<PresetField>, private val defaults: Map<PresetField, String> = emptyMap()) {
    /** Home Assistant's REST API: `POST /api/services/<domain>/<service>` with the entity. */
    HA_SERVICE(
        listOf(PresetField.NAME, PresetField.ENTITY_ID, PresetField.SERVICE, PresetField.BASE_URL, PresetField.TOKEN),
        mapOf(PresetField.SERVICE to "toggle", PresetField.BASE_URL to "https://homeassistant.local:8123"),
    ),
    /** Home Assistant's REST API: `GET /api/states/<entity>`, showing `$.state`. */
    HA_STATE(
        listOf(PresetField.NAME, PresetField.ENTITY_ID, PresetField.UNIT, PresetField.BASE_URL, PresetField.TOKEN),
        mapOf(PresetField.BASE_URL to "https://homeassistant.local:8123"),
    ),
    /** A Home Assistant webhook trigger: `POST <base>/api/webhook/<id>`, no token needed. */
    HA_WEBHOOK(
        listOf(PresetField.NAME, PresetField.WEBHOOK_ID, PresetField.BASE_URL),
        mapOf(PresetField.BASE_URL to "https://homeassistant.local:8123"),
    ),
    /** An entity of Home Assistant's MQTT integration: a button on its command topic and a tile on its state topic. */
    HA_MQTT(
        listOf(PresetField.NAME, PresetField.COMMAND_TOPIC, PresetField.PAYLOAD, PresetField.STATE_TOPIC),
        mapOf(PresetField.COMMAND_TOPIC to "homeassistant/switch/garage/set", PresetField.PAYLOAD to "ON",
            PresetField.STATE_TOPIC to "homeassistant/switch/garage/state"),
    ),
    MQTT_BUTTON(listOf(PresetField.NAME, PresetField.COMMAND_TOPIC, PresetField.PAYLOAD)),
    HTTP_WEBHOOK(listOf(PresetField.NAME, PresetField.URL)),
    HTTP_JSON_VALUE(listOf(PresetField.NAME, PresetField.URL, PresetField.JSON_PATH, PresetField.UNIT), mapOf(PresetField.JSON_PATH to "$.value"));

    /** The form's starting values: the preset's examples, and the connection already set up. */
    fun initialValues(config: HomeConfig): Map<PresetField, String> = fields.associateWith { f ->
        when (f) {
            PresetField.BASE_URL -> config.http.baseUrl.ifBlank { defaults[f].orEmpty() }
            // Never shown again: blank keeps the token already saved.
            PresetField.TOKEN -> ""
            else -> defaults[f].orEmpty()
        }
    }

    /** [config] with what this preset adds from [values]; [newId] names the new items. */
    fun apply(config: HomeConfig, values: Map<PresetField, String>, newId: () -> String = HomeConfig::newId): HomeConfig {
        fun v(f: PresetField) = values[f].orEmpty().trim()
        val entity = v(PresetField.ENTITY_ID)
        val label = v(PresetField.NAME).ifBlank { entity.ifBlank { name.lowercase().replace('_', ' ') } }
        var c = config
        if (PresetField.BASE_URL in fields && v(PresetField.BASE_URL).isNotEmpty()) {
            c = c.copy(http = c.http.copy(baseUrl = v(PresetField.BASE_URL).trimEnd('/')))
        }
        if (PresetField.TOKEN in fields && v(PresetField.TOKEN).isNotEmpty()) {
            val auth = Header("Authorization", "Bearer " + v(PresetField.TOKEN).removePrefix("Bearer ").trim())
            c = c.copy(http = c.http.copy(headers = c.http.headers.filterNot { it.name.equals("Authorization", true) } + auth))
        }
        return when (this) {
            HA_SERVICE -> {
                // "cover.garage_door" → the "cover" domain; "toggle" works for most.
                val domain = entity.substringBefore('.', "homeassistant")
                val service = v(PresetField.SERVICE).ifBlank { "toggle" }
                c.withAction(HomeAction(newId(), label, Send.Http("POST", "/api/services/$domain/$service",
                    body = JSONObject().put("entity_id", entity).toString())))
            }
            HA_STATE -> c.withTile(HomeTile(newId(), label, Source.Http("/api/states/$entity", jsonPath = "$.state"), v(PresetField.UNIT)))
            HA_WEBHOOK -> c.withAction(HomeAction(newId(), label, Send.Http("POST", "/api/webhook/${v(PresetField.WEBHOOK_ID)}")))
            HA_MQTT -> {
                val withButton = c.withAction(HomeAction(newId(), label, Send.Mqtt(v(PresetField.COMMAND_TOPIC), v(PresetField.PAYLOAD))))
                if (v(PresetField.STATE_TOPIC).isEmpty()) withButton
                else withButton.withTile(HomeTile(newId(), label, Source.Mqtt(v(PresetField.STATE_TOPIC))))
            }
            MQTT_BUTTON -> c.withAction(HomeAction(newId(), label, Send.Mqtt(v(PresetField.COMMAND_TOPIC), v(PresetField.PAYLOAD))))
            HTTP_WEBHOOK -> c.withAction(HomeAction(newId(), label, Send.Http("POST", v(PresetField.URL))))
            HTTP_JSON_VALUE -> c.withTile(HomeTile(newId(), label, Source.Http(v(PresetField.URL), jsonPath = v(PresetField.JSON_PATH)), v(PresetField.UNIT)))
        }
    }

    private fun HomeConfig.withAction(a: HomeAction) = copy(actions = actions + a)
    private fun HomeConfig.withTile(t: HomeTile) = copy(tiles = tiles + t)
}
