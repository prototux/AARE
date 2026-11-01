package net.prototux.aare.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.Settings
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
    var style by remember { mutableStateOf(settings.carStyle) }
    var banners by remember { mutableStateOf(settings.messageBanners) }
    var aloud by remember { mutableStateOf(settings.readMessagesAloud) }
    var audio by remember { mutableStateOf(settings.carAudio) }
    var darkBar by remember { mutableStateOf(settings.darkBar) }
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
            Choice(stringResource(R.string.setup_choice_bar), listOf(Settings.RAIL_AUTO to stringResource(R.string.setup_choice_bar_auto), Settings.RAIL_LEFT to stringResource(R.string.setup_choice_left), Settings.RAIL_RIGHT to stringResource(R.string.setup_choice_right), Settings.RAIL_BOTTOM to stringResource(R.string.setup_choice_bottom)), rail) {
                rail = it; settings.railPosition = it
            }
        }
        item {
            Choice(stringResource(R.string.setup_choice_style), listOf(Settings.STYLE_AUTO to stringResource(R.string.setup_style_android_auto), Settings.STYLE_CARPLAY to stringResource(R.string.setup_style_carplay)), style) {
                style = it; settings.carStyle = it
            }
        }
        item { SwitchRow(stringResource(R.string.setup_switch_dark_bar), darkBar) { darkBar = it; settings.darkBar = it } }
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

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
