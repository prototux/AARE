package net.prototux.aare.ui.car

import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.faces.FaceStore
import net.prototux.aare.media.Pill
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * The car's own settings app: what drivers change from the car, like Android Auto's. The rest
 * stays in AARE on the phone.
 */
@Composable
internal fun CarSettingsScreen(settings: Settings, modifier: Modifier) {
    var theme by remember { mutableStateOf(settings.carTheme) }
    var rail by remember { mutableStateOf(settings.railPosition) }
    var themeId by remember { mutableStateOf(settings.carThemeId) }
    var layoutId by remember { mutableStateOf(settings.carLayoutId) }
    var accent by remember { mutableStateOf(settings.carAccent) }
    var banners by remember { mutableStateOf(settings.messageBanners) }
    var aloud by remember { mutableStateOf(settings.readMessagesAloud) }
    var audio by remember { mutableStateOf(settings.carAudio) }
    var darkBar by remember { mutableStateOf(settings.darkBar) }
    var railWidgets by remember { mutableStateOf(RailWidget.parse(settings.railWidgets)) }
    var cards by remember { mutableStateOf(DashCard.parse(settings.dashboardCards)) }
    val faces by FaceStore.own.collectAsState()
    var calendarAuto by remember { mutableStateOf(settings.calendarCardAuto) }
    var rotate by remember { mutableStateOf(settings.dashboardRotateSeconds) }
    var mapBig by remember { mutableStateOf(settings.dashboardMapBig) }
    var agendaCount by remember { mutableStateOf(settings.calendarAgendaCount) }
    var passenger by remember { mutableStateOf(settings.ignoreDriving) }
    LazyColumn(
        modifier.background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Text(stringResource(R.string.setup_app_entry_settings), style = MaterialTheme.typography.headlineSmall) }
        item {
            Choice(stringResource(R.string.setup_choice_colors), listOf(Settings.THEME_AUTO to stringResource(R.string.setup_choice_colors_auto), Settings.THEME_DARK to stringResource(R.string.setup_choice_dark), Settings.THEME_LIGHT to stringResource(R.string.setup_choice_light)), theme) {
                theme = it; settings.carTheme = it
            }
        }
        item {
            CarSelect(stringResource(R.string.setup_choice_bar), listOf(Settings.RAIL_AUTO to stringResource(R.string.setup_choice_bar_auto), Settings.RAIL_LEFT to stringResource(R.string.setup_choice_left), Settings.RAIL_RIGHT to stringResource(R.string.setup_choice_right), Settings.RAIL_BOTTOM to stringResource(R.string.setup_choice_bottom)), rail) {
                rail = it; settings.railPosition = it
            }
        }
        item {
            StyleChoices(
                themeId, layoutId, accent,
                choice = { title, options, selected, onSelect -> Choice(title, options, selected, onSelect) },
                select = { title, options, selected, onSelect -> CarSelect(title, options, selected, ::styleSwatch, onSelect) },
                onChange = { t, l -> themeId = t; layoutId = l; settings.carThemeId = t; settings.carLayoutId = l },
                onAccent = { accent = it; settings.carAccent = it },
            )
        }
        item { SwitchRow(stringResource(R.string.setup_switch_dark_bar), darkBar) { darkBar = it; settings.darkBar = it } }
        item {
            RailWidgetsEditor(railWidgets, LocalCarDimens.current.touchTarget) { railWidgets = it; settings.railWidgets = it.map(RailWidget::id) }
        }
        item {
            DashboardCardsEditor(cards, LocalCarDimens.current.touchTarget, available = DashCard.entries.filter { it != DashCard.CAR || settings.obdAdapter }, faces = faces.filter { it.spec != null },
                mapBig = mapBig, calendarAuto = calendarAuto, onCalendarAuto = { calendarAuto = it; settings.calendarCardAuto = it }) { cards = it; settings.dashboardCards = it.map(DashItem::id) }
        }
        item { CarSelect(stringResource(R.string.dash_rotate_title), rotateOptions(rotate), rotate.toString()) { rotate = it.toInt(); settings.dashboardRotateSeconds = rotate } }
        item { SwitchRow(stringResource(R.string.dash_map_big_title), mapBig) { mapBig = it; settings.dashboardMapBig = it } }
        if (DashCard.CALENDAR in cards) item {
            Choice(stringResource(R.string.setup_choice_agenda_count), (1..5).map { it.toString() to it.toString() }, agendaCount.toString()) {
                agendaCount = it.toInt(); settings.calendarAgendaCount = it.toInt()
            }
        }
        item { SwitchRow(stringResource(R.string.setup_switch_passenger), passenger) { passenger = it; settings.ignoreDriving = it } }
        item { SwitchRow(stringResource(R.string.setup_switch_message_notifications), banners) { banners = it; settings.messageBanners = it } }
        item { SwitchRow(stringResource(R.string.msg_toggle_read_aloud_title), aloud) { aloud = it; settings.readMessagesAloud = it } }
        item { SwitchRow(stringResource(R.string.setup_switch_audio_next_connection), audio) { audio = it; settings.carAudio = it } }
    }
}

@Composable
private fun Choice(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (value, label) -> Pill(label, selected = value == selected, onClick = { onSelect(value) }) }
        }
    }
}

/**
 * A long list of choices as one row with the current value; tapping it (or the knob's press) unfolds
 * every option below, one full-size row each, and picking one folds it again. The car screen's
 * stand-in for a drop-down: a pop-up menu is awkward with the knob, and a row of pills can't show
 * eleven labels at arm's length.
 */
@Composable
private fun CarSelect(title: String, options: List<Pair<String, String>>, selected: String, swatch: (String) -> List<Color> = { emptyList() }, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val rowHeight = LocalCarDimens.current.touchTarget
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = rowHeight).carClickable(MaterialTheme.shapes.medium) { open = !open },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (open) options.forEach { (value, label) ->
            Row(Modifier.fillMaxWidth().heightIn(min = rowHeight).carClickable(MaterialTheme.shapes.medium) { onSelect(value); open = false }
                .padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                swatch(value).takeIf { it.isNotEmpty() }?.let { Swatch(it, 32.dp); Spacer(Modifier.width(16.dp)) }
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (value == selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).carClickable(RectangleShape) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        // Material Switch, not yet carClickable: the row above already gives this whole row the
        // knob focus and highlight, see the allowlist in CarScreenReachabilityCheckTest.
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
