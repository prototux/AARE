package net.prototux.aare.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.prototux.aare.R

/**
 * Search entry. The car screen is a virtual display where the phone's IME does not appear, so
 * we draw our own keyboard; like Android Auto, it is locked while driving.
 */
@Composable
internal fun SearchView(ui: MediaUi, driving: Boolean) {
    if (driving) {
        Placeholder(stringResource(R.string.search_parked_only), loading = false, icon = Icons.Rounded.Search)
        return
    }
    CarKeyboard(
        onText = { ui.query += it },
        onBackspace = { ui.query = ui.query.dropLast(1) },
        onClear = { ui.query = "" },
        onSearch = { ui.search(ui.query) },
        searchEnabled = ui.query.isNotBlank(),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private val ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm-")

/** A big-key QWERTY keyboard whose rows share the available height. */
@Composable
internal fun CarKeyboard(
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    searchEnabled: Boolean,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        ROWS.forEachIndexed { r, keys ->
            KeyRow(Modifier.weight(1f)) {
                keys.forEach { c -> Key(c.uppercase(), Modifier.weight(1f)) { onText(c.toString()) } }
                if (r == ROWS.lastIndex) Key(null, Modifier.weight(2f), Icons.AutoMirrored.Rounded.Backspace, onClick = onBackspace)
            }
        }
        KeyRow(Modifier.weight(1f)) {
            Key(null, Modifier.weight(1.5f), Icons.Rounded.Clear, onClick = onClear)
            Key(null, Modifier.weight(5f), Icons.Rounded.SpaceBar) { onText(" ") }
            Key(stringResource(R.string.media_action_search), Modifier.weight(3f), Icons.Rounded.Search, primary = true, enabled = searchEnabled, onClick = onSearch)
        }
    }
}

@Composable
private fun KeyRow(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(max = 76.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun Key(
    label: String?,
    modifier: Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (primary && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (primary && enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, label, tint = fg, modifier = Modifier.size(30.dp))
            if (label != null) {
                if (icon != null) Box(Modifier.size(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, color = fg)
            }
        }
    }
}
