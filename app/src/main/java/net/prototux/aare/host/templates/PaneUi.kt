package net.prototux.aare.host.templates

import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/** PaneTemplate: a few informational rows, an optional large image and up to two buttons. */
@Composable
internal fun PaneTemplateView(t: PaneTemplate) {
    @Suppress("DEPRECATION")
    val header = headerSpec(t.header, t.title, t.headerAction, t.actionStrip)
    TemplateScaffold(header) { PaneContent(t.pane) }
}

/** Body of a Pane, also used inside map cards (MapWithContentTemplate, MapTemplate). */
@Composable
internal fun PaneContent(pane: Pane, modifier: Modifier = Modifier) {
    if (pane.isLoading) {
        LoadingBox(modifier)
        return
    }
    val host = LocalTemplateHost.current
    val inCard = LocalInCard.current
    val limit = host.contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE).takeIf { it > 0 } ?: Int.MAX_VALUE
    val rows = pane.rows.take(limit)
    val pad = if (inCard) 12.dp else 24.dp
    BoxWithConstraints(modifier.fillMaxSize()) {
        // big screens: image beside the rows, like Android Auto; small screens/cards: above them
        val side = !inCard && maxWidth >= 720.dp && pane.image != null
        Row(Modifier.fillMaxSize().padding(start = pad, end = pad, bottom = pad)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (!side && pane.image != null) {
                        CarIconView(
                            pane.image,
                            160.dp,
                            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
                            shape = MaterialTheme.shapes.medium,
                            contentScale = ContentScale.Fit,
                        )
                    }
                    rows.forEachIndexed { i, row -> RowItem(row, groupShape(i == 0, i == rows.lastIndex)) }
                }
                if (pane.actions.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    ActionButtons(pane.actions, Modifier.fillMaxWidth(), fill = true)
                }
            }
            if (side) {
                Spacer(Modifier.width(24.dp))
                Box(Modifier.weight(0.7f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    CarIconView(pane.image, 280.dp, shape = MaterialTheme.shapes.large, contentScale = ContentScale.Fit)
                }
            }
        }
    }
}
