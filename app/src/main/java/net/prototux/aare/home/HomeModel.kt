package net.prototux.aare.home

import java.util.UUID

/** A request header. Header values are kept with the secrets: this is where tokens go. */
data class Header(val name: String, val value: String)

/** The MQTT broker. [password] is a secret (stored apart, never logged). */
data class MqttBroker(
    val host: String = "",
    val port: Int = 1883,
    val tls: Boolean = false,
    val username: String = "",
    val password: String = "",
    /** Blank: a random "aare-…" id per connection. */
    val clientId: String = "",
) {
    val configured: Boolean get() = host.isNotBlank() && port in 1..65_535
}

/**
 * The HTTP server: relative URLs ("/api/…") go to [baseUrl], and [headers] (Authorization…) are
 * added to requests to that server only, never to other hosts.
 */
data class HttpServer(val baseUrl: String = "", val headers: List<Header> = emptyList())

/** What an action sends. */
sealed interface Send {
    data class Mqtt(val topic: String, val payload: String = "", val qos: Int = 0, val retain: Boolean = false) : Send
    data class Http(val method: String = "POST", val url: String, val headers: List<Header> = emptyList(), val body: String = "") : Send
}

/** Where a tile's value comes from. */
sealed interface Source {
    /** The last message on [topic] (a filter: '+' and '#' allowed), through [jsonPath] if set. */
    data class Mqtt(val topic: String, val jsonPath: String = "") : Source
    /** A GET polled every [intervalS] seconds, through [jsonPath] if set. */
    data class Http(val url: String, val headers: List<Header> = emptyList(), val jsonPath: String = "", val intervalS: Int = 60) : Source
}

/** Run an action when the car comes within [radiusM] of a place. Off unless [enabled]. */
data class Arrival(val latitude: Double, val longitude: Double, val radiusM: Int = 150, val enabled: Boolean = false)

/** A button on the car screen. */
data class HomeAction(
    val id: String,
    val name: String,
    val send: Send,
    /** Ask on the car screen before sending (a garage door…). */
    val confirm: Boolean = false,
    /** Also on the dashboard's Home card (the Home app shows every action). */
    val onCard: Boolean = true,
    val arrival: Arrival? = null,
)

/** A value shown on the car screen. */
data class HomeTile(val id: String, val name: String, val source: Source, val unit: String = "")

/** Everything the user set up on the phone's "Home actions" page. */
data class HomeConfig(
    /** The whole feature: nothing connects and nothing shows in the car while it is off. */
    val enabled: Boolean = false,
    val mqtt: MqttBroker = MqttBroker(),
    val http: HttpServer = HttpServer(),
    val actions: List<HomeAction> = emptyList(),
    val tiles: List<HomeTile> = emptyList(),
) {
    /** Something to show in the car. */
    val hasContent: Boolean get() = enabled && (actions.isNotEmpty() || tiles.isNotEmpty())

    /** The broker is needed: an MQTT action or tile exists. */
    val usesMqtt: Boolean get() = mqtt.configured && (actions.any { it.send is Send.Mqtt } || tiles.any { it.source is Source.Mqtt })

    companion object {
        /** At most this many buttons on the dashboard card (the Home app shows them all). */
        const val CARD_ACTIONS = 4
        const val CARD_TILES = 4
        /** HTTP tiles aren't polled more often than this, whatever the setting. */
        const val MIN_POLL_S = 10

        fun newId(): String = UUID.randomUUID().toString().take(8)
    }
}
