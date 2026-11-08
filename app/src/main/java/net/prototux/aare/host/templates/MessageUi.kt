package net.prototux.aare.host.templates

import androidx.car.app.model.CarText
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.R

/** MessageTemplate: icon (or spinner), a short message, optional debug text and up to two buttons. */
@Composable
internal fun MessageTemplateView(t: MessageTemplate) {
    @Suppress("DEPRECATION")
    val header = headerSpec(t.header, t.title, t.headerAction, t.actionStrip)
    TemplateScaffold(header) {
        MessageBody(t.isLoading, t.icon, t.message, t.debugMessage, t.actions)
    }
}

@Composable
internal fun MessageBody(
    loading: Boolean,
    icon: androidx.car.app.model.CarIcon?,
    message: CarText?,
    debug: CarText?,
    actions: List<androidx.car.app.model.Action>,
) {
    val scheme = MaterialTheme.colorScheme
    val inCard = LocalInCard.current
    // centered when it fits, scrollable when it does not
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(horizontal = if (inCard) 16.dp else 48.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 5.dp)
                Spacer(Modifier.height(24.dp))
            } else if (icon != null) {
                CarIconView(icon, 88.dp)
                Spacer(Modifier.height(24.dp))
            }
            CarTextView(
                message,
                Modifier.widthIn(max = 720.dp),
                style = if (inCard) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
                maxLines = 6,
                textAlign = TextAlign.Center,
            )
            if (!CarText.isNullOrEmpty(debug)) {
                Spacer(Modifier.height(16.dp))
                CarTextView(
                    debug,
                    Modifier.widthIn(max = 720.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainer).padding(16.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                    color = scheme.onSurfaceVariant,
                    maxLines = 8,
                )
            }
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(32.dp))
                ActionButtons(actions)
            }
        }
    }
}

/**
 * LongMessageTemplate: scrolling text with buttons. Android Auto only shows it while parked,
 * so while driving we show a calm placeholder instead.
 */
@Composable
internal fun LongMessageTemplateView(t: LongMessageTemplate) {
    val host = LocalTemplateHost.current
    val header = headerSpec(null, t.title, t.headerAction, t.actionStrip)
    TemplateScaffold(header) {
        if (host.driving) {
            EmptyMessage(null, stringResource(R.string.tpl_parked_only), icon = Icons.Rounded.Lock)
            return@TemplateScaffold
        }
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                CarTextView(
                    t.message,
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = Int.MAX_VALUE,
                )
            }
            if (t.actions.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                ActionButtons(t.actions, Modifier.fillMaxWidth().heightIn(min = 76.dp))
            }
        }
    }
}
