package net.prototux.aare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.car.calibration.CalState
import net.prototux.aare.car.calibration.CalibrationHub

/**
 * The phone side of the touch calibration tool: it drives every step, since the car screen's
 * touches can't be trusted until the tool is done.
 */
@Composable
fun TouchCalibrationCard(settings: Settings) {
    val state by CalibrationHub.state.collectAsState()
    // Saved calibrations change when one is saved or forgotten: re-read on each state change.
    var forgotten by remember { mutableIntStateOf(0) }
    val saved = remember(state, forgotten) { settings.calibratedCars }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)) {
        Text(stringResource(R.string.calibration_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            CalState.Unavailable -> {
                Hint(stringResource(R.string.calibration_intro))
                Hint(stringResource(R.string.calibration_not_connected))
                // Offline, a saved calibration can still be forgotten (the car's own values come back next time).
                saved.forEach { car ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.calibration_saved_for, car), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { settings.setTouchCalibration(car, null); forgotten++ }) { Text(stringResource(R.string.calibration_forget)) }
                    }
                }
            }
            is CalState.Ready -> {
                Hint(stringResource(R.string.calibration_intro))
                if (s.saved) Hint(stringResource(R.string.calibration_using_saved))
                if (s.driving) Hint(stringResource(R.string.calibration_driving))
                Buttons {
                    Button(onClick = { CalibrationHub.start() }, enabled = !s.driving) { Text(stringResource(R.string.calibration_start)) }
                    if (s.saved) OutlinedButton(onClick = { CalibrationHub.resetSaved() }) { Text(stringResource(R.string.calibration_reset)) }
                }
            }
            is CalState.Area -> AreaStep(s)
            is CalState.Target -> {
                Hint(stringResource(if (s.retry) R.string.calibration_target_retry_help else R.string.calibration_target_help, s.index + 1))
                Buttons { OutlinedButton(onClick = { CalibrationHub.cancel() }) { Text(stringResource(R.string.calibration_cancel)) } }
            }
            is CalState.Verify -> {
                Hint(stringResource(R.string.calibration_verify_help, s.secondsLeft))
                Buttons { OutlinedButton(onClick = { CalibrationHub.cancel() }) { Text(stringResource(R.string.calibration_cancel)) } }
            }
            is CalState.Result -> {
                Hint(stringResource(when (s.outcome) {
                    CalState.Outcome.SAVED -> R.string.calibration_result_saved
                    CalState.Outcome.MISSED -> R.string.calibration_result_missed
                    CalState.Outcome.TIMEOUT -> R.string.calibration_result_timeout
                    CalState.Outcome.CANCELLED -> R.string.calibration_result_cancelled
                    CalState.Outcome.NO_SPREAD -> R.string.calibration_result_no_spread
                    CalState.Outcome.INTERRUPTED -> R.string.calibration_result_interrupted
                    CalState.Outcome.RESET -> R.string.calibration_result_reset
                }))
                Buttons { Button(onClick = { CalibrationHub.dismiss() }) { Text(stringResource(android.R.string.ok)) } }
            }
        }
    }
}

@Composable
private fun AreaStep(s: CalState.Area) {
    var edge by rememberSaveable { mutableIntStateOf(0) }
    val names = listOf(R.string.calibration_edge_left, R.string.calibration_edge_top, R.string.calibration_edge_right, R.string.calibration_edge_bottom)
    Hint(stringResource(R.string.calibration_area_help))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        names.forEachIndexed { i, n ->
            SegmentedButton(selected = edge == i, onClick = { edge = i }, shape = SegmentedButtonDefaults.itemShape(i, names.size)) {
                Text(stringResource(n))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    // The margin of that edge: bigger moves the edge in, smaller moves it out.
    Text(stringResource(R.string.calibration_area_value, stringResource(names[edge]), s.margins[edge]), style = MaterialTheme.typography.bodyLarge)
    Buttons {
        for (step in listOf(-10, -1, 1, 10)) {
            OutlinedButton(onClick = { CalibrationHub.setMargins(s.margins.toMutableList().also { it[edge] += step }) }) {
                Text(if (step > 0) "+$step" else "$step")
            }
        }
    }
    Buttons {
        TextButton(onClick = { CalibrationHub.setMargins(s.carMargins) }) { Text(stringResource(R.string.calibration_area_car_values)) }
        OutlinedButton(onClick = { CalibrationHub.cancel() }) { Text(stringResource(R.string.calibration_cancel)) }
        Button(onClick = { CalibrationHub.next() }) { Text(stringResource(R.string.calibration_next)) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
