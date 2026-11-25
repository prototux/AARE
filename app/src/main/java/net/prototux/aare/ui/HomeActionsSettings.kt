package net.prototux.aare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.prototux.aare.R
import net.prototux.aare.car.DashboardInfo
import net.prototux.aare.home.Arrival
import net.prototux.aare.home.Header
import net.prototux.aare.home.HomeAction
import net.prototux.aare.home.HomeConfig
import net.prototux.aare.home.HomeHttp
import net.prototux.aare.home.HomeHub
import net.prototux.aare.home.HomePreset
import net.prototux.aare.home.HomeStore
import net.prototux.aare.home.HomeTile
import net.prototux.aare.home.HttpServer
import net.prototux.aare.home.MqttBroker
import net.prototux.aare.home.PresetField
import net.prototux.aare.home.Redact
import net.prototux.aare.home.Send
import net.prototux.aare.home.Source
import net.prototux.aare.home.mqtt.MqttCodec

/**
 * The phone's "Home actions" page: the broker and the HTTP server, the car's buttons (actions)
 * and values (tiles), presets, and a test for each. Everything is saved as it is confirmed.
 */
@Composable
internal fun HomeActionsSettings() {
    val ctx = LocalContext.current
    val store = remember { HomeStore(ctx) }
    var config by remember { mutableStateOf(store.load()) }
    fun save(c: HomeConfig) { config = c; store.save(c) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.home_page_title)) {
            Toggle(stringResource(R.string.home_toggle_title), stringResource(R.string.home_toggle_subtitle), config.enabled) { save(config.copy(enabled = it)) }
            Hint(stringResource(R.string.home_intro))
        }
        Section(stringResource(R.string.home_section_connections)) {
            val b = config.mqtt
            ListRow(stringResource(R.string.home_broker_title),
                if (b.configured) "${if (b.tls) "mqtts" else "mqtt"}://${b.host}:${b.port}" else stringResource(R.string.home_not_set_up)) { dialog = HomeDialog.Broker }
            ListRow(stringResource(R.string.home_http_title),
                config.http.baseUrl.ifBlank { stringResource(R.string.home_not_set_up) } +
                    if (config.http.headers.isNotEmpty()) " · " + pluralStringResource(R.plurals.home_http_headers_count, config.http.headers.size, config.http.headers.size) else "") { dialog = HomeDialog.Http }
        }
        Section(stringResource(R.string.home_section_actions)) {
            if (config.actions.isEmpty()) Hint(stringResource(R.string.home_actions_empty))
            config.actions.forEach { a -> ListRow(a.name.ifBlank { "?" }, actionSummary(a)) { dialog = HomeDialog.EditAction(a, isNew = false) } }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.home_add_action), Icons.Rounded.Add) {
                    dialog = HomeDialog.EditAction(HomeAction(HomeConfig.newId(), "", Send.Mqtt("")), isNew = true)
                }
                Pill(stringResource(R.string.home_add_preset), Icons.Rounded.Add) { dialog = HomeDialog.PickPreset }
            }
        }
        Section(stringResource(R.string.home_section_tiles)) {
            if (config.tiles.isEmpty()) Hint(stringResource(R.string.home_tiles_empty))
            config.tiles.forEach { t -> ListRow(t.name.ifBlank { "?" }, tileSummary(t)) { dialog = HomeDialog.EditTile(t, isNew = false) } }
            Spacer(Modifier.height(8.dp))
            Pill(stringResource(R.string.home_add_tile), Icons.Rounded.Add) {
                dialog = HomeDialog.EditTile(HomeTile(HomeConfig.newId(), "", Source.Mqtt("")), isNew = true)
            }
        }
        Hint(stringResource(R.string.home_security_hint))
    }

    val close = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        HomeDialog.Broker -> BrokerDialog(config, close) { save(config.copy(mqtt = it)); close() }
        HomeDialog.Http -> HttpDialog(config.http, close) { save(config.copy(http = it)); close() }
        HomeDialog.PickPreset -> PresetPicker(close) { dialog = HomeDialog.FillPreset(it) }
        is HomeDialog.FillPreset -> PresetDialog(d.preset, config, close) { save(it); close() }
        is HomeDialog.EditAction -> ActionDialog(d.action, config, close,
            onDelete = if (d.isNew) null else ({ save(config.copy(actions = config.actions.filterNot { it.id == d.action.id })); close() }),
        ) { a ->
            save(config.copy(actions = if (d.isNew) config.actions + a else config.actions.map { if (it.id == a.id) a else it }))
            close()
        }
        is HomeDialog.EditTile -> TileDialog(d.tile, config, close,
            onDelete = if (d.isNew) null else ({ save(config.copy(tiles = config.tiles.filterNot { it.id == d.tile.id })); close() }),
        ) { t ->
            save(config.copy(tiles = if (d.isNew) config.tiles + t else config.tiles.map { if (it.id == t.id) t else it }))
            close()
        }
    }
}

