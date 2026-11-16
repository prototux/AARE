package net.prototux.aare.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.car.Calls
import net.prototux.aare.ui.theme.LocalCarColors
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * Phone calls over whatever is on the car screen, like Android Auto: a big card with Answer and
 * Decline while it rings, then a small strip with the caller, the duration and Hang up. Tapping
 * the strip opens the Phone app.
 */
@Composable
internal fun CallOverlay(state: CarUiState, actions: CarActions, modifier: Modifier) {
    val call = state.call ?: return
    if (call.ringing) Ringing(call, actions, modifier) else InCall(call, state, actions, modifier)
}

@Composable
private fun Ringing(call: CarCall, actions: CarActions, modifier: Modifier) {
    Row(
        modifier.padding(16.dp).widthIn(max = 760.dp).clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(call.name.take(1), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.phone_incoming_call), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(call.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (Calls.canEnd) RoundButton(Icons.Rounded.CallEnd, stringResource(R.string.phone_action_decline), LocalCarColors.current.decline) { actions.endCall() }
            RoundButton(Icons.Rounded.Call, stringResource(R.string.phone_action_answer), LocalCarColors.current.accept) { actions.answerCall() }
        }
    }
}

@Composable
private fun InCall(call: CarCall, state: CarUiState, actions: CarActions, modifier: Modifier) {
    val elapsed by produceState(0L, call.sinceMs) {
        while (true) { value = (System.currentTimeMillis() - call.sinceMs) / 1000; delay(1_000) }
    }
    val phone = state.apps.firstOrNull { it.kind == CarAppKind.PHONE }
    val ctx = LocalContext.current
    val settings = remember(ctx) { Settings(ctx) }
    val density = LocalDensity.current
    var offset by remember { mutableStateOf(Offset(settings.callBarOffsetX, settings.callBarOffsetY)) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    // The strip hangs from the top centre; the user can drag it anywhere, and it stays put across
    // calls. Clamped so it can never be dragged off the screen. The wrapper fills the screen to
    // measure it; the [modifier] (its alignment in the parent) applies to that wrapper.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxX = (with(density) { maxWidth.toPx() } - barSize.width).coerceAtLeast(0f) / 2f
        val maxY = (with(density) { maxHeight.toPx() } - barSize.height).coerceAtLeast(0f)
        fun clamp(o: Offset) = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(0f, maxY))
        Row(
            Modifier.align(Alignment.TopCenter)
                .offset { clamp(offset).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                .onSizeChanged { barSize = it }
                .pointerInput(barSize, maxX, maxY) {
                    detectDragGestures(
                        onDrag = { _, drag -> offset = clamp(offset + drag) },
                        onDragEnd = { settings.callBarOffsetX = offset.x; settings.callBarOffsetY = offset.y },
                    )
                }
                .padding(12.dp).clip(MaterialTheme.shapes.large).background(LocalCarColors.current.accept)
                .clickable { phone?.let(actions::openApp) }.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Call, null, tint = LocalCarColors.current.onCall)
            Spacer(Modifier.width(12.dp))
            val timeStr = "${elapsed / 60}:${"%02d".format(elapsed % 60)}"
            Text(stringResource(R.string.phone_in_call_elapsed, call.name, timeStr), color = LocalCarColors.current.onCall,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp))
            Spacer(Modifier.width(12.dp))
            if (Calls.canEnd) RoundButton(Icons.Rounded.CallEnd, stringResource(R.string.phone_action_hang_up), LocalCarColors.current.decline, size = LocalCarDimens.current.touchTarget - 16.dp) { actions.endCall() }
        }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color,
                        size: androidx.compose.ui.unit.Dp = LocalCarDimens.current.touchTarget, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = LocalCarColors.current.onCall)
    }
}
