package net.prototux.aare.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.SettingsBackup
import net.prototux.aare.faces.Face
import net.prototux.aare.faces.FaceException
import net.prototux.aare.faces.FaceSpec
import net.prototux.aare.faces.FaceStore
import net.prototux.aare.ui.car.DashCard
import net.prototux.aare.ui.car.DashItem
import net.prototux.aare.ui.car.FaceCard
import net.prototux.aare.ui.car.FacePreview
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.ui.theme.CarThemes
import net.prototux.aare.ui.theme.forPhone
import net.prototux.aare.ui.theme.withAccent

/**
 * The phone's "Clock and custom cards" page: the clock card's design, picked from the bundled ones,
 * and the user's own cards, imported from files made on a computer (docs/custom-cards.md).
 */
@Composable
internal fun FacesSettings(settings: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bundled by produceState(emptyList<Face>()) { value = withContext(Dispatchers.IO) { FaceStore.bundled(ctx) } }
    LaunchedEffect(Unit) { FaceStore.load(ctx) }
    val own by FaceStore.own.collectAsState()
    var clock by remember { mutableStateOf(settings.clockFace) }
    var opened by remember { mutableStateOf<Face?>(null) }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            for (uri in uris) runCatching {
                val json = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { SettingsBackup.readLimited(it, FaceSpec.MAX_BYTES) }.toString(Charsets.UTF_8)
                }
                FaceStore.import(ctx, json)
            }.onSuccess { settings.showCard(FaceCard(it.id)) }.onFailure {
                DiagLog.add("card import failed: ${it.javaClass.simpleName}")
                val reason = if (it is FaceException) it.message.orEmpty() else ctx.getString(R.string.faces_cant_read)
                Toast.makeText(ctx, ctx.getString(R.string.faces_failed, reason), Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.dash_card_clock)) {
            Grid(bundled) { face ->
                FaceTile(settings, face, selected = face.id == clock) {
                    clock = face.id
                    settings.clockFace = face.id
                    settings.showCard(DashCard.CLOCK)
                }
            }
        }
        Section(stringResource(R.string.faces_section_own)) {
            Text(stringResource(R.string.faces_own_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (own.isNotEmpty()) {
                Grid(own) { face -> FaceTile(settings, face, selected = false) { opened = face } }
                Spacer(Modifier.height(12.dp))
            }
            // Any type: not every phone knows what a .json is.
            Pill(stringResource(R.string.faces_import), Icons.Rounded.Add) { import.launch(arrayOf("*/*")) }
        }
    }

    opened?.let { face ->
        AlertDialog(
            onDismissRequest = { opened = null },
            title = { Text(name(face), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                if (face.spec == null) Text(face.error.orEmpty())
                else Box(Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT).clip(RoundedCornerShape(16.dp))) { CarCardPreview(settings, face.spec) }
            },
            confirmButton = { TextButton(onClick = { opened = null }) { Text(stringResource(R.string.common_close)) } },
            dismissButton = {
                TextButton(onClick = {
                    opened = null
                    settings.dashboardCards = (DashCard.parse(settings.dashboardCards) - FaceCard(face.id)).ifEmpty { listOf(DashCard.MAP) }.map(DashItem::id)
                    scope.launch { FaceStore.delete(ctx, face.id) }
                }) { Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

/** Adds [card] to the dashboard, after the others, unless it's there already. */
private fun Settings.showCard(card: DashItem) {
    val order = DashCard.parse(dashboardCards)
    if (card !in order) dashboardCards = (order + card).map(DashItem::id)
}

/** A bundled design's name is a word to translate; a card of the user's is called what its file says. */
@Composable
private fun name(face: Face): String = when (face.id) {
    "digital" -> stringResource(R.string.face_digital)
    "stacked" -> stringResource(R.string.face_stacked)
    "weather" -> stringResource(R.string.face_weather)
    "classic" -> stringResource(R.string.face_classic)
    "minimal" -> stringResource(R.string.face_minimal)
    "rings" -> stringResource(R.string.face_rings)
    "elegant" -> stringResource(R.string.face_elegant)
    "segments" -> stringResource(R.string.face_segments)
    "agenda" -> stringResource(R.string.face_agenda)
    else -> face.spec?.name ?: stringResource(R.string.faces_broken)
}

@Composable
private fun Grid(faces: List<Face>, tile: @Composable (Face) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        faces.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { Box(Modifier.weight(1f)) { tile(it) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private const val CARD_ASPECT = 1.6f

@Composable
private fun FaceTile(settings: Settings, face: Face, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.clip(shape).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT).clip(shape)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape),
        ) { CarCardPreview(settings, face.spec) }
        Text(name(face), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (face.spec == null) MaterialTheme.colorScheme.error else if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp))
    }
}

/** [spec] as the car draws it: in the chosen theme's colours, on a dashboard card's background. */
@Composable
private fun CarCardPreview(settings: Settings, spec: FaceSpec?) {
    val ctx = LocalContext.current
    val theme = remember { CarThemes.byId(settings.carThemeId).forPhone(ctx).withAccent(settings.carAccent, ctx) }
    CarTheme(night = settings.carTheme != Settings.THEME_LIGHT, theme = theme) {
        // Glass themes' cards are translucent: over the theme's background.
        Box(Modifier.fillMaxSize().background(Color.Black).background(MaterialTheme.colorScheme.background).background(MaterialTheme.colorScheme.surfaceContainer)) {
            spec?.let { FacePreview(it, Modifier.fillMaxSize()) }
        }
    }
}
