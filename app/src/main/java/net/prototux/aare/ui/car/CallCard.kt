package net.prototux.aare.ui.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.prototux.aare.R
import net.prototux.aare.car.Calls
import net.prototux.aare.ui.theme.LocalCarDimens

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFD32F2F)

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
        modifier.padding(16.dp).widthIn(max = 760.dp).clip(RoundedCornerShape(32.dp))
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
            if (Calls.canEnd) RoundButton(Icons.Rounded.CallEnd, stringResource(R.string.phone_action_decline), Red) { actions.endCall() }
            RoundButton(Icons.Rounded.Call, stringResource(R.string.phone_action_answer), Green) { actions.answerCall() }
        }
    }
}

@Composable
private fun InCall(call: CarCall, state: CarUiState, actions: CarActions, modifier: Modifier) {
    val elapsed by produceState(0L, call.sinceMs) {
        while (true) { value = (System.currentTimeMillis() - call.sinceMs) / 1000; delay(1_000) }
    }
    val phone = state.apps.firstOrNull { it.kind == CarAppKind.PHONE }
    Row(
        modifier.padding(12.dp).clip(RoundedCornerShape(28.dp)).background(Green)
            .clickable { phone?.let(actions::openApp) }.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Call, null, tint = Color.White)
        Spacer(Modifier.width(12.dp))
        val timeStr = "${elapsed / 60}:${"%02d".format(elapsed % 60)}"
        Text(stringResource(R.string.phone_in_call_elapsed, call.name, timeStr), color = Color.White,
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp))
        Spacer(Modifier.width(12.dp))
        if (Calls.canEnd) RoundButton(Icons.Rounded.CallEnd, stringResource(R.string.phone_action_hang_up), Red, size = LocalCarDimens.current.touchTarget - 16.dp) { actions.endCall() }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color,
                        size: androidx.compose.ui.unit.Dp = LocalCarDimens.current.touchTarget, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White)
    }
}
