package net.prototux.aare.ui.car

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.host.templates.CarFormat

/**
 * The instrument cluster's turn card, when the navigation app has no cluster session of its own
 * (or Google Maps or Waze guides on the phone): the next maneuver, its distance, the road and the
 * arrival time. Without guidance: the time and what's playing.
 */
@Composable
internal fun ClusterTurnCard(ui: CarUiState, runtime: CarRuntime, modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val trip = ui.trip
        val phone = ui.phoneGuidance
        val step = trip?.steps?.firstOrNull()
        when {
            step != null -> {
                step.maneuver?.icon?.let { icon -> runtime.icons.load(icon, 192, ui.tripPackage) }?.let { img ->
                    Image(img, null, Modifier.size(96.dp))
                }
                trip.stepTravelEstimates.firstOrNull()?.remainingDistance?.let { d ->
                    Text(CarFormat.distance(d.displayDistance, d.displayUnit), style = MaterialTheme.typography.displaySmall)
                }
                val road = (step.road ?: step.cue ?: trip.currentRoad)?.toString().orEmpty()
                if (road.isNotBlank()) Text(road, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                trip.destinationTravelEstimates.firstOrNull()?.arrivalTimeAtDestination?.let { eta ->
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.cluster_arrival_time, DateFormat.getTimeFormat(context).format(eta.timeSinceEpochMillis)),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            phone != null -> {
                phone.arrow?.let { a ->
                    Image(a.asImageBitmap(), null, Modifier.size(96.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface, BlendMode.SrcIn))
                }
                Text(phone.primary, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (phone.secondary.isNotBlank()) Text(phone.secondary, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2)
                if (phone.detail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(phone.detail, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            else -> {
                Clock()
                ui.nowPlaying?.let { np ->
                    Spacer(Modifier.height(12.dp))
                    Text(np.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(np.subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