private sealed interface HomeDialog {
    data object Broker : HomeDialog
    data object Http : HomeDialog
    data object PickPreset : HomeDialog
    data class FillPreset(val preset: HomePreset) : HomeDialog
    data class EditAction(val action: HomeAction, val isNew: Boolean) : HomeDialog
    data class EditTile(val tile: HomeTile, val isNew: Boolean) : HomeDialog
}

@Composable
private fun actionSummary(a: HomeAction): String = listOfNotNull(
    when (val s = a.send) {
        is Send.Mqtt -> "MQTT ${s.topic}"
        is Send.Http -> "${s.method} ${Redact.url(s.url)}"
    },
    if (a.confirm) stringResource(R.string.home_summary_confirm) else null,
    if (a.arrival?.enabled == true) stringResource(R.string.home_summary_arrival) else null,
).joinToString(" · ")

private fun tileSummary(t: HomeTile): String = when (val s = t.source) {
    is Source.Mqtt -> "MQTT ${s.topic}" + if (s.jsonPath.isNotBlank()) " ${s.jsonPath}" else ""
    is Source.Http -> "GET ${Redact.url(s.url)}" + if (s.jsonPath.isNotBlank()) " ${s.jsonPath}" else ""
}

// ---------------------------------------------------------------- connections

@Composable
private fun BrokerDialog(config: HomeConfig, onDismiss: () -> Unit, onSave: (MqttBroker) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val b = config.mqtt
    var host by remember { mutableStateOf(b.host) }
    var port by remember { mutableStateOf(b.port.toString()) }
    var tls by remember { mutableStateOf(b.tls) }
    var user by remember { mutableStateOf(b.username) }
    var password by remember { mutableStateOf(b.password) }
    var clientId by remember { mutableStateOf(b.clientId) }
    var result by remember { mutableStateOf<String?>(null) }
    val broker = MqttBroker(host.trim(), port.toIntOrNull() ?: 0, tls, user.trim(), password, clientId.trim())
    val valid = broker.host.isEmpty() || broker.configured
    Editor(stringResource(R.string.home_broker_title), onDismiss, saveEnabled = valid, onSave = { onSave(broker) },
        onTest = if (broker.configured) ({
            result = ctx.getString(R.string.home_testing)
            scope.launch {
                val e = HomeHub.testBroker(broker, config)
                result = if (e == null) ctx.getString(R.string.home_test_connected) else ctx.getString(R.string.home_test_failed, HomeHub.reason(ctx, e))
            }
        }) else null, result = result) {
        Field(host, { host = it }, stringResource(R.string.home_field_host), hint = stringResource(R.string.home_broker_host_hint))
        Field(port, { port = it.filter(Char::isDigit).take(5) }, stringResource(R.string.home_field_port), number = true,
            error = port.isNotEmpty() && broker.port !in 1..65_535)
        Toggle(stringResource(R.string.home_tls_title), stringResource(R.string.home_tls_subtitle), tls) {
            // Follow the usual ports when they weren't changed.
            if (port == if (tls) "8883" else "1883") port = if (it) "8883" else "1883"
            tls = it
        }
        Field(user, { user = it }, stringResource(R.string.home_field_username))
        Field(password, { password = it }, stringResource(R.string.home_field_password), secret = true)
        Field(clientId, { clientId = it }, stringResource(R.string.home_field_client_id), hint = stringResource(R.string.home_client_id_hint))
    }
}

