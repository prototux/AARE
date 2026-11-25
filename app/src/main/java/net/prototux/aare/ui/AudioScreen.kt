package net.prototux.aare.ui

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.audio.CaptureMethod
import net.prototux.aare.audio.CaptureMethods
import net.prototux.aare.audio.CaptureOrder
import net.prototux.aare.audio.CaptureStatus
import net.prototux.aare.audio.CaptureStatus.Reason
import net.prototux.aare.audio.CaptureStatus.State
import net.prototux.aare.audio.CarMicForApps
import net.prototux.aare.root.Root
import net.prototux.aare.service.ProjectionService
import rikka.shizuku.Shizuku

/**
 * The car audio page (the design chosen in docs/decisions.md): what you hear in the car and through
 * which method, the method selector, the methods in the order AARE tries them, why the others aren't
 * used, then the car microphone and the sound settings. Everything comes from CaptureStatus.
 */
@Composable
internal fun AudioSettings(settings: Settings, state: ProjectionService.State, refresh: Int, recheck: () -> Unit) {
    val ctx = LocalContext.current
    val live by CaptureStatus.live.collectAsState()
    var forced by remember { mutableStateOf(settings.forcedCaptureMethod) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK && r.data != null) ProjectionService.giveAudioConsent(ctx, r.resultCode, r.data!!)
        recheck()
    }
    // Shizuku itself needs Android 12 (CaptureMethods.shizukuAvailable); its listener is never
    // registered below that, so it's never live when there's no binder to answer it.
    DisposableEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val l = Shizuku.OnRequestPermissionResultListener { _, _ -> recheck() }
            runCatching { Shizuku.addRequestPermissionResultListener(l) }
            onDispose { runCatching { Shizuku.removeRequestPermissionResultListener(l) } }
        } else onDispose {}
    }
    @Suppress("UNUSED_EXPRESSION") refresh
    val setup = CaptureMethods.setup(ctx, state.audioCapture)
    val report = CaptureStatus.report(setup, live, settings.captureRefusals, forced)
    // The system app (Magisk module) is on its way out: shown only where it is installed.
    val shown = report.methods.filter { it.method != CaptureMethod.AUDIO_POLICY || setup.privileged }
    // Screen capture is offered only when nothing better can work, so nobody grants a right AARE won't use.
    val othersWork = shown.any { it.method != CaptureMethod.PLAYBACK_CAPTURE && (it.state == State.IN_USE || it.state == State.BACKUP) }
    val act = Actions(
        allowRoot = { Root.ask(ctx) { recheck() } },
        openShizuku = { ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)?.let { ctx.startSafely(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        allowShizuku = { runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST) } },
        allowCapture = {
            val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
            consent.launch(if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else mpm.createScreenCaptureIntent())
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatusCard(report)
        Section(stringResource(R.string.audio_how_title)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.audio_force_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(8.dp))
            Select(null, listOf(AUTOMATIC to stringResource(R.string.audio_force_automatic)) + shown.map { it.method.name to stringResource(it.method.title) },
                forced?.name ?: AUTOMATIC) { v ->
                forced = CaptureMethod.entries.firstOrNull { it.name == v }
                settings.forcedCaptureMethod = forced
            }
        }
        Section(stringResource(R.string.audio_tried_title)) {
            for (e in shown) TriedRow(e, report.connected, offerCapture = !othersWork, allowCapture = act.allowCapture)
        }
        Folded(stringResource(if (report.active != null) R.string.audio_why_not_title else R.string.audio_what_needs_title)) {
            for (e in shown.filter { it.method != report.active }) WhyNotRow(e, act, offerCapture = !othersWork)
        }
        Folded(stringResource(R.string.audio_about_title)) {
            for ((t, b) in listOf(
                R.string.audio_about_routing_title to R.string.audio_about_routing,
                R.string.audio_about_recording_title to R.string.audio_about_recording,
                R.string.audio_about_capture_title to R.string.audio_about_capture,
            )) Column(Modifier.padding(vertical = 6.dp)) {
                Text(stringResource(t), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(b), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        CarMicSection(settings)
        SoundSection(settings)
    }
}

private class Actions(val allowRoot: () -> Unit, val openShizuku: () -> Unit, val allowShizuku: () -> Unit, val allowCapture: () -> Unit)

/** What you hear in the car: three plain answers, then the method in use. */
@Composable
private fun StatusCard(report: CaptureStatus.Report) {
    val active = report.active
    val ok = active != null
    val bg = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val fg = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bg).padding(20.dp)) {
        Text(stringResource(if (report.connected) R.string.audio_status_title else R.string.audio_status_title_expected),
            style = MaterialTheme.typography.labelLarge, color = fg)
        Spacer(Modifier.height(8.dp))
        val split = active in CaptureOrder.POLICY
        val mixed = active == CaptureMethod.SHIZUKU
        Answer(stringResource(R.string.audio_status_working), if (ok) Mark.YES else Mark.NO, null, fg)
        Answer(stringResource(R.string.audio_status_apart), if (split) Mark.YES else if (mixed) Mark.PARTLY else Mark.NO,
            when {
                mixed -> stringResource(R.string.audio_status_apart_mixed)
                active == CaptureMethod.PLAYBACK_CAPTURE -> stringResource(R.string.audio_status_apart_none)
                else -> null
            }, fg)
        Answer(stringResource(R.string.audio_status_quiet), if (split || mixed) Mark.YES else Mark.NO,
            if (active == CaptureMethod.PLAYBACK_CAPTURE) stringResource(R.string.audio_status_quiet_not) else null, fg)
        Spacer(Modifier.height(8.dp))
        val name = active?.let { stringResource(it.title) } ?: stringResource(R.string.audio_status_none)
        Text(stringResource(R.string.audio_status_active, name) + if (report.forced != null) " " + stringResource(R.string.audio_status_forced) else "",
            style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}

private enum class Mark { YES, PARTLY, NO }

@Composable
private fun Answer(text: String, mark: Mark, note: String?, fg: Color) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        val (icon, tint) = when (mark) {
            Mark.YES -> Icons.Rounded.CheckCircle to fg
            // Works, not fully: neutral, not red.
            Mark.PARTLY -> Icons.Rounded.Contrast to fg.copy(alpha = 0.75f)
            Mark.NO -> Icons.Rounded.Cancel to MaterialTheme.colorScheme.error
        }
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = fg)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = fg) }
        }
    }
}

