package net.prototux.aare.host.templates

import androidx.car.app.model.Tab
import androidx.car.app.model.TabTemplate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.prototux.aare.ui.car.carFocusHighlight
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * TabTemplate: app icon and tabs in the header, the active tab's template below. Selecting a tab
 * highlights it at once and asks the app (TabCallbackDelegate) for the new contents.
 */
@Composable
internal fun TabTemplateView(t: TabTemplate) {
    val host = LocalTemplateHost.current
    val d = LocalCarDimens.current
    // reset to the app's choice on every new template object (identity: a refresh may be equal)
    var active by remember(System.identityHashCode(t)) { mutableStateOf(t.activeTabContentId) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().height(d.touchTarget + 20.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            t.headerAction?.let {
                ActionView(it, ActionLook.Header)
                Spacer(Modifier.width(12.dp))
            }
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                t.tabs.forEach { tab ->
                    TabChip(tab, selected = tab.contentId == active) {
                        if (tab.contentId != active) {
                            active = tab.contentId
                            safeCall("onTabSelected") { t.tabCallbackDelegate.sendTabSelected(tab.contentId, host.done) }
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // while the app has not answered a tab switch, its old contents would be misleading
            val contents = t.tabContents
            if (t.isLoading || active != t.activeTabContentId) {
                LoadingBox()
            } else {
                // per-tab state (scroll position...) must not leak from one tab to the next
                key(t.activeTabContentId) { TemplateContent(contents.template) }
            }
        }
    }
}

@Composable
private fun TabChip(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.secondaryContainer else Color.Transparent
    val fg = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    Row(
        Modifier.height(64.dp).clip(MaterialTheme.shapes.extraLarge).background(bg)
            .carFocusHighlight(MaterialTheme.shapes.extraLarge).clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CarIconView(tab.icon, 30.dp, monochromeTint = fg)
        Spacer(Modifier.width(10.dp))
        CarTextView(tab.title, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}