@Composable
private fun HttpDialog(server: HttpServer, onDismiss: () -> Unit, onSave: (HttpServer) -> Unit) {
    val ctx = LocalContext.current
    var base by remember { mutableStateOf(server.baseUrl) }
    var token by remember { mutableStateOf(server.token) }
    var headers by remember { mutableStateOf(Header.toLines(server.otherHeaders)) }
    var lanHttp by remember { mutableStateOf(server.allowLanHttp) }
    val edited = HttpServer(base.trim().trimEnd('/'), Header.parseLines(headers), lanHttp)
    val refusal = if (base.isBlank()) null else plainRefusal(edited, "/")
    val baseValid = base.isBlank() || (HomeHttp.resolve(base, "/") != null && refusal == null)
    Editor(stringResource(R.string.home_http_title), onDismiss, saveEnabled = baseValid,
        onSave = { onSave(edited.withToken(token)) }) {
        Field(base, { base = it }, stringResource(R.string.home_field_base_url),
            hint = refusal?.let { HomeHub.reason(ctx, it) } ?: stringResource(R.string.home_base_url_hint), error = !baseValid)
        Field(token, { token = it }, stringResource(R.string.home_field_token), hint = stringResource(R.string.home_token_hint), secret = true)
        Field(headers, { headers = it }, stringResource(R.string.home_field_headers), hint = stringResource(R.string.home_headers_hint), lines = 3)
        // Off by default; the subtitle is the warning (nothing on that network is encrypted).
        Toggle(stringResource(R.string.home_http_lan_title), stringResource(R.string.home_http_lan_subtitle), lanHttp) { lanHttp = it }
    }
}

/** Why plain http [url] is refused with [server], or null (also when [url] isn't a valid URL yet). */
private fun plainRefusal(server: HttpServer, url: String): HomeHttp.PlainHttpRefused? =
    HomeHttp.resolve(server.baseUrl, url)?.let { HomeHttp.plainRefusal(server, it) }

// ---------------------------------------------------------------- presets