/** One method in the order tried: its name and one status word (or the screen-capture authorization). */
@Composable
private fun TriedRow(e: CaptureStatus.Entry, connected: Boolean, offerCapture: Boolean, allowCapture: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint) = stateIcon(e.state)
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(e.method.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (e.method == CaptureMethod.PLAYBACK_CAPTURE && offerCapture && e.state == State.NEEDS_SETUP) {
            OutlinedButton(onClick = allowCapture) { Text(stringResource(R.string.audio_action_allow_capture)) }
        } else {
            Text(stateWord(e, connected), style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}

/** A method that isn't the one in use: what it needs, why it isn't used, and what to do about it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhyNotRow(e: CaptureStatus.Entry, act: Actions, offerCapture: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        val (icon, tint) = stateIcon(e.state)
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(e.method.title), style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 4.dp)) {
                for (t in tags(e.method)) Text(stringResource(t), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 7.dp, vertical = 3.dp))
            }
            Text(whyText(e), style = MaterialTheme.typography.bodySmall, color = tint)
            val action: Pair<Int, () -> Unit>? = when (e.reason) {
                Reason.ROOT_NOT_ALLOWED -> R.string.audio_action_allow_root to act.allowRoot
                Reason.SHIZUKU_NOT_RUNNING -> R.string.audio_action_open_shizuku to act.openShizuku
                Reason.SHIZUKU_NOT_ALLOWED -> R.string.audio_action_allow_shizuku to act.allowShizuku
                Reason.NO_CONSENT -> if (offerCapture) R.string.audio_action_allow_capture to act.allowCapture else null
                else -> null
            }
            action?.let { (label, run) -> OutlinedButton(onClick = run, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(label)) } }
        }
    }
}

/** A section folded by default, opened with a tap on its title. */
@Composable
private fun Folded(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (open) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) { content() }
    }
}