@Composable
private fun PresetPicker(onDismiss: () -> Unit, onPick: (HomePreset) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_add_preset)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                HomePreset.entries.forEach { p -> ListRow(stringResource(p.title), stringResource(p.subtitle)) { onPick(p) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun PresetDialog(preset: HomePreset, config: HomeConfig, onDismiss: () -> Unit, onAdd: (HomeConfig) -> Unit) {
    var values by remember { mutableStateOf(preset.initialValues(config)) }
    Editor(stringResource(preset.title), onDismiss, saveLabel = stringResource(R.string.home_add), onSave = { onAdd(preset.apply(config, values)) }) {
        Hint(stringResource(preset.subtitle))
        preset.fields.forEach { f ->
            Field(values[f].orEmpty(), { values = values + (f to it) }, stringResource(f.label),
                hint = if (f == PresetField.TOKEN && config.http.token.isNotEmpty()) stringResource(R.string.home_token_keep_hint) else f.hint?.let { stringResource(it) },
                secret = f == PresetField.TOKEN)
        }
        Hint(stringResource(R.string.home_preset_editable))
    }
}

private val HomePreset.title: Int
    get() = when (this) {
        HomePreset.HA_SERVICE -> R.string.home_preset_ha_service
        HomePreset.HA_STATE -> R.string.home_preset_ha_state
        HomePreset.HA_WEBHOOK -> R.string.home_preset_ha_webhook
        HomePreset.HA_MQTT -> R.string.home_preset_ha_mqtt
        HomePreset.MQTT_BUTTON -> R.string.home_preset_mqtt_button
        HomePreset.HTTP_WEBHOOK -> R.string.home_preset_http_webhook
        HomePreset.HTTP_JSON_VALUE -> R.string.home_preset_http_json
    }

private val HomePreset.subtitle: Int
    get() = when (this) {
        HomePreset.HA_SERVICE -> R.string.home_preset_ha_service_subtitle
        HomePreset.HA_STATE -> R.string.home_preset_ha_state_subtitle
        HomePreset.HA_WEBHOOK -> R.string.home_preset_ha_webhook_subtitle
        HomePreset.HA_MQTT -> R.string.home_preset_ha_mqtt_subtitle
        HomePreset.MQTT_BUTTON -> R.string.home_preset_mqtt_button_subtitle
        HomePreset.HTTP_WEBHOOK -> R.string.home_preset_http_webhook_subtitle
        HomePreset.HTTP_JSON_VALUE -> R.string.home_preset_http_json_subtitle
    }

private val PresetField.label: Int
    get() = when (this) {
        PresetField.NAME -> R.string.home_field_name
        PresetField.BASE_URL -> R.string.home_field_base_url
        PresetField.TOKEN -> R.string.home_field_token
        PresetField.ENTITY_ID -> R.string.home_field_entity
        PresetField.SERVICE -> R.string.home_field_service
        PresetField.WEBHOOK_ID -> R.string.home_field_webhook
        PresetField.COMMAND_TOPIC -> R.string.home_field_command_topic
        PresetField.PAYLOAD -> R.string.home_field_payload
        PresetField.STATE_TOPIC -> R.string.home_field_state_topic
        PresetField.URL -> R.string.home_field_url
        PresetField.JSON_PATH -> R.string.home_field_json_path
        PresetField.UNIT -> R.string.home_field_unit
    }

private val PresetField.hint: Int?
    get() = when (this) {
        PresetField.ENTITY_ID -> R.string.home_entity_hint
        PresetField.SERVICE -> R.string.home_service_hint
        PresetField.TOKEN -> R.string.home_token_hint
        PresetField.JSON_PATH -> R.string.home_json_path_hint
        else -> null
    }

// ---------------------------------------------------------------- actions and tiles

@Composable
private fun ActionDialog(action: HomeAction, config: HomeConfig, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (HomeAction) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mqtt0 = action.send as? Send.Mqtt
    val http0 = action.send as? Send.Http
    var name by remember { mutableStateOf(action.name) }
    var isMqtt by remember { mutableStateOf(mqtt0 != null) }
    var topic by remember { mutableStateOf(mqtt0?.topic.orEmpty()) }
    var payload by remember { mutableStateOf(mqtt0?.payload.orEmpty()) }
    var qos by remember { mutableStateOf(mqtt0?.qos ?: 0) }
    var retain by remember { mutableStateOf(mqtt0?.retain ?: false) }
    var method by remember { mutableStateOf(http0?.method ?: "POST") }
    var url by remember { mutableStateOf(http0?.url.orEmpty()) }
    var headers by remember { mutableStateOf(Header.toLines(http0?.headers.orEmpty())) }
    var body by remember { mutableStateOf(http0?.body.orEmpty()) }
    var confirm by remember { mutableStateOf(action.confirm) }
    var onCard by remember { mutableStateOf(action.onCard) }
    var arrivalOn by remember { mutableStateOf(action.arrival?.enabled ?: false) }
    var lat by remember { mutableStateOf(action.arrival?.latitude?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(action.arrival?.longitude?.toString().orEmpty()) }
    var radius by remember { mutableStateOf((action.arrival?.radiusM ?: 150).toString()) }
    var result by remember { mutableStateOf<String?>(null) }

    val send = if (isMqtt) Send.Mqtt(topic.trim(), payload, qos, retain) else Send.Http(method, url.trim(), Header.parseLines(headers), body)
    val place = lat.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }?.let { la ->
        lon.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }?.let { lo -> Arrival(la, lo, (radius.toIntOrNull() ?: 150).coerceIn(50, 5_000), arrivalOn) }
    }
    val refusal = if (isMqtt) null else plainRefusal(config.http, url)
    val sendValid = if (isMqtt) MqttCodec.validPublishTopic(topic.trim()) else HomeHttp.resolve(config.http.baseUrl, url) != null && refusal == null
    val valid = name.isNotBlank() && sendValid && (!arrivalOn || place != null)
    val edited = HomeAction(action.id, name.trim(), send, confirm, onCard, place ?: action.arrival?.copy(enabled = false))

    Editor(stringResource(R.string.home_action_title), onDismiss, saveEnabled = valid, onSave = { onSave(edited) }, onDelete = onDelete,
        onTest = if (sendValid) ({
            result = ctx.getString(R.string.home_testing)
            scope.launch {
                val e = HomeHub.run(edited, config)
                result = if (e == null) ctx.getString(R.string.home_test_sent) else ctx.getString(R.string.home_test_failed, HomeHub.reason(ctx, e))
            }
        }) else null, result = result) {
        Field(name, { name = it }, stringResource(R.string.home_field_name))
        Choices(stringResource(R.string.home_field_kind), listOf("mqtt" to "MQTT", "http" to "HTTP"), if (isMqtt) "mqtt" else "http") { isMqtt = it == "mqtt" }
        if (isMqtt) {
            if (!config.mqtt.configured) Hint(stringResource(R.string.home_needs_broker))
            Field(topic, { topic = it }, stringResource(R.string.home_field_topic), error = topic.isNotEmpty() && !MqttCodec.validPublishTopic(topic.trim()))
            Field(payload, { payload = it }, stringResource(R.string.home_field_payload), lines = 2)
            Choices(stringResource(R.string.home_field_qos), listOf("0" to "0", "1" to "1"), qos.toString()) { qos = it.toInt() }
            Toggle(stringResource(R.string.home_retain_title), stringResource(R.string.home_retain_subtitle), retain) { retain = it }
        } else {
            Choices(stringResource(R.string.home_field_method), HomeHttp.METHODS.map { it to it }, method) { method = it }
            Field(url, { url = it }, stringResource(R.string.home_field_url), hint = refusal?.let { HomeHub.reason(ctx, it) } ?: stringResource(R.string.home_url_hint),
                error = url.isNotEmpty() && (HomeHttp.resolve(config.http.baseUrl, url) == null || refusal != null))
            Field(headers, { headers = it }, stringResource(R.string.home_field_headers), hint = stringResource(R.string.home_headers_hint), lines = 2)
            if (method != "GET") Field(body, { body = it }, stringResource(R.string.home_field_body), lines = 3)
        }
        Toggle(stringResource(R.string.home_confirm_title), stringResource(R.string.home_confirm_subtitle), confirm) { confirm = it }
        Toggle(stringResource(R.string.home_on_card_title), stringResource(R.string.home_on_card_subtitle), onCard) { onCard = it }
        Toggle(stringResource(R.string.home_arrival_toggle_title), stringResource(R.string.home_arrival_toggle_subtitle), arrivalOn) { arrivalOn = it }
        if (arrivalOn) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(lat, { lat = it }, stringResource(R.string.home_field_latitude), number = true, modifier = Modifier.weight(1f), error = lat.isNotEmpty() && place == null)
                Field(lon, { lon = it }, stringResource(R.string.home_field_longitude), number = true, modifier = Modifier.weight(1f), error = lon.isNotEmpty() && place == null)
            }
            Field(radius, { radius = it.filter(Char::isDigit).take(4) }, stringResource(R.string.home_field_radius), number = true, hint = stringResource(R.string.home_radius_hint))
            TextButton(onClick = {
                val here = DashboardInfo.phoneLocation(ctx)
                if (here == null) result = ctx.getString(R.string.home_no_location)
                else { lat = "%.6f".format(java.util.Locale.ROOT, here.latitude); lon = "%.6f".format(java.util.Locale.ROOT, here.longitude) }
            }) { Text(stringResource(R.string.home_use_location)) }
        }
    }
}

@Composable
private fun TileDialog(tile: HomeTile, config: HomeConfig, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (HomeTile) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mqtt0 = tile.source as? Source.Mqtt
    val http0 = tile.source as? Source.Http
    var name by remember { mutableStateOf(tile.name) }
    var isMqtt by remember { mutableStateOf(mqtt0 != null) }
    var topic by remember { mutableStateOf(mqtt0?.topic.orEmpty()) }
    var url by remember { mutableStateOf(http0?.url.orEmpty()) }
    var headers by remember { mutableStateOf(Header.toLines(http0?.headers.orEmpty())) }
    var path by remember { mutableStateOf(mqtt0?.jsonPath ?: http0?.jsonPath.orEmpty()) }
    var interval by remember { mutableStateOf((http0?.intervalS ?: 60).toString()) }
    var unit by remember { mutableStateOf(tile.unit) }
    var result by remember { mutableStateOf<String?>(null) }

    val source = if (isMqtt) Source.Mqtt(topic.trim(), path.trim())
        else Source.Http(url.trim(), Header.parseLines(headers), path.trim(), (interval.toIntOrNull() ?: 60).coerceAtLeast(HomeConfig.MIN_POLL_S))
    val refusal = if (isMqtt) null else plainRefusal(config.http, url)
    val sourceValid = if (isMqtt) MqttCodec.validFilter(topic.trim()) else HomeHttp.resolve(config.http.baseUrl, url) != null && refusal == null
    val edited = HomeTile(tile.id, name.trim(), source, unit.trim())

    Editor(stringResource(R.string.home_tile_title), onDismiss, saveEnabled = name.isNotBlank() && sourceValid, onSave = { onSave(edited) }, onDelete = onDelete,
        onTest = if (sourceValid) ({
            result = ctx.getString(R.string.home_testing)
            scope.launch {
                result = HomeHub.test(edited, config).fold(
                    { v -> ctx.getString(R.string.home_test_value, v ?: "—") },
                    { e -> ctx.getString(R.string.home_test_failed, HomeHub.reason(ctx, e)) },
                )
            }
        }) else null, result = result) {
        Field(name, { name = it }, stringResource(R.string.home_field_name))
        Choices(stringResource(R.string.home_field_kind), listOf("mqtt" to "MQTT", "http" to "HTTP"), if (isMqtt) "mqtt" else "http") { isMqtt = it == "mqtt" }
        if (isMqtt) {
            if (!config.mqtt.configured) Hint(stringResource(R.string.home_needs_broker))
            Field(topic, { topic = it }, stringResource(R.string.home_field_topic), hint = stringResource(R.string.home_filter_hint),
                error = topic.isNotEmpty() && !MqttCodec.validFilter(topic.trim()))
        } else {
            Field(url, { url = it }, stringResource(R.string.home_field_url), hint = refusal?.let { HomeHub.reason(ctx, it) } ?: stringResource(R.string.home_url_hint),
                error = url.isNotEmpty() && (HomeHttp.resolve(config.http.baseUrl, url) == null || refusal != null))
            Field(headers, { headers = it }, stringResource(R.string.home_field_headers), hint = stringResource(R.string.home_headers_hint), lines = 2)
            Field(interval, { interval = it.filter(Char::isDigit).take(5) }, stringResource(R.string.home_field_interval), number = true,
                hint = stringResource(R.string.home_interval_hint, HomeConfig.MIN_POLL_S))
        }
        Field(path, { path = it }, stringResource(R.string.home_field_json_path), hint = stringResource(R.string.home_json_path_hint))
        Field(unit, { unit = it }, stringResource(R.string.home_field_unit))
    }
}

// ---------------------------------------------------------------- building blocks

/** An editing dialog: its fields scroll; [onTest] (if any) shows its outcome in [result]. */
@Composable
private fun Editor(
    title: String,
    onDismiss: () -> Unit,
    saveEnabled: Boolean = true,
    saveLabel: String = stringResource(R.string.home_save),
    onSave: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onTest: (() -> Unit)? = null,
    result: String? = null,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
                if (onTest != null || onDelete != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onTest?.let { TextButton(onClick = it) { Text(stringResource(R.string.home_test)) } }
                    onDelete?.let { TextButton(onClick = it) { Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error) } }
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = saveEnabled) { Text(saveLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    hint: String? = null,
    secret: Boolean = false,
    number: Boolean = false,
    lines: Int = 1,
    error: Boolean = false,
) {
    OutlinedTextField(
        value, onChange, modifier,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = lines == 1,
        minLines = lines,
        isError = error,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            secret -> KeyboardType.Password
            number -> KeyboardType.Decimal
            else -> KeyboardType.Text
        }),
    )
}

@Composable
private fun ListRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