@Composable
private fun CarMicSection(settings: Settings) {
    val ctx = LocalContext.current
    var carMic by remember { mutableStateOf(settings.carMicForPhoneApps) }
    Section(stringResource(R.string.audio_section_car_mic)) {
        Toggle(stringResource(R.string.audio_toggle_car_mic_title), stringResource(R.string.audio_toggle_car_mic_subtitle), carMic) {
            carMic = it; settings.carMicForPhoneApps = it
        }
        if (carMic) {
            val ready = CarMicForApps.available(ctx)
            Text(stringResource(when {
                ready -> R.string.audio_car_mic_ready
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> R.string.audio_car_mic_needs_android10
                else -> R.string.audio_car_mic_needs_shizuku
            }), style = MaterialTheme.typography.bodyMedium, color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SoundSection(settings: Settings) {
    var enabled by remember { mutableStateOf(settings.carAudio) }
    Section(stringResource(R.string.audio_sound_title)) {
        Toggle(stringResource(R.string.audio_toggle_enable_title), stringResource(R.string.audio_toggle_enable_subtitle), enabled) {
            enabled = it; settings.carAudio = it
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var fullVolume by remember { mutableStateOf(settings.carAudioFullVolume) }
            Toggle(stringResource(R.string.audio_toggle_full_volume_title), stringResource(R.string.audio_toggle_full_volume_subtitle), fullVolume) {
                fullVolume = it; settings.carAudioFullVolume = it
            }
        }
    }
}

@Composable
private fun stateIcon(s: State): Pair<ImageVector, Color> = when (s) {
    State.IN_USE -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
    State.BACKUP -> Icons.Rounded.RadioButtonChecked to MaterialTheme.colorScheme.primary
    State.FAILED, State.REFUSED_LAST_DRIVE -> Icons.Rounded.Cancel to MaterialTheme.colorScheme.error
    State.NEEDS_SETUP -> Icons.Rounded.Warning to MaterialTheme.colorScheme.tertiary
    State.NOT_AVAILABLE, State.NOT_TRIED -> Icons.Rounded.RemoveCircleOutline to MaterialTheme.colorScheme.outline
}

@Composable
private fun stateWord(e: CaptureStatus.Entry, connected: Boolean): String = stringResource(when (e.state) {
    State.IN_USE -> if (connected) R.string.audio_state_in_use else R.string.audio_state_expected
    State.BACKUP -> R.string.audio_state_backup
    State.FAILED -> R.string.audio_state_failed
    State.REFUSED_LAST_DRIVE -> R.string.audio_state_refused
    State.NEEDS_SETUP -> R.string.audio_state_needs_setup
    State.NOT_AVAILABLE -> R.string.audio_state_not_available
    State.NOT_TRIED -> R.string.audio_state_not_tried
})

@Composable
private fun whyText(e: CaptureStatus.Entry): String = when (e.state) {
    State.FAILED -> e.detail?.let { stringResource(R.string.audio_why_failed, it) } ?: stringResource(R.string.audio_why_failed_unknown)
    State.REFUSED_LAST_DRIVE -> stringResource(R.string.audio_why_refused,
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.atMs ?: 0L)),
        e.detail ?: stringResource(R.string.audio_why_unknown))
    State.BACKUP -> stringResource(R.string.audio_why_backup)
    State.NOT_TRIED -> stringResource(R.string.audio_why_not_tried)
    State.IN_USE -> ""
    State.NEEDS_SETUP, State.NOT_AVAILABLE -> stringResource(when (e.reason) {
        Reason.NOT_SYSTEM_APP -> R.string.audio_why_not_system_app
        Reason.ANDROID_TOO_OLD -> R.string.audio_why_android_too_old
        Reason.NOT_ROOTED -> R.string.audio_why_not_rooted
        Reason.ROOT_NOT_ALLOWED -> R.string.audio_why_root_not_allowed
        Reason.SHIZUKU_NOT_INSTALLED -> R.string.audio_why_shizuku_not_installed
        Reason.SHIZUKU_NOT_RUNNING -> R.string.audio_why_shizuku_not_running
        Reason.SHIZUKU_NOT_ALLOWED -> R.string.audio_why_shizuku_not_allowed
        Reason.NO_CONSENT, null -> R.string.audio_why_no_consent
    })
}

/** What each method needs, as tags. */
private fun tags(m: CaptureMethod): List<Int> = when (m) {
    CaptureMethod.AUDIO_POLICY -> emptyList()
    CaptureMethod.ROOT_POLICY -> listOf(R.string.audio_tag_root, R.string.audio_tag_android12)
    CaptureMethod.SHIZUKU_POLICY -> listOf(R.string.audio_tag_shizuku, R.string.audio_tag_android12)
    CaptureMethod.SHIZUKU -> listOf(R.string.audio_tag_root_or_shizuku, R.string.audio_tag_android12)
    CaptureMethod.PLAYBACK_CAPTURE -> listOf(R.string.audio_tag_authorization, R.string.audio_tag_android10)
}

private const val AUTOMATIC = "auto"
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
