package net.prototux.aare.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import net.prototux.aare.ui.car.styleSwatch
import net.prototux.aare.ui.car.Swatch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import net.prototux.aare.ui.car.railGapDp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.prototux.aare.DiagLog
import net.prototux.aare.KnownHeadUnits
import net.prototux.aare.R
import net.prototux.aare.SessionAapLog
import net.prototux.aare.Settings
import net.prototux.aare.SettingsBackup
import net.prototux.aare.BuildConfig
import net.prototux.aare.UpdateChecker
import net.prototux.aare.audio.CaptureMethod
import net.prototux.aare.audio.CaptureMethods
import net.prototux.aare.audio.CarMicForApps
import net.prototux.aare.car.CarKeyAction
import net.prototux.aare.car.DrivingMode
import net.prototux.aare.car.PhoneLocationFeed
import net.prototux.aare.car.QuirkChoice
import net.prototux.aare.car.QuirkSettings
import net.prototux.aare.cert.CertificateDownload
import net.prototux.aare.cert.CertificateStore
import net.prototux.aare.cert.CertificateUpdates
import net.prototux.aare.host.AndroidAutoApps
import net.prototux.aare.host.AssistantApp
import net.prototux.aare.host.Assistants
import net.prototux.aare.host.CarAppCatalog
import net.prototux.aare.host.CarAppInfo
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.host.PhoneApp
import net.prototux.aare.host.UnsupportedCarApp
import net.prototux.aare.mirror.MirrorTouchService
import androidx.core.graphics.drawable.toBitmap
import net.prototux.aare.launch.AppLauncher
import net.prototux.aare.launch.StandaloneDrivingRule
import net.prototux.aare.launch.effectiveDrivingRule
import net.prototux.aare.launch.isNavigationApp
import net.prototux.aare.car.CarData
import net.prototux.aare.service.ProjectionService
import net.prototux.aare.home.HomeStore
import net.prototux.aare.transport.wireless.CarCompanion
import net.prototux.aare.transport.wireless.HandshakeChoice
import net.prototux.aare.setup.SetupPermissions
import net.prototux.aare.setup.ShizukuSetup
import net.prototux.aare.setup.ShizukuSetupClient
import net.prototux.aare.ui.car.DashCard
import net.prototux.aare.ui.car.dtcText
import net.prototux.aare.ui.car.DashboardCardsEditor
import net.prototux.aare.ui.car.RailWidget
import net.prototux.aare.ui.car.RailWidgetsEditor
import net.prototux.aare.ui.car.StyleChoices
import net.prototux.aare.ui.car.WALLPAPER_NAMES
import net.prototux.aare.ui.car.movedBy
import net.prototux.aare.ui.car.withKey
import net.prototux.aare.ui.theme.CarTextSize
import net.prototux.aare.ui.theme.PhoneTheme
import rikka.shizuku.Shizuku
import java.text.DateFormat
import kotlin.math.roundToInt

/** The phone side: what's connected, what's set up, and the settings. */
class MainActivity : ComponentActivity() {
    // Each time the app comes to the front, not only when it starts: a release published while
    // AARE stayed in memory shows the next time it's opened (UpdateChecker limits how often it asks).
    override fun onResume() {
        super.onResume()
        UpdateChecker.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyDebugExtras(intent)
        setContent { PhoneTheme(dark = isSystemInDarkTheme()) { Surface(Modifier.fillMaxSize()) { Home() } } }
    }

    // singleTask: when AARE is already open, ./headunit.sh's intent arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (applyDebugExtras(intent)) {
            ProjectionService.startIdleIfEnabled(this)
            recreate() // the settings screen shows the new state
        }
    }

    /** Debug builds only: lets ./headunit.sh switch the head unit server on over adb. Returns true if applied. */
    private fun applyDebugExtras(intent: Intent?): Boolean {
        if (!net.prototux.aare.BuildConfig.DEBUG || intent?.hasExtra("hu_server") != true) return false
        Settings(this).headUnitServer = intent.getBooleanExtra("hu_server", false)
        return true
    }
}

/** The settings pages, Android Auto style: one per topic, opened from the home page. */
private enum class Page(val titleRes: Int, val subtitleRes: Int, val icon: ImageVector) {
    CAR_SCREEN(R.string.setup_page_car_screen_title, R.string.setup_page_car_screen_subtitle, Icons.Rounded.Dashboard),
    APPS(R.string.setup_page_apps_title, R.string.setup_page_apps_subtitle, Icons.Rounded.Apps),
    ASSISTANT(R.string.assistant_settings_title, R.string.assistant_page_subtitle, Icons.Rounded.Mic),
    MESSAGES(R.string.msg_settings_title, R.string.msg_page_subtitle, Icons.Rounded.Forum),
    AUDIO(R.string.audio_settings_title, R.string.audio_page_subtitle, Icons.AutoMirrored.Rounded.VolumeUp),
    CONNECTION(R.string.setup_page_connection_title, R.string.setup_page_connection_subtitle, Icons.Rounded.Bluetooth),
    CAR_DATA(R.string.setup_page_car_data_title, R.string.setup_page_car_data_subtitle, Icons.Rounded.DirectionsCar),
    HOME_ACTIONS(R.string.home_page_title, R.string.home_page_subtitle, Icons.Rounded.Home),
    ADVANCED(R.string.setup_page_advanced_title, R.string.setup_page_advanced_subtitle, Icons.Rounded.Tune),
    ABOUT(R.string.setup_page_about_title, R.string.setup_page_about_subtitle, Icons.Rounded.Info),
}

@Composable
private fun Home() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val certs = remember { CertificateStore(ctx) }
    val state by ProjectionService.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    // Bumped after a settings import: every card below is rebuilt and re-reads Settings, instead of
    // showing (and, on the next tap, writing back) the values it remembered from before.
    var settingsGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { ProjectionService.startIdleIfEnabled(ctx); ProjectionService.probeUsb(ctx); ProjectionService.probeWireless(ctx); CertificateUpdates.refresh(ctx) }
    // Re-check permissions and capture methods whenever the user comes back to the app.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = page != null) { page = null }
    OldAndroidNotice(settings)

    key(settingsGeneration) { AnimatedContent(page, label = "page", transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }) { p ->
        if (p == null) {
            LazyColumn(
                Modifier.fillMaxSize().systemBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { Header() }
                item { UpdateBanner() }
                item { CertificateExpiryBanner() }
                item { StatusCard(state) }
                item { Setup(state, refresh, { refresh++ }) { page = it } }
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                        Page.entries.forEach { CategoryRow(it) { page = it } }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back)) }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(p.titleRes), style = MaterialTheme.typography.titleLarge)
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (p) {
                        Page.CAR_SCREEN -> item { CarScreenSettings(settings, refresh) }
                        Page.APPS -> item { AppsCard(settings) }
                        Page.ASSISTANT -> item { AssistantCard(settings) }
                        Page.MESSAGES -> item { MessageSettings(settings, refresh) }
                        Page.AUDIO -> item { AudioSettings(settings, state, refresh) { refresh++ } }
                        Page.CONNECTION -> item { ConnectionSettings(settings, refresh) }
                        Page.CAR_DATA -> item { CarDataCard() }
                        Page.HOME_ACTIONS -> item { HomeActionsSettings() }
                        Page.ADVANCED -> {
                            item { HeadUnitServerSettings(settings) }
                            item { CertificatesCard(certs, state) }
                            item { VehicleOverridesCard() }
                            item { CarQuirksCard(settings) }
                            item { LogCard() }
                            item { SettingsBackupCard(settings) { settingsGeneration++ } }
                            item { CarButtonsCard(settings) }
                        }
                        Page.ABOUT -> item { AboutScreen() }
                    }
                }
            }
        }
    } }
}

@Composable
private fun CategoryRow(page: Page, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(page.icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(page.titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(page.subtitleRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DirectionsCar, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.setup_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** A quiet warning when a newer release is on GitHub: tap to view it, or dismiss it. No popup. */
@Composable
private fun UpdateBanner() {
    val ctx = LocalContext.current
    val update by UpdateChecker.available.collectAsState()
    update?.let { u ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer)
                .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.url))) }
                .padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_available_title, u.version), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(stringResource(R.string.update_available_subtitle, net.prototux.aare.BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            IconButton(onClick = { UpdateChecker.dismiss(ctx) }) {
                Icon(Icons.Rounded.Close, stringResource(R.string.common_dismiss), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

/** The Google-issued certificate expires soon or has expired (see CertificateUpdates): tap to download a newer one. */
@Composable
private fun CertificateExpiryBanner() {
    val ctx = LocalContext.current
    val expiring by CertificateUpdates.expiring.collectAsState()
    expiring?.let { e ->
        val date = DateFormat.getDateInstance().format(e.notAfter)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.errorContainer)
                .clickable { ctx.startService(Intent(ctx, ProjectionService::class.java).setAction(ProjectionService.ACTION_DOWNLOAD_CERT)) }
                .padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (e.expired) R.string.cert_expired_banner_title else R.string.cert_expiring_banner_title, date),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(stringResource(R.string.cert_expiry_banner_subtitle),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            IconButton(onClick = { CertificateUpdates.dismiss(ctx) }) {
                Icon(Icons.Rounded.Close, stringResource(R.string.common_dismiss), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/**
 * Once per launch on Android 6 and 7 (minSdk 23; Android 8 is otherwise the baseline): what falls
 * back to an older, more limited method there. "Don't show again" persists in [Settings]; OK
 * alone shows it again next launch.
 */
@Composable
private fun OldAndroidNotice(settings: Settings) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || settings.oldAndroidNoticeDismissed) return
    var open by remember { mutableStateOf(true) }
    if (!open) return
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.setup_old_android_title)) },
        text = {
            Column {
                Text(stringResource(R.string.setup_old_android_message), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Toggle(stringResource(R.string.setup_old_android_dont_show_again), "", dontShowAgain) { dontShowAgain = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (dontShowAgain) settings.oldAndroidNoticeDismissed = true
                open = false
            }) { Text(stringResource(R.string.common_ok)) }
        },
    )
}

@Composable
private fun StatusCard(state: ProjectionService.State) {
    val ctx = LocalContext.current
    val bg = if (state.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(bg).padding(24.dp)) {
        Text(if (state.connected) state.status else stringResource(R.string.notif_status_not_connected), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        val certLabel = stringResource(R.string.setup_status_certificate, state.certificate)
        val disconnectedHint = stringResource(R.string.setup_status_disconnected_hint)
        val detail = when {
            state.connected -> listOf(state.transport, state.video, certLabel).filter { it.isNotBlank() }.joinToString("\n")
            else -> disconnectedHint
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.lastError?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.setup_status_last_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // The same Trust as the notification's, for when notifications are off.
        state.pendingTrust?.let { p ->
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.notif_trust_reconnect_suffix, stringResource(R.string.notif_trust_warning)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val trustLabel = stringResource(if (p.changed) R.string.setup_trust_new_cert else R.string.setup_trust_head_unit)
            Pill(stringResource(R.string.setup_trust_fingerprint_suffix, trustLabel, p.fingerprint.take(16)), null) { ProjectionService.trust(ctx, p.peerKey, p.fingerprint) }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.setup_preview_car_screen), Icons.Rounded.Tv) { ctx.startActivity(Intent(ctx, CarPreviewActivity::class.java)) }
            if (state.connected) Pill(stringResource(R.string.setup_disconnect), null) { ProjectionService.stop(ctx) }
        }
    }
}

@Composable
internal fun Pill(label: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------- setup checklist

@Composable
private fun Setup(state: ProjectionService.State, refresh: Int, recheck: () -> Unit, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val activity = ctx as? android.app.Activity
    val settings = remember { Settings(ctx) }
    fun rationale(p: String) = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, p)
    // The rationale of each permission just before the request, and the ones asked for in an earlier one.
    var rationaleBefore by remember { mutableStateOf(emptySet<String>()) }
    var askedBefore by remember { mutableStateOf(emptySet<String>()) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        recheck()
        // Denied twice, a permission is no longer asked for: the request answers at once and the row
        // would seem dead, so open AARE's system settings to grant it there.
        val blocked = activity != null && result.filterValues { !it }.keys.any {
            permissionBlocked(it in rationaleBefore, rationale(it), it in askedBefore)
        }
        if (blocked) ctx.startSafely(appDetails(ctx))
    }
    val runtimePerms = SetupPermissions.forSdk(Build.VERSION.SDK_INT)
    @Suppress("UNUSED_EXPRESSION") refresh
    val perms = runtimePerms.all(::granted)
    val notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    val audio = CaptureMethods.audioPolicyAvailable(ctx) || CaptureMethods.shizukuAvailable() || CaptureMethods.rootAvailable(ctx) || state.audioCapture
    // Shown even once everything is granted: the result is the confirmation.
    var granted by remember { mutableStateOf<List<ShizukuSetupClient.Item>?>(null) }
    granted?.let { ShizukuGrantResult(it) { granted = null } }
    if (perms && notificationAccess && audio) return

    val scope = rememberCoroutineScope()
    var granting by remember { mutableStateOf(false) }
    Section(stringResource(R.string.setup_finish_title)) {
        if ((!perms || !notificationAccess) && ShizukuSetupClient.available()) {
            Check(stringResource(R.string.shizuku_setup_title),
                stringResource(if (granting) R.string.shizuku_setup_working else R.string.shizuku_setup_subtitle), false) {
                if (granting) return@Check
                granting = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { ShizukuSetupClient(ctx).grantAll() }
                    granting = false
                    if (result == null) Toast.makeText(ctx, R.string.shizuku_setup_helper_failed, Toast.LENGTH_LONG).show()
                    else granted = result
                    recheck()
                }
            }
        }
        Check(stringResource(R.string.setup_check_permissions_title), stringResource(R.string.setup_check_permissions_subtitle), perms) {
            rationaleBefore = runtimePerms.filter(::rationale).toSet()
            askedBefore = settings.requestedPermissions
            settings.requestedPermissions = askedBefore + runtimePerms
            permissions.launch(runtimePerms.toTypedArray())
        }
        Check(stringResource(R.string.setup_check_notification_access_title), stringResource(R.string.setup_check_notification_access_subtitle), notificationAccess) {
            ctx.startSafely(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appDetails(ctx))
        }
        Check(stringResource(R.string.audio_settings_title), stringResource(R.string.setup_check_audio_subtitle), audio) { open(Page.AUDIO) }
    }
}

/** What "Grant AARE's permissions with Shizuku" did, one line per permission. */
@Composable
private fun ShizukuGrantResult(items: List<ShizukuSetupClient.Item>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shizuku_setup_result_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (items.isEmpty()) Text(stringResource(R.string.shizuku_setup_nothing_to_grant))
                items.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (item.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, Modifier.size(20.dp),
                            tint = if (item.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(item.label, style = MaterialTheme.typography.bodyMedium)
                            if (!item.ok) Text(stringResource(R.string.shizuku_setup_not_granted, item.detail),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
    )
}

@Composable
private fun Check(title: String, subtitle: String, done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = !done, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done) Text(stringResource(R.string.setup_action_set_up), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Whether a permission the request just denied can no longer be asked for (main setup row). Since Android 11,
 * backing out of the dialog also denies with no rationale, as before any request: so it counts as refused for
 * good only if the rationale went from shown to gone (refused a second time), or it was asked for in an earlier
 * request and still has none (blocked back then).
 */
internal fun permissionBlocked(rationaleBefore: Boolean, rationaleAfter: Boolean, askedBefore: Boolean): Boolean =
    !rationaleAfter && (rationaleBefore || askedBefore)

/** AARE's page in the system settings, where every permission and access can be granted by hand. */
private fun appDetails(ctx: Context) =
    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))

/**
 * Opens a settings screen or a link. Some phones lack the screen (Go builds and OEM ROMs drop some)
 * or have no browser: then [fallback] opens instead, or a short message, rather than a crash. No
 * resolveActivity() check first: package visibility hides browsers from it without a <queries> entry.
 */
private fun Context.startSafely(intent: Intent, fallback: Intent? = null) {
    val opened = listOfNotNull(intent, fallback).any { runCatching { startActivity(it) }.isSuccess }
    if (!opened) Toast.makeText(this, R.string.setup_nothing_to_open, Toast.LENGTH_SHORT).show()
}

// ---------------------------------------------------------------- settings

@Composable
private fun ConnectionSettings(settings: Settings, refresh: Int) {
    var wireless by remember { mutableStateOf(settings.wireless) }
    var wirelessCompat by remember { mutableStateOf(settings.wirelessCompatibility) }
    var checkUpdates by remember { mutableStateOf(settings.checkUpdates) }
    // What "Check now" found, shown next to it; [checking] while it asks.
    var updateStatus by remember { mutableStateOf<String?>(null) }
    val checking = stringResource(R.string.update_checking)
    var fps60 by remember { mutableStateOf(settings.prefer60fps) }
    var maxH by remember { mutableIntStateOf(settings.maxVideoHeight) }
    var forceH264 by remember { mutableStateOf(settings.forceH264) }
    var constantBitrate by remember { mutableStateOf(settings.constantBitrateVideo) }
    var resume by remember { mutableStateOf(settings.resumeMedia) }
    var callsOnSpeaker by remember { mutableStateOf(settings.callsOnPhoneSpeaker) }
    var autoPair by remember { mutableStateOf(settings.autoPairCarBluetooth) }
    var cars by remember { mutableStateOf(settings.knownCars) }
    var carGps by remember { mutableStateOf(settings.carGpsForPhone) }
    var btAddr by remember { mutableStateOf(settings.phoneBluetoothAddress) }
    val ctx = LocalContext.current
    val pinned = remember { net.prototux.aare.PinnedHeadUnits(ctx) }
    var pins by remember { mutableStateOf(pinned.all) }
    // A head unit trusted from the notification while this screen is open shows up right away.
    DisposableEffect(pinned) { onDispose(pinned.listen { pins = pinned.all }) }
    // Read here, not in the Section's lambda, so coming back from Developer options re-checks it.
    @Suppress("UNUSED_EXPRESSION") refresh
    val mockAllowed = PhoneLocationFeed.allowed(ctx)
    // Opt-in: only the user can grant it, in the app's settings (Location > Allow all the time).
    val backgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.setup_section_connecting)) {
            Toggle(stringResource(R.string.setup_toggle_wireless_title), stringResource(R.string.setup_toggle_wireless_subtitle), wireless) { wireless = it; settings.wireless = it; if (it) ProjectionService.probeWireless(ctx) }
            if (wireless) {
                Choices(stringResource(R.string.setup_choice_wireless_compat), listOf(
                    HandshakeChoice.AUTO to stringResource(R.string.setup_choice_wireless_compat_auto),
                    HandshakeChoice.BUILT_IN to stringResource(R.string.setup_choice_wireless_compat_builtin),
                    HandshakeChoice.ADAPTER to stringResource(R.string.setup_choice_wireless_compat_adapter),
                ), wirelessCompat) { wirelessCompat = it; settings.wirelessCompatibility = it }
                Text(stringResource(R.string.setup_hint_wireless_compat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            Toggle(stringResource(R.string.setup_toggle_resume_music_title), stringResource(R.string.setup_toggle_resume_music_subtitle), resume) { resume = it; settings.resumeMedia = it }
            Toggle(stringResource(R.string.setup_toggle_calls_speaker_title), stringResource(R.string.setup_toggle_calls_speaker_subtitle), callsOnSpeaker) {
                callsOnSpeaker = it; settings.callsOnPhoneSpeaker = it
            }
            Toggle(stringResource(R.string.setup_toggle_auto_pair_title), stringResource(R.string.setup_toggle_auto_pair_subtitle), autoPair) {
                autoPair = it; settings.autoPairCarBluetooth = it
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(btAddr, { btAddr = it; settings.phoneBluetoothAddress = it.trim() }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setup_bt_address_label)) },
                supportingText = { Text(stringResource(R.string.setup_bt_address_hint)) },
                singleLine = true)
        }
        if (wireless && CarCompanion.supported(ctx)) CompanionCars(settings, pins.keys)
        AndroidAutoCard(refresh)
        Section(stringResource(R.string.setup_section_updates)) {
            Toggle(stringResource(R.string.setup_toggle_updates_title), stringResource(R.string.setup_toggle_updates_subtitle), checkUpdates) {
                checkUpdates = it; settings.checkUpdates = it; UpdateChecker.refresh(ctx, force = it)
                updateStatus = null
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = checkUpdates && updateStatus != checking, onClick = {
                    updateStatus = checking
                    UpdateChecker.checkNow(ctx) { r ->
                        updateStatus = when {
                            r.version == null -> ctx.getString(R.string.update_check_failed)
                            r.newer -> ctx.getString(R.string.update_check_available, r.version)
                            else -> ctx.getString(R.string.update_check_latest, BuildConfig.VERSION_NAME)
                        }
                    }
                }) { Text(stringResource(R.string.update_check_now)) }
                updateStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Section(stringResource(R.string.setup_section_location)) {
            Toggle(stringResource(R.string.setup_toggle_car_gps_title), stringResource(R.string.setup_toggle_car_gps_subtitle), carGps) {
                carGps = it; settings.carGpsForPhone = it
            }
            if (carGps) Check(stringResource(R.string.setup_check_mock_location_title), stringResource(R.string.setup_check_mock_location_subtitle), mockAllowed) {
                ctx.startSafely(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), appDetails(ctx))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Check(stringResource(R.string.setup_check_background_location_title), stringResource(R.string.setup_check_background_location_subtitle),
                    backgroundLocation) { ctx.startSafely(appDetails(ctx)) }
            }
        }
        Section(stringResource(R.string.setup_section_video)) {
            Toggle(stringResource(R.string.setup_toggle_60fps_title), stringResource(R.string.setup_toggle_60fps_subtitle), fps60) { fps60 = it; settings.prefer60fps = it }
            Choices(stringResource(R.string.setup_choice_max_resolution), listOf(480, 720, 1080, 1440).map { "$it" to "${it}p" }, "$maxH") {
                maxH = it.toInt(); settings.maxVideoHeight = maxH
            }
            Text(stringResource(R.string.setup_hint_lower_resolution), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Toggle(stringResource(R.string.setup_toggle_force_h264_title), stringResource(R.string.setup_toggle_force_h264_subtitle), forceH264) { forceH264 = it; settings.forceH264 = it }
            Toggle(stringResource(R.string.setup_toggle_constant_bitrate_title), stringResource(R.string.setup_toggle_constant_bitrate_subtitle), constantBitrate) {
                constantBitrate = it; settings.constantBitrateVideo = it
            }
        }
        Section(stringResource(R.string.setup_section_your_cars)) {
            if (cars.isEmpty()) Text(stringResource(R.string.setup_cars_empty), style = MaterialTheme.typography.bodyMedium)
            cars.entries.sortedByDescending { it.value }.forEach { (name, time) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.setup_car_last_connected, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(time)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { settings.forgetCar(name); cars = settings.knownCars }) { Text(stringResource(R.string.setup_action_forget)) }
                }
            }
        }
        Section(stringResource(R.string.setup_section_trusted_head_units)) {
            Text(stringResource(R.string.setup_trusted_head_units_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pins.isEmpty()) Text(stringResource(R.string.setup_trusted_head_units_empty), style = MaterialTheme.typography.bodyMedium)
            pins.entries.sortedBy { it.key }.forEach { (key, shas) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(headUnitLabel(ctx, key), style = MaterialTheme.typography.bodyLarge)
                        // stringResource can't run inside joinToString's lambda: get the template once, format each.
                        val fpTemplate = stringResource(R.string.setup_sha256_fingerprint)
                        Text(shas.joinToString { fpTemplate.format(it.take(16)) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { pinned.forget(key); pins = pinned.all }) { Text(stringResource(R.string.setup_action_forget)) }
                }
            }
        }
    }
}

/**
 * One-tap wireless: cars associated as companion devices start the wireless connection when their
 * Bluetooth connects, and can have their Wi-Fi approved through Shizuku. Offered for the cars
 * already used wirelessly: their Bluetooth address is a trusted head unit's key.
 */
@Composable
private fun CompanionCars(settings: Settings, trustedKeys: Set<String>) {
    val ctx = LocalContext.current
    var cars by remember { mutableStateOf(CarCompanion.cars(ctx)) }
    var shizukuWifi by remember { mutableStateOf(settings.companionWifiShizuku) }
    var failure by remember { mutableStateOf<String?>(null) }
    // The association's own callback reports the result; this only notices a dismissed dialog.
    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { cars = CarCompanion.cars(ctx) }
    val candidates = trustedKeys.map { it.uppercase() }.filter { android.bluetooth.BluetoothAdapter.checkBluetoothAddress(it) && cars.none { c -> c.address == it } }.sorted()
    Section(stringResource(R.string.setup_section_companion)) {
        Text(stringResource(R.string.setup_companion_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        cars.forEach { car ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text(car.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { CarCompanion.remove(ctx, car); cars = CarCompanion.cars(ctx) }) { Text(stringResource(R.string.setup_action_forget)) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) candidates.forEach { address ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(headUnitLabel(ctx, address), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = {
                    failure = null
                    CarCompanion.associate(ctx, address,
                        onPending = { confirm.launch(IntentSenderRequest.Builder(it).build()) },
                        onDone = { ok, why -> cars = CarCompanion.cars(ctx); failure = if (ok) null else why.orEmpty() })
                }) { Text(stringResource(R.string.setup_action_associate)) }
            }
        }
        if (cars.isEmpty() && candidates.isEmpty()) Text(stringResource(R.string.setup_companion_empty), style = MaterialTheme.typography.bodyMedium)
        failure?.let { Text(stringResource(R.string.setup_companion_failed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (cars.isNotEmpty()) Toggle(stringResource(R.string.setup_toggle_companion_shizuku_title), stringResource(R.string.setup_toggle_companion_shizuku_subtitle), shizukuWifi) {
            shizukuWifi = it; settings.companionWifiShizuku = it
        }
    }
}

/**
 * Google's Android Auto answers the same cars: switch it off, and on again, through Shizuku
 * (pm disable-user / pm enable, never uninstalling it or clearing its data). Hidden when it isn't installed.
 */
@Composable
private fun AndroidAutoCard(refresh: Int) {
    val ctx = LocalContext.current
    val client = remember { ShizukuSetupClient(ctx) }
    var changed by remember { mutableIntStateOf(0) }
    val state = remember(refresh, changed) { client.androidAutoState() }
    if (state == ShizukuSetup.AndroidAutoState.NOT_INSTALLED) return
    val shizuku = ShizukuSetupClient.available()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    fun setEnabled(enabled: Boolean) {
        busy = true
        failure = null
        scope.launch {
            failure = withContext(Dispatchers.IO) { client.setAndroidAutoEnabled(enabled) }
            busy = false
            changed++
        }
    }
    Section(stringResource(R.string.aa_section_title)) {
        val enabled = state == ShizukuSetup.AndroidAutoState.ENABLED
        Text(stringResource(if (enabled) R.string.aa_state_enabled else R.string.aa_state_disabled), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        when {
            busy -> Text(stringResource(R.string.aa_working), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            !shizuku -> {
                Text(stringResource(R.string.aa_needs_shizuku), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    ctx.startSafely(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ShizukuSetup.ANDROID_AUTO, null)))
                }) { Text(stringResource(R.string.aa_app_info)) }
            }
            enabled -> Pill(stringResource(R.string.aa_action_disable), null) { confirm = true }
            else -> Pill(stringResource(R.string.aa_action_enable), null) { setEnabled(true) }
        }
        failure?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.aa_failed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.aa_confirm_title)) },
        text = { Text(stringResource(R.string.aa_confirm_text)) },
        confirmButton = { TextButton(onClick = { confirm = false; setEnabled(false) }) { Text(stringResource(R.string.aa_confirm_action)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** A trusted head unit's key as the user knows it: the paired car's name, the USB accessory, or the developer server. */
@android.annotation.SuppressLint("MissingPermission") // wrapped: without BLUETOOTH_CONNECT the address is shown
private fun headUnitLabel(ctx: android.content.Context, key: String): String = when {
    key == "tcp" -> ctx.getString(R.string.huserver_label)
    key.startsWith("usb:") -> key.removePrefix("usb:").replace('|', ' ').trim().ifBlank { key }
    else -> runCatching { ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.getRemoteDevice(key)?.name }.getOrNull() ?: key
}

/** Android Auto's developer "head unit server": for the desktop head unit and other test tools. */
@Composable
private fun HeadUnitServerSettings(settings: Settings) {
    val ctx = LocalContext.current
    var huServer by remember { mutableStateOf(settings.headUnitServer) }
    var lan by remember { mutableStateOf(settings.headUnitServerLan) }
    // The service switches the LAN option off when a session ends: pick that up.
    val connected = ProjectionService.state.collectAsState().value.connected
    LaunchedEffect(connected) { lan = settings.headUnitServerLan }
    var lanOnce by remember { mutableStateOf(settings.headUnitServerLanOnce) }
    var mirror by remember { mutableStateOf(settings.headUnitServerMirror) }
    var localhost by remember { mutableStateOf(settings.headUnitServerLocalhost) }
    Section(stringResource(R.string.huserver_label)) {
        Toggle(stringResource(R.string.huserver_label), stringResource(R.string.huserver_toggle_subtitle), huServer) {
            huServer = it; settings.headUnitServer = it
            // Off only closes the listener: the service stays for a session, a wireless attempt or the
            // audio consent, and goes away by itself when it has nothing left.
            ProjectionService.startIdleIfEnabled(ctx)
        }
        if (huServer) Toggle(stringResource(R.string.huserver_toggle_network_title), stringResource(R.string.huserver_toggle_network_subtitle), lan) {
            lan = it; settings.headUnitServerLan = it
            ProjectionService.startIdleIfEnabled(ctx)
        }
        if (huServer) Toggle(stringResource(R.string.huserver_toggle_lanonce_title), stringResource(R.string.huserver_toggle_lanonce_subtitle), lanOnce) {
            lanOnce = it; settings.headUnitServerLanOnce = it
        }
        if (huServer) Toggle(stringResource(R.string.huserver_toggle_mirror_title), stringResource(R.string.huserver_toggle_mirror_subtitle), mirror) {
            mirror = it; settings.headUnitServerMirror = it
        }
        if (huServer) Toggle(stringResource(R.string.huserver_toggle_localhost_title), stringResource(R.string.huserver_toggle_localhost_subtitle), localhost) {
            localhost = it; settings.headUnitServerLocalhost = it
            ProjectionService.startIdleIfEnabled(ctx)
        }
    }
}

@Composable
private fun MessageSettings(settings: Settings, refresh: Int) {
    val ctx = LocalContext.current
    var banners by remember { mutableStateOf(settings.messageBanners) }
    var groups by remember { mutableStateOf(settings.groupMessages) }
    var aloud by remember { mutableStateOf(settings.readMessagesAloud) }
    var dnd by remember { mutableStateOf(settings.dndWhileDriving) }
    var autoReply by remember { mutableStateOf(settings.autoReply) }
    var voiceReply by remember { mutableStateOf(settings.voiceReply) }
    var replyText by remember { mutableStateOf(settings.autoReplyText) }
    @Suppress("UNUSED_EXPRESSION") refresh
    val access = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Section(stringResource(R.string.msg_settings_title)) {
        if (!access) Check(stringResource(R.string.setup_check_notification_access_title), stringResource(R.string.msg_check_notification_access_subtitle), false) {
            ctx.startSafely(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS), appDetails(ctx))
        }
        Toggle(stringResource(R.string.msg_toggle_banners_title), stringResource(R.string.msg_toggle_banners_subtitle), banners) { banners = it; settings.messageBanners = it }
        Toggle(stringResource(R.string.msg_toggle_group_title), stringResource(R.string.msg_toggle_group_subtitle), groups) { groups = it; settings.groupMessages = it }
        Toggle(stringResource(R.string.msg_toggle_read_aloud_title), stringResource(R.string.msg_toggle_read_aloud_subtitle), aloud) { aloud = it; settings.readMessagesAloud = it }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.msg_hint_apps),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Section(stringResource(R.string.msg_section_while_driving)) {
        Toggle(stringResource(R.string.msg_toggle_dnd_title), stringResource(R.string.msg_toggle_dnd_subtitle), dnd) {
            dnd = it; settings.dndWhileDriving = it
        }
        if (dnd) Check(stringResource(R.string.msg_check_dnd_access_title), stringResource(R.string.msg_check_dnd_access_subtitle), DrivingMode.accessGranted(ctx)) {
            ctx.startSafely(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), appDetails(ctx))
        }
        Toggle(stringResource(R.string.msg_toggle_autoreply_title), stringResource(R.string.msg_toggle_autoreply_subtitle), autoReply) {
            autoReply = it; settings.autoReply = it
        }
        if (autoReply) OutlinedTextField(replyText, { replyText = it; settings.autoReplyText = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.msg_autoreply_field_label)) }, singleLine = true)
        Toggle(stringResource(R.string.msg_toggle_voice_reply_title), stringResource(R.string.msg_toggle_voice_reply_subtitle), voiceReply) {
            voiceReply = it; settings.voiceReply = it
        }
    }
    }
}

@Composable
internal fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CarScreenSettings(settings: Settings, refresh: Int) {
    val ctx = LocalContext.current
    var theme by remember { mutableStateOf(settings.carTheme) }
    var nightSource by remember { mutableStateOf(settings.nightSource) }
    var themeId by remember { mutableStateOf(settings.carThemeId) }
    var layoutId by remember { mutableStateOf(settings.carLayoutId) }
    var accent by remember { mutableStateOf(settings.carAccent) }
    var rail by remember { mutableStateOf(settings.railPosition) }
    var chrome by remember { mutableStateOf(settings.carChrome) }
    var phoneScreen by remember { mutableStateOf(settings.phoneScreen) }
    var darkBar by remember { mutableStateOf(settings.darkBar) }
    var splitScreen by remember { mutableStateOf(settings.splitScreen) }
    var railWidgets by remember { mutableStateOf(RailWidget.parse(settings.railWidgets)) }
    var textSize by remember { mutableStateOf(settings.carTextSize) }
    var controlSize by remember { mutableStateOf(settings.carControlSize) }
    var cards by remember { mutableStateOf(DashCard.parse(settings.dashboardCards)) }
    var mapBig by remember { mutableStateOf(settings.dashboardMapBig) }
    var passenger by remember { mutableStateOf(settings.ignoreDriving) }
    var weather by remember { mutableStateOf(settings.showWeather) }
    var agendaCount by remember { mutableStateOf(settings.calendarAgendaCount) }
    var wallpaper by remember { mutableStateOf(settings.wallpaper) }
    var mapInset by remember { mutableStateOf(settings.mapCardBottomInset) }
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { saveWallpaper(ctx, uri) }
            if (ok) {
                // Through "none", so the car reloads the photo even if one was already set.
                settings.wallpaper = "none"; settings.wallpaper = Settings.WALLPAPER_CUSTOM
                wallpaper = Settings.WALLPAPER_CUSTOM
            } else {
                Toast.makeText(ctx, R.string.setup_wallpaper_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") refresh
    val touch = AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        .split(':').any { ComponentName.unflattenFromString(it) == ComponentName(ctx, MirrorTouchService::class.java) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.setup_section_look)) {
            StyleChoices(
                themeId, layoutId, accent,
                choice = { title, options, selected, onSelect -> Choices(title, options, selected, onSelect) },
                onChange = { t, l -> themeId = t; layoutId = l; settings.carThemeId = t; settings.carLayoutId = l },
                onAccent = { accent = it; settings.carAccent = it },
                select = { title, options, selected, onSelect -> Select(title, options, selected, ::styleSwatch, onSelect) },
            )
            Spacer(Modifier.height(12.dp))
            Choices(stringResource(R.string.setup_choice_colors), listOf(Settings.THEME_AUTO to stringResource(R.string.setup_choice_colors_auto), Settings.THEME_DARK to stringResource(R.string.setup_choice_dark), Settings.THEME_LIGHT to stringResource(R.string.setup_choice_light)), theme) {
                theme = it; settings.carTheme = it
            }
            // Only matters with "Car's day/night": a fixed theme has no night mode to source.
            if (theme == Settings.THEME_AUTO) {
                Spacer(Modifier.height(12.dp))
                Choices(stringResource(R.string.setup_choice_night_source), listOf(
                    Settings.NIGHT_SOURCE_CAR to stringResource(R.string.setup_choice_night_source_car),
                    Settings.NIGHT_SOURCE_LIGHT_SENSOR to stringResource(R.string.setup_choice_night_source_light_sensor),
                ), nightSource) { nightSource = it; settings.nightSource = it }
            }
            Spacer(Modifier.height(12.dp))
            Select(stringResource(R.string.setup_choice_bar), listOf(Settings.RAIL_AUTO to stringResource(R.string.setup_choice_bar_auto), Settings.RAIL_LEFT to stringResource(R.string.setup_choice_left), Settings.RAIL_RIGHT to stringResource(R.string.setup_choice_right), Settings.RAIL_BOTTOM to stringResource(R.string.setup_choice_bottom)), rail) {
                rail = it; settings.railPosition = it
            }
            Spacer(Modifier.height(12.dp))
            Choices(stringResource(R.string.setup_choice_status_items), listOf(Settings.CHROME_AUTO to stringResource(R.string.setup_choice_status_items_auto), Settings.CHROME_SHOW to stringResource(R.string.setup_choice_status_items_show), Settings.CHROME_HIDE to stringResource(R.string.setup_choice_status_items_hide)), chrome) {
                chrome = it; settings.carChrome = it
            }
            Text(stringResource(R.string.setup_hint_status_items), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Toggle(stringResource(R.string.setup_toggle_dark_bar_title), stringResource(R.string.setup_toggle_dark_bar_subtitle), darkBar) { darkBar = it; settings.darkBar = it }
            Toggle(stringResource(R.string.setup_toggle_split_screen_title), stringResource(R.string.setup_toggle_split_screen_subtitle), splitScreen) {
                splitScreen = it; settings.splitScreen = it
            }
            Spacer(Modifier.height(12.dp))
            RailWidgetsEditor(railWidgets, 48.dp) { railWidgets = it; settings.railWidgets = it.map(RailWidget::id) }
            Spacer(Modifier.height(12.dp))
            val sizeOptions = listOf(
                CarTextSize.DEFAULT.id to stringResource(R.string.setup_choice_size_default),
                CarTextSize.LARGER.id to stringResource(R.string.setup_choice_size_larger),
                CarTextSize.LARGEST.id to stringResource(R.string.setup_choice_size_largest),
            )
            // Text and control size are independent settings (one changes the font scale, the
            // other CarDimens' touch targets), so a user who needs only one doesn't grow both.
            Choices(stringResource(R.string.setup_choice_text_size), sizeOptions, textSize) { textSize = it; settings.carTextSize = it }
            Spacer(Modifier.height(12.dp))
            Choices(stringResource(R.string.setup_choice_control_size), sizeOptions, controlSize) { controlSize = it; settings.carControlSize = it }
        }
        Section(stringResource(R.string.setup_section_dashboard)) {
            Select(stringResource(R.string.setup_choice_wallpaper), listOf("none" to stringResource(R.string.setup_wallpaper_none)) + WALLPAPER_NAMES.map { it to it.replaceFirstChar(Char::uppercase) } +
                listOf(Settings.WALLPAPER_CUSTOM to stringResource(R.string.setup_wallpaper_photo)), wallpaper) { choice ->
                if (choice == Settings.WALLPAPER_CUSTOM) photoPicker.launch("image/*")
                else { wallpaper = choice; settings.wallpaper = choice }
            }
            Spacer(Modifier.height(8.dp))
            Toggle(stringResource(R.string.setup_toggle_weather_title), stringResource(R.string.setup_toggle_weather_subtitle), weather) {
                weather = it; settings.showWeather = it
            }
            Spacer(Modifier.height(8.dp))
            DashboardCardsEditor(cards, 48.dp) { cards = it; settings.dashboardCards = it.map(DashCard::id) }
            Spacer(Modifier.height(12.dp))
            Choices(stringResource(R.string.setup_choice_map_inset), listOf("0" to stringResource(R.string.setup_map_inset_none), "24" to stringResource(R.string.setup_map_inset_small),
                "40" to stringResource(R.string.setup_map_inset_medium), "56" to stringResource(R.string.setup_map_inset_large)), mapInset.toString()) {
                mapInset = it.toInt(); settings.mapCardBottomInset = mapInset
            }
            Text(stringResource(R.string.setup_hint_map_inset),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Toggle(stringResource(R.string.dash_map_big_title), stringResource(R.string.dash_map_big_subtitle), mapBig) {
                mapBig = it; settings.dashboardMapBig = it
            }
            Spacer(Modifier.height(12.dp))
            Choices(stringResource(R.string.setup_choice_agenda_count), (1..5).map { it.toString() to it.toString() }, agendaCount.toString()) {
                agendaCount = it.toInt(); settings.calendarAgendaCount = it.toInt()
            }
            Text(stringResource(R.string.setup_agenda_count_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Section(stringResource(R.string.setup_section_driving)) {
            Toggle(stringResource(R.string.setup_toggle_passenger_title), stringResource(R.string.setup_toggle_passenger_subtitle), passenger) {
                passenger = it; settings.ignoreDriving = it
            }
        }
        Section(stringResource(R.string.phone_section_title)) {
            Toggle(stringResource(R.string.phone_toggle_enable_title), stringResource(R.string.phone_toggle_enable_subtitle), phoneScreen) {
                phoneScreen = it; settings.phoneScreen = it
            }
            if (phoneScreen) {
                Check(stringResource(R.string.phone_check_touch_title), stringResource(R.string.phone_check_touch_subtitle), touch) {
                    ctx.startSafely(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS), appDetails(ctx))
                }
                Text(stringResource(R.string.phone_hint_touch_capture),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Copies the chosen photo into AARE's files, at most 1920 pixels on its longest side, as the car's wallpaper. */
private fun saveWallpaper(context: android.content.Context, uri: Uri): Boolean {
    // Written to a temporary file and renamed over the current one only once complete, so a photo
    // that can't be read or written (a full disk) leaves the current wallpaper alone.
    val tmp = java.io.File(context.filesDir, Settings.WALLPAPER_FILE + ".tmp")
    val ok = runCatching {
        val bmp = decodeWallpaper(context, uri, 1920)
        tmp.outputStream().use { check(bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        check(tmp.renameTo(java.io.File(context.filesDir, Settings.WALLPAPER_FILE)))
    }.isSuccess
    if (!ok) tmp.delete()
    return ok
}

/**
 * Decodes [uri] upright (cameras store portrait photos sideways with an EXIF orientation tag) and
 * already scaled down towards [maxPx], so a 200 MP photo is never decoded at full size.
 */
private fun decodeWallpaper(context: android.content.Context, uri: Uri, maxPx: Int): Bitmap {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder applies the EXIF orientation itself.
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // compress() needs a software bitmap
            val (w, h) = info.size.width to info.size.height
            val scale = maxPx.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
        }
    }
    // Android 8: a power-of-two sample size, then the exact scale and the EXIF rotation in one matrix.
    // Each pass opens its own stream: a content stream can be read only once.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    val src = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: error("can't decode the photo")
    val orientation = runCatching {
        resolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val scale = minOf(1f, maxPx.toFloat() / maxOf(src.width, src.height))
    val m = Matrix().apply {
        postScale(scale, scale)
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
        }
    }
    val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    if (out !== src) src.recycle()
    return out
}

/** Car audio: the capture methods, best first, and what each needs. */
@Composable
private fun AudioSettings(settings: Settings, state: ProjectionService.State, refresh: Int, recheck: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(settings.carAudio) }
    var carMic by remember { mutableStateOf(settings.carMicForPhoneApps) }
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
    val policy = CaptureMethods.audioPolicyAvailable(ctx)
    val shizuku = CaptureMethods.shizukuAvailable()
    val shizukuAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && CaptureMethods.shizukuNeedsPermission()
    val root = CaptureMethods.rootAvailable(ctx)
    val best = when {
        policy -> CaptureMethod.AUDIO_POLICY
        shizuku -> CaptureMethod.SHIZUKU_POLICY
        root -> CaptureMethod.ROOT_POLICY
        state.audioCapture -> CaptureMethod.PLAYBACK_CAPTURE
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.audio_settings_title)) {
            Toggle(stringResource(R.string.audio_toggle_enable_title), stringResource(R.string.audio_toggle_enable_subtitle), enabled) {
                enabled = it; settings.carAudio = it
            }
            Spacer(Modifier.height(4.dp))
            Text(best?.let { stringResource(R.string.audio_using_method, stringResource(it.title)) } ?: stringResource(R.string.audio_no_method),
                style = MaterialTheme.typography.bodyMedium, color = if (best == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Section(stringResource(R.string.audio_section_methods)) {
            Check(stringResource(CaptureMethod.AUDIO_POLICY.title),
                if (policy) stringResource(R.string.audio_policy_ready)
                else stringResource(R.string.audio_policy_not_ready),
                policy) {
                ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/prototux/AARE#car-audio")))
            }
            Check(stringResource(CaptureMethod.SHIZUKU.title),
                when {
                    shizuku -> stringResource(R.string.audio_shizuku_ready)
                    root -> stringResource(R.string.audio_root_ready)
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> stringResource(R.string.audio_shizuku_needs_android12)
                    shizukuAsk -> stringResource(R.string.audio_shizuku_tap_allow)
                    else -> stringResource(R.string.audio_shizuku_or_root_hint)
                },
                shizuku || root) {
                when {
                    shizukuAsk -> runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST) }
                    shizuku || root -> Unit
                    // Check for root (prompts su on a rooted phone); otherwise point to Shizuku.
                    else -> CaptureMethods.checkRoot { granted ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (granted) { settings.rootAudio = true; recheck() }
                            else ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                        }
                    }
                }
            }
            Check(stringResource(CaptureMethod.PLAYBACK_CAPTURE.title),
                if (state.audioCapture) stringResource(R.string.audio_playback_ready)
                else stringResource(R.string.audio_playback_not_ready),
                state.audioCapture) {
                val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
                consent.launch(if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    else mpm.createScreenCaptureIntent())
            }
        }
        Section(stringResource(R.string.audio_section_car_mic)) {
            Toggle(stringResource(R.string.audio_toggle_car_mic_title), stringResource(R.string.audio_toggle_car_mic_subtitle), carMic) {
                carMic = it; settings.carMicForPhoneApps = it
            }
            if (carMic) {
                val ready = CarMicForApps.available()
                Text(stringResource(when {
                    ready -> R.string.audio_car_mic_ready
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> R.string.audio_car_mic_needs_android10
                    else -> R.string.audio_car_mic_needs_shizuku
                }), style = MaterialTheme.typography.bodyMedium, color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}

private const val SHIZUKU_REQUEST = 42

/** A labelled drop-down of mutually exclusive options, for lists too long for a row of [Choices]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Select(title: String, options: List<Pair<String, String>>, selected: String, swatch: (String) -> List<Color> = { emptyList() }, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(6.dp))
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            leadingIcon = swatch(selected).takeIf { it.isNotEmpty() }?.let { { Swatch(it, 20.dp) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { expanded = false; onSelect(value) },
                    leadingIcon = swatch(value).takeIf { it.isNotEmpty() }?.let { { Swatch(it, 20.dp) } },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** A labelled row of mutually exclusive options. */
@Composable
internal fun Choices(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(6.dp))
    // One height for the row, so a label that wraps doesn't leave its segment taller than the rest.
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        options.forEachIndexed { i, (value, label) ->
            // No check mark: the filled segment already shows the choice, and the mark took the
            // room a longer word needs ("Fahrerseite", "Barre des tâches"). Those wrap, not cut.
            SegmentedButton(selected = value == selected, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(i, options.size), modifier = Modifier.fillMaxHeight(), icon = {}) {
                Text(label, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ---------------------------------------------------------------- apps

/** Everything the car's launcher lists, as it groups it. */
private class LauncherLists(val carApps: List<CarAppInfo>, val media: List<PhoneApp>, val messaging: List<PhoneApp>)

@Composable
private fun AppsCard(settings: Settings) {
    val ctx = LocalContext.current
    var hidden by remember { mutableStateOf(settings.hiddenApps) }
    var phoneScreen by remember { mutableStateOf(settings.phoneScreen) }
    var browser by remember { mutableStateOf(settings.browser) }
    var browserUrl by remember { mutableStateOf(settings.browserUrl) }
    // Same rules as the car's launcher (CarRuntime.applyApps): an app with a car app shows once, as that.
    val lists by produceState<LauncherLists?>(null) {
        value = withContext(Dispatchers.IO) {
            val car = CarAppCatalog.query(ctx)
            val carPkgs = car.mapTo(HashSet()) { it.packageName }
            val messaging = AndroidAutoApps.messagingApps(ctx)
            val messagingOnly = AndroidAutoApps.messagingOnly(messaging)
            val media = AndroidAutoApps.mediaApps(ctx).filter { it.packageName !in carPkgs && it.packageName !in messagingOnly }
            val shown = carPkgs + media.map { it.packageName }
            LauncherLists(car, media, messaging.filter { it.packageName !in shown })
        }
    }
    fun toggle(pkg: String, visible: Boolean) {
        hidden = if (visible) hidden - pkg else hidden + pkg
        settings.hiddenApps = hidden
    }
    val l = lists
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.setup_section_app_list)) {
            Text(stringResource(R.string.setup_app_list_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (l == null) {
                Text(stringResource(R.string.setup_app_list_loading), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
                return@Section
            }
            AppGroup(stringResource(R.string.setup_app_group_nav))
            if (l.carApps.isEmpty()) Text(stringResource(R.string.setup_app_list_no_nav), style = MaterialTheme.typography.bodyMedium)
            val navigationKind = stringResource(R.string.setup_app_kind_navigation)
            val genericKind = stringResource(R.string.setup_app_kind_generic)
            l.carApps.forEach { app ->
                val kind = if (app.isNavigation) navigationKind
                    else app.categories.firstOrNull()?.substringAfterLast('.')?.lowercase()?.replaceFirstChar { it.uppercase() } ?: genericKind
                AppRow(app.icon, app.label, kind, app.packageName !in hidden) { toggle(app.packageName, it) }
            }
            if (l.media.isNotEmpty()) {
                AppGroup(stringResource(R.string.setup_app_group_media))
                val mediaKind = stringResource(R.string.setup_app_kind_media)
                l.media.forEach { app -> AppRow(app.icon, app.label, mediaKind, app.packageName !in hidden) { toggle(app.packageName, it) } }
            }
            if (l.messaging.isNotEmpty()) {
                AppGroup(stringResource(R.string.setup_app_group_messaging))
                val messagingKind = stringResource(R.string.setup_app_kind_messaging)
                l.messaging.forEach { app -> AppRow(app.icon, app.label, messagingKind, app.packageName !in hidden) { toggle(app.packageName, it) } }
            }
            AppGroup(stringResource(R.string.setup_app_group_aare))
            // Phone and Notifications are built in (CarRuntime.applyApps), so they have no package
            // to key on; hiddenApps takes their fixed ids instead, like it already does for every
            // other app here. Settings isn't offered: hiding it would lock the user out of the
            // car-side settings from the car itself.
            AppRow(null, stringResource(R.string.setup_app_entry_phone), stringResource(R.string.setup_app_builtin_subtitle), CarRuntime.PHONE_ID !in hidden) { toggle(CarRuntime.PHONE_ID, it) }
            AppRow(null, stringResource(R.string.setup_app_entry_notifications), stringResource(R.string.setup_app_builtin_subtitle), CarRuntime.NOTIFICATIONS_ID !in hidden) { toggle(CarRuntime.NOTIFICATIONS_ID, it) }
            AppRow(null, stringResource(R.string.phone_section_title), stringResource(R.string.phone_app_row_subtitle), phoneScreen) { phoneScreen = it; settings.phoneScreen = it }
            AppRow(null, stringResource(R.string.browser_app_label), stringResource(R.string.browser_app_row_subtitle), browser) { browser = it; settings.browser = it }
            if (browser) {
                OutlinedTextField(
                    value = browserUrl, onValueChange = { browserUrl = it; settings.browserUrl = it },
                    label = { Text(stringResource(R.string.browser_url_label)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(stringResource(R.string.browser_hint_parked), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (l != null) AppOrder(settings, l, hidden, phoneScreen, browser)
        if (l != null) RailAppsSection(settings, l, hidden, phoneScreen, browser)
        StandaloneAppsSection(settings, ctx)
        val unsupported by produceState(emptyList<UnsupportedCarApp>(), l) {
            if (l != null) value = withContext(Dispatchers.IO) { AndroidAutoApps.unsupported(ctx, l.carApps.map { it.packageName }) }
        }
        if (unsupported.isNotEmpty()) {
            Section(stringResource(R.string.setup_section_unsupported_apps)) {
                unsupported.forEach { UnsupportedRow(it) }
            }
        }
        Text(stringResource(R.string.setup_hint_allowlist),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Everything the car launcher can list, keyed as `CarRuntime.orderKey` does (packages, or the
 * built-in apps' ids): shared by the launcher's own order ([AppOrder]) and the rail's ([RailAppsSection]).
 */
@Composable
private fun launcherCandidates(l: LauncherLists, hidden: Set<String>, phoneScreen: Boolean, browser: Boolean): List<Triple<String, String, android.graphics.Bitmap?>> {
    val ctx = LocalContext.current
    val homeApp = remember { HomeStore(ctx).load().hasContent }
    return buildList {
        l.carApps.forEach { add(Triple(it.packageName, it.label, it.icon)) }
        l.media.forEach { add(Triple(it.packageName, it.label, it.icon)) }
        l.messaging.forEach { add(Triple(it.packageName, it.label, it.icon)) }
    }.filter { it.first !in hidden } + listOfNotNull(
        if (CarRuntime.PHONE_ID !in hidden) Triple(CarRuntime.PHONE_ID, stringResource(R.string.setup_app_entry_phone), null) else null,
        // Settings is always in the car's list (never hidden): always in this order too.
        Triple(CarRuntime.SETTINGS_ID, stringResource(R.string.setup_app_entry_settings), null),
        if (CarRuntime.NOTIFICATIONS_ID !in hidden) Triple(CarRuntime.NOTIFICATIONS_ID, stringResource(R.string.setup_app_entry_notifications), null) else null,
        if (phoneScreen) Triple(CarRuntime.PHONE_SCREEN_ID, stringResource(R.string.phone_section_title), null) else null,
        if (browser) Triple(CarRuntime.BROWSER_ID, stringResource(R.string.browser_app_label), null) else null,
        if (homeApp) Triple(CarRuntime.HOME_ID, stringResource(R.string.home_app_label), null) else null,
    )
}

/** The order of the car's app list: the visible apps, with up and down buttons. */
@Composable
private fun AppOrder(settings: Settings, l: LauncherLists, hidden: Set<String>, phoneScreen: Boolean, browser: Boolean) {
    val all = launcherCandidates(l, hidden, phoneScreen, browser)
    var order by remember { mutableStateOf(settings.appOrder) }
    val index = order.withIndex().associate { (i, k) -> k to i }
    val sorted = all.sortedWith(compareBy({ index[it.first] ?: Int.MAX_VALUE }, { it.second.lowercase() }))
    fun move(i: Int, by: Int) {
        val keys = sorted.map { it.first }.toMutableList()
        val j = (i + by).coerceIn(0, keys.lastIndex)
        keys.add(j, keys.removeAt(i))
        // Hidden apps keep their saved slots, so showing one again puts it back where it was:
        // the shown keys fill their old slots in the new order, new ones go at the end.
        val queue = ArrayDeque(keys)
        val shown = keys.toSet()
        val merged = order.mapNotNull { if (it in shown) queue.removeFirstOrNull() else it } + queue
        order = merged; settings.appOrder = merged
    }
    Section(stringResource(R.string.setup_section_app_order)) {
        sorted.forEachIndexed { i, (_, label, icon) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(32.dp).clip(CircleShape))
                else Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.common_up)) }
                IconButton(onClick = { move(i, 1) }, enabled = i < sorted.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.common_down)) }
            }
        }
    }
}

/**
 * The rail's own, shorter app list, picked on the phone: a switch per candidate (the same ones the
 * launcher order lists) to put it on the rail, and up/down to order the chosen ones. Nothing chosen
 * (the default): the rail keeps its built-in set (today's behaviour) — see CarShell.RailContent.
 */
@Composable
private fun RailAppsSection(settings: Settings, l: LauncherLists, hidden: Set<String>, phoneScreen: Boolean, browser: Boolean) {
    val candidates = launcherCandidates(l, hidden, phoneScreen, browser)
    var compact by remember { mutableStateOf(settings.compactRail) }
    var spacing by remember { mutableStateOf(settings.railSpacing) }
    var chosen by remember { mutableStateOf(settings.railApps) }
    fun setChosen(keys: List<String>) { chosen = keys; settings.railApps = keys }
    val chosenIndex = chosen.withIndex().associate { (i, k) -> k to i }
    val sorted = candidates.sortedWith(compareBy({ chosenIndex[it.first] ?: Int.MAX_VALUE }, { it.second.lowercase() }))
    Section(stringResource(R.string.setup_section_car_rail)) {
        Text(stringResource(R.string.setup_car_rail_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Toggle(stringResource(R.string.setup_toggle_compact_rail_title), stringResource(R.string.setup_toggle_compact_rail_subtitle), compact) {
            compact = it; settings.compactRail = it
        }
        Spacer(Modifier.height(8.dp))
        val gap = spacing ?: railGapDp(compact, bottom = settings.railPosition == Settings.RAIL_BOTTOM)
        fun setSpacing(dp: Int?) { spacing = dp; settings.railSpacing = dp }
        Text(stringResource(R.string.setup_rail_spacing), style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { setSpacing(gap - 1) }, enabled = gap > 0) { Text("\u2212") }
            Text(stringResource(R.string.setup_rail_spacing_dp, gap), style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center, modifier = Modifier.width(80.dp))
            OutlinedButton(onClick = { setSpacing(gap + 1) }, enabled = gap < 40) { Text("+") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { setSpacing(null) }, enabled = spacing != null) { Text(stringResource(R.string.setup_rail_spacing_reset)) }
        }
        Spacer(Modifier.height(8.dp))
        sorted.forEach { (key, label, icon) ->
            val i = chosen.indexOf(key)
            val on = i >= 0
            Row(Modifier.fillMaxWidth().clickable { setChosen(chosen.withKey(key, !on)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = on, onCheckedChange = { setChosen(chosen.withKey(key, it)) })
                Spacer(Modifier.width(12.dp))
                if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(32.dp).clip(CircleShape))
                else Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (on) {
                    IconButton(onClick = { setChosen(chosen.movedBy(i, -1)) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.common_up)) }
                    IconButton(onClick = { setChosen(chosen.movedBy(i, 1)) }, enabled = i < chosen.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.common_down)) }
                }
            }
        }
    }
}

@Composable
private fun AppGroup(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

/** Opt-in: launch ordinary phone apps on the car screen (parked only; needs root or Shizuku). */
@Composable
private fun StandaloneAppsSection(settings: Settings, ctx: android.content.Context) {
    var enabled by remember { mutableStateOf(settings.standaloneApps) }
    var keepAwake by remember { mutableStateOf(settings.standaloneKeepScreenAwake) }
    var mediaVideo by remember { mutableStateOf(settings.mediaVideo) }
    var allowed by remember { mutableStateOf(settings.standaloneAppPackages) }
    var rules by remember { mutableStateOf(settings.standaloneDrivingRules) }
    val available = remember { AppLauncher.available(ctx) }
    Section(stringResource(R.string.standalone_section_title)) {
        Text(stringResource(R.string.standalone_section_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Toggle(stringResource(R.string.standalone_toggle_title), stringResource(R.string.standalone_toggle_subtitle), enabled) {
            enabled = it; settings.standaloneApps = it
        }
        if (enabled && !available) {
            Text(stringResource(R.string.standalone_need_root), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        if (enabled) {
            Toggle(
                stringResource(R.string.standalone_keep_awake_title),
                stringResource(R.string.standalone_keep_awake_subtitle),
                keepAwake,
            ) {
                keepAwake = it; settings.standaloneKeepScreenAwake = it
            }
            Toggle(stringResource(R.string.standalone_media_video_title), stringResource(R.string.standalone_media_video_subtitle), mediaVideo) {
                mediaVideo = it; settings.mediaVideo = it
            }
            val apps by produceState<List<LaunchableApp>?>(null) {
                value = withContext(Dispatchers.IO) { launchableApps(ctx) }
            }
            val list = apps
            if (list == null) {
                Text(stringResource(R.string.setup_app_list_loading), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                Spacer(Modifier.height(8.dp))
                list.forEach { (pkg, label, icon, navigation) ->
                    AppRow(icon, label, pkg, pkg in allowed) { on ->
                        allowed = if (on) allowed + pkg else allowed - pkg
                        settings.standaloneAppPackages = allowed
                    }
                    if (pkg in allowed) {
                        DrivingRuleChoice(rules[pkg], navigation) { rule ->
                            rules = rules + (pkg to rule)
                            settings.standaloneDrivingRules = rules
                        }
                    }
                }
            }
        }
    }
}

/**
 * What a launched app may do while driving. "Navigation" is offered to navigation apps only: any
 * other app a passenger wants to use while driving is what passenger mode is for.
 */
@Composable
private fun DrivingRuleChoice(rule: StandaloneDrivingRule?, navigation: Boolean, onSelect: (StandaloneDrivingRule) -> Unit) {
    val current = effectiveDrivingRule(rule, navigation)
    Column(Modifier.padding(start = 52.dp, bottom = 12.dp)) {
        Choices(
            stringResource(R.string.standalone_rule_title),
            StandaloneDrivingRule.offered(navigation).map { r ->
                r.key to stringResource(when (r) {
                    StandaloneDrivingRule.PARKED_ONLY -> R.string.standalone_rule_parked
                    StandaloneDrivingRule.AUDIO_ONLY -> R.string.standalone_rule_audio
                    StandaloneDrivingRule.NAVIGATION -> R.string.standalone_rule_navigation
                })
            },
            current.key,
        ) { key -> StandaloneDrivingRule.byKey(key)?.let(onSelect) }
        if (current == StandaloneDrivingRule.NAVIGATION) {
            Text(stringResource(R.string.standalone_rule_navigation_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** A launchable app for the standalone allow-list; [navigation]: it may be given the navigation rule. */
private data class LaunchableApp(val pkg: String, val label: String, val icon: android.graphics.Bitmap?, val navigation: Boolean)

/** Every app with a launcher icon, except AARE itself, for the standalone allow-list. */
private fun launchableApps(ctx: android.content.Context): List<LaunchableApp> {
    val pm = ctx.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { it.activityInfo?.packageName }
        .filter { it != ctx.packageName }
        .distinct()
        .mapNotNull { pkg ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val icon = runCatching { pm.getApplicationIcon(info).toBitmap(96, 96) }.getOrNull()
            LaunchableApp(pkg, pm.getApplicationLabel(info).toString(), icon, isNavigationApp(pkg, info.category))
        }.sortedBy { it.label.lowercase() }
}

@Composable
private fun AppRow(icon: android.graphics.Bitmap?, label: String, kind: String, visible: Boolean, onVisible: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onVisible(!visible) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(40.dp).clip(CircleShape))
        else Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(label.take(1), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = visible, onCheckedChange = onVisible)
    }
}

@Composable
private fun UnsupportedRow(app: UnsupportedCarApp) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(40.dp).clip(CircleShape)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(app.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- assistant

/** Which assistant the steering-wheel voice button opens: the phone's default, or one picked here. */
@Composable
private fun AssistantCard(settings: Settings) {
    val ctx = LocalContext.current
    val choices = remember { Assistants.query(ctx, CarAppCatalog.query(ctx)) }
    val phoneDefault = remember { Assistants.phoneDefault(ctx) }
    var chosen by remember { mutableStateOf(settings.assistantPackage) }
    var onCarScreen by remember { mutableStateOf(settings.assistantOnCarScreen) }
    val launcherAvailable = remember { AppLauncher.available(ctx) }
    fun choose(pkg: String?) { chosen = pkg; settings.assistantPackage = pkg }
    Section(stringResource(R.string.assistant_settings_title)) {
        val defaultLabel = phoneDefault?.let { pkg -> choices.firstOrNull { it.packageName == pkg }?.label ?: Assistants.label(ctx, pkg) ?: pkg }
        AssistantRow(
            title = stringResource(R.string.assistant_same_as_phone),
            subtitle = defaultLabel ?: stringResource(R.string.assistant_no_default),
            app = null, selected = chosen == null, onSelect = { choose(null) },
        )
        val hasCarAppSubtitle = stringResource(R.string.assistant_has_car_app_subtitle)
        val opensOnCarScreenSubtitle = stringResource(R.string.assistant_opens_on_car_screen)
        val opensOnPhoneSubtitle = stringResource(R.string.assistant_opens_on_phone)
        choices.forEach { app ->
            AssistantRow(
                title = app.label,
                subtitle = when {
                    app.hasCarApp -> hasCarAppSubtitle
                    onCarScreen && launcherAvailable -> opensOnCarScreenSubtitle
                    else -> opensOnPhoneSubtitle
                },
                app = app, selected = chosen == app.packageName, onSelect = { choose(app.packageName) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.assistant_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (launcherAvailable) {
            Spacer(Modifier.height(8.dp))
            Toggle(
                stringResource(R.string.assistant_car_screen_toggle_title),
                stringResource(R.string.assistant_car_screen_toggle_subtitle),
                onCarScreen,
            ) { onCarScreen = it; settings.assistantOnCarScreen = it }
        }
        var stopStyle by remember { mutableStateOf(settings.assistantStopStyle) }
        Spacer(Modifier.height(8.dp))
        Select(stringResource(R.string.assistant_stop_style_title), listOf(
            Settings.ASSISTANT_STOP_VOICE_BUTTON to stringResource(R.string.assistant_stop_style_voice_button),
            Settings.ASSISTANT_STOP_RED_RESTART to stringResource(R.string.assistant_stop_style_red_restart),
            Settings.ASSISTANT_STOP_RED_STOP to stringResource(R.string.assistant_stop_style_red_stop),
        ), stopStyle) { stopStyle = it; settings.assistantStopStyle = it }
        var links by remember { mutableStateOf(settings.assistantLinks) }
        Spacer(Modifier.height(8.dp))
        Toggle(stringResource(R.string.assistant_links_title), stringResource(R.string.assistant_links_subtitle), links) {
            links = it; settings.assistantLinks = it
        }
    }
}

@Composable
private fun AssistantRow(title: String, subtitle: String, app: AssistantApp?, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        app?.icon?.let {
            Image(it.asImageBitmap(), null, Modifier.size(40.dp).clip(CircleShape))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- certificates

@Composable
private fun CertificatesCard(certs: CertificateStore, state: ProjectionService.State) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf(certs.entries()) }
    // Saveable: the system picker can outlive the activity, and the key picker's result needs the certificate.
    var pickCert by rememberSaveable { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var p12 by rememberSaveable { mutableStateOf<Uri?>(null) }
    var downloading by remember { mutableStateOf(false) }
    val settings = remember { Settings(ctx) }
    var autoUpdate by remember { mutableStateOf(settings.certAutoUpdate) }
    val scope = rememberCoroutineScope()
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { keyUri ->
        val certUri = pickCert ?: return@rememberLauncherForActivityResult
        if (keyUri == null) return@rememberLauncherForActivityResult
        error = runCatching {
            val cert = ctx.contentResolver.openInputStream(certUri)!!.use { it.readBytes().decodeToString() }
            val key = ctx.contentResolver.openInputStream(keyUri)!!.use { it.readBytes().decodeToString() }
            certs.importPem(displayName(ctx, certUri), cert, key)
            entries = certs.entries()
        }.exceptionOrNull()?.message
    }
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Document URIs (msf:1234) carry no extension: ask the provider for the file name and type.
        val name = displayName(ctx, uri)
        val type = ctx.contentResolver.getType(uri)
        if (name.endsWith(".p12", true) || name.endsWith(".pfx", true) || type == "application/x-pkcs12") p12 = uri
        else { pickCert = uri; keyPicker.launch(arrayOf("*/*")) }
    }
    Section(stringResource(R.string.cert_section_title)) {
        Text(stringResource(R.string.cert_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        entries.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.cert_entry_subtitle, e.subject.take(60), DateFormat.getDateInstance().format(e.notAfter)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (e.removable) TextButton(onClick = { certs.remove(e); entries = certs.entries() }) { Text(stringResource(R.string.cert_action_remove)) }
            }
        }
        Row {
            TextButton(onClick = { certPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.cert_action_import)) }
            TextButton(enabled = !downloading, onClick = {
                downloading = true
                error = null
                scope.launch {
                    error = runCatching {
                        val d = withContext(Dispatchers.IO) { CertificateDownload.fetch() }
                        certs.importDownloaded(d)
                        entries = certs.entries()
                        CertificateUpdates.refresh(ctx)
                    }.exceptionOrNull()?.let { downloadError(ctx, it) }
                    downloading = false
                }
            }) { Text(stringResource(if (downloading) R.string.cert_action_downloading else R.string.cert_action_download)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Toggle(stringResource(R.string.cert_auto_update_title), stringResource(R.string.cert_auto_update_subtitle), autoUpdate) {
            autoUpdate = it; settings.certAutoUpdate = it; CertificateUpdates.refresh(ctx)
        }
    }
    p12?.let { uri ->
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { p12 = null },
            title = { Text(stringResource(R.string.cert_p12_dialog_title)) },
            text = { OutlinedTextField(password, { password = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    error = runCatching { certs.importPkcs12(displayName(ctx, uri), uri, password.toCharArray()); entries = certs.entries() }
                        .exceptionOrNull()?.message
                    p12 = null
                }) { Text(stringResource(R.string.cert_action_import_confirm)) }
            },
            dismissButton = { TextButton(onClick = { p12 = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** What to tell the user when Download certificate fails. */
private fun downloadError(ctx: Context, e: Throwable): String = when (e) {
    is CertificateDownload.Unavailable -> ctx.getString(R.string.cert_download_unavailable, CertificateDownload.SOURCE)
    is CertificateDownload.Invalid -> ctx.getString(R.string.cert_download_invalid, e.message)
    is java.io.IOException -> ctx.getString(R.string.cert_download_network, e.message ?: e.toString())
    else -> e.message ?: e.toString()
}

/** The picked file's name as the provider shows it, for naming an imported certificate. */
private fun displayName(ctx: Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: ctx.getString(R.string.cert_imported_fallback)

/** One label/value line; renders nothing when the car hasn't reported this value yet. */
@Composable
private fun DataRow(label: String, value: String?) {
    if (value == null) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

/**
 * A read-only view of everything the head unit tells AARE about the car: its identity and the live
 * value of every sensor it reports (an "OBD-ish" viewer, but for the AAP sensor stream). All values
 * come from [CarData.state] and [ProjectionService.state], which update themselves.
 */
@Composable
private fun CarDataCard() {
    val car by CarData.state.collectAsState()
    val proj by ProjectionService.state.collectAsState()
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var inCar by remember { mutableStateOf(settings.carDataApp) }
    Section(stringResource(R.string.setup_page_car_data_title)) {
        Toggle(stringResource(R.string.car_data_show_in_car_title), stringResource(R.string.car_data_show_in_car_subtitle), inCar) { inCar = it; settings.carDataApp = it }
        if (!car.connected) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.car_data_not_connected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (!car.connected) return
    Spacer(Modifier.height(16.dp))
    val on = stringResource(R.string.car_data_on); val off = stringResource(R.string.car_data_off)
    val yes = stringResource(R.string.car_data_yes); val no = stringResource(R.string.car_data_no)
    fun onOff(b: Boolean?) = b?.let { if (it) on else off }
    fun yesNo(b: Boolean?) = b?.let { if (it) yes else no }

    Section(stringResource(R.string.car_data_section_car)) {
        val id = car.identity
        DataRow(stringResource(R.string.car_data_identity),
            id?.let { listOfNotNull(it.make.ifBlank { null }, it.model.ifBlank { null }, it.year?.toString()).joinToString(" ").ifBlank { null } })
        DataRow(stringResource(R.string.car_data_head_unit), proj.headUnit.ifBlank { null })
        DataRow(stringResource(R.string.car_data_connection), proj.transport.ifBlank { null })
        DataRow(stringResource(R.string.car_data_video), proj.video.ifBlank { null })
        if (car.advertised.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.car_data_advertised), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(car.advertised.map { it.name.removePrefix("SENSOR_") }.sorted().joinToString(", "),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(16.dp))
    Section(stringResource(R.string.car_data_section_live)) {
        DataRow(stringResource(R.string.car_data_driving), car.drivingStatus?.let { if (it.value == 0) stringResource(R.string.car_data_parked) else stringResource(R.string.car_data_moving) })
        DataRow(stringResource(R.string.car_data_night), car.nightMode?.let { if (it.value) stringResource(R.string.car_data_night_on) else stringResource(R.string.car_data_night_off) })
        DataRow(stringResource(R.string.car_data_speed), car.speed?.let { "%.0f km/h".format(it.value.metersPerSecond * 3.6f) })
        DataRow(stringResource(R.string.car_data_cruise), car.speed?.value?.cruiseSetSpeed?.let { "$it" })
        DataRow(stringResource(R.string.car_data_rpm), car.rpm?.let { "%.0f".format(it.value) })
        DataRow(stringResource(R.string.car_data_gear), car.gearName)
        DataRow(stringResource(R.string.car_data_parking_brake), onOff(car.parkingBrake?.value))
        DataRow(stringResource(R.string.car_data_odometer), car.odometer?.let { o ->
            "%.0f km".format(o.value.km) + (o.value.tripKm?.let { " · trip %.0f km".format(it) } ?: "") })
        DataRow(stringResource(R.string.car_data_fuel), car.fuel?.let { f ->
            listOfNotNull(f.value.levelPercent?.let { "$it%" }, f.value.rangeKm?.let { "$it km" }, if (f.value.low == true) stringResource(R.string.car_data_low) else null)
                .joinToString(" · ").ifBlank { null } })
        DataRow(stringResource(R.string.car_data_compass), car.compass?.let { "%.0f°".format(it.value.bearingDeg) })
        DataRow(stringResource(R.string.car_data_satellites), car.satellites?.let { s -> "${s.value.inUse}" + (s.value.inView?.let { " / $it" } ?: "") })
        DataRow(stringResource(R.string.car_data_environment), car.environment?.let { e ->
            listOfNotNull(e.value.temperatureC?.let { "%.1f °C".format(it) }, e.value.pressureKpa?.let { "%.0f kPa".format(it) }, e.value.rain?.let { if (it > 0) stringResource(R.string.car_data_rain) else null })
                .joinToString(" · ").ifBlank { null } })
        DataRow(stringResource(R.string.car_data_climate), car.hvac?.let { h ->
            listOfNotNull(h.value.targetC?.let { "→ %.0f °C".format(it) }, h.value.currentC?.let { "%.0f °C".format(it) }).joinToString(" · ").ifBlank { null } })
        DataRow(stringResource(R.string.car_data_steering), car.deadReckoning?.value?.steeringAngleDeg?.let { "%.0f°".format(it) })
        DataRow(stringResource(R.string.car_data_tyres), car.tirePressuresKpa?.let { t -> t.value.joinToString(" · ") { "%.0f".format(it) } + " kPa" })
        DataRow(stringResource(R.string.car_data_doors), car.doors?.let { d ->
            val open = buildList {
                if (d.value.hoodOpen == true) add(stringResource(R.string.car_data_hood))
                if (d.value.trunkOpen == true) add(stringResource(R.string.car_data_trunk))
                repeat(d.value.doorsOpen.count { it }) { add(stringResource(R.string.car_data_door)) }
            }
            if (open.isEmpty()) stringResource(R.string.car_data_closed) else open.joinToString(", ") })
        DataRow(stringResource(R.string.car_data_lights), car.lights?.let { l ->
            listOfNotNull(l.value.headLights?.name?.lowercase(), l.value.turn?.takeIf { it != CarData.Turn.NONE }?.name?.lowercase(),
                if (l.value.hazard == true) stringResource(R.string.car_data_hazard) else null).joinToString(" · ").ifBlank { null } })
        DataRow(stringResource(R.string.car_data_passenger), yesNo(car.passengerPresent?.value))
        DataRow(stringResource(R.string.car_data_toll), yesNo(car.tollCardPresent?.value))
        DataRow(stringResource(R.string.car_data_accelerometer), car.accelerometer?.let { a -> xyz(a.value) }?.let { "$it m/s²" })
        DataRow(stringResource(R.string.car_data_gyroscope), car.gyroscope?.let { g -> xyz(g.value) }?.let { "$it rad/s" })
        DataRow(stringResource(R.string.car_data_dtc), dtcText(car.diagnostics?.value))
    }
}

private fun xyz(v: CarData.Vector3): String? =
    listOf(v.x, v.y, v.z).let { if (it.all { c -> c == null }) null else it.joinToString(", ") { c -> c?.let { "%.2f".format(it) } ?: "–" } }

@Composable
private fun VehicleOverridesCard() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    var carFixes by remember { mutableStateOf(settings.builtInCarFixes) }
    val file = remember { net.prototux.aare.car.VehicleOverrides.file(ctx) }
    var count by remember { mutableIntStateOf(net.prototux.aare.car.VehicleOverrides.ruleCount(ctx)) }
    var problem by remember { mutableStateOf(net.prototux.aare.car.VehicleOverrides.problem(ctx)) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
        }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_SHORT).show() }
        count = net.prototux.aare.car.VehicleOverrides.ruleCount(ctx)
        problem = net.prototux.aare.car.VehicleOverrides.problem(ctx)
    }
    Section(stringResource(R.string.vehicles_section_title)) {
        Text(stringResource(R.string.vehicles_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Toggle(stringResource(R.string.vehicles_builtin_fixes_title), stringResource(R.string.vehicles_builtin_fixes_subtitle), carFixes) {
            carFixes = it; settings.builtInCarFixes = it
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.vehicles_rule_count, count), style = MaterialTheme.typography.bodyMedium)
        problem?.let { p ->
            Text(
                if (p.line != null && p.column != null) stringResource(R.string.vehicles_malformed_at, p.line, p.column, p.reason)
                else stringResource(R.string.vehicles_malformed, p.reason),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        Text(file.absolutePath, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Row {
            TextButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(stringResource(R.string.vehicles_import)) }
            TextButton(onClick = {
                runCatching { file.parentFile?.mkdirs(); file.writeText(net.prototux.aare.car.VehicleOverrides.template()) }
                    .onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_SHORT).show() }
                count = net.prototux.aare.car.VehicleOverrides.ruleCount(ctx)
                problem = net.prototux.aare.car.VehicleOverrides.problem(ctx)
                Toast.makeText(ctx, file.absolutePath, Toast.LENGTH_LONG).show()
            }) { Text(stringResource(R.string.vehicles_template)) }
        }
    }
}

/** One vehicles.json quirk, with its choice for one car (see VehicleOverrides.effective). */
@Composable
private fun QuirkRow(title: String, subtitle: String, value: QuirkChoice, onLabel: String, offLabel: String, onSelect: (QuirkChoice) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        val options = listOf(QuirkChoice.AUTOMATIC to stringResource(R.string.quirks_automatic), QuirkChoice.ON to onLabel, QuirkChoice.OFF to offLabel)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (v, label) ->
                SegmentedButton(selected = v == value, onClick = { onSelect(v) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                    Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * "Car quirks": every vehicles.json quirk that has a clear on/off meaning, overridable for one car,
 * the head unit connected last (kept with it in KnownHeadUnits, so Forget clears it). Automatic
 * keeps what the built-in car fixes and the user's vehicles.json say for it; On/Off force it.
 */
@Composable
private fun CarQuirksCard(settings: Settings) {
    val ctx = LocalContext.current
    val known = remember { KnownHeadUnits(ctx) }
    val car = remember { settings.lastHeadUnit?.takeIf { known.carName(it) != null } }
    Section(stringResource(R.string.quirks_section_title)) {
        Text(stringResource(R.string.quirks_subtitle), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.quirks_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (car == null) {
            Text(stringResource(R.string.quirks_no_car), style = MaterialTheme.typography.bodyMedium)
        } else {
            CarQuirkRows(car, known)
        }
    }
}

@Composable
private fun CarQuirkRows(car: String, known: KnownHeadUnits) {
    var q by remember(car) { mutableStateOf(known.quirks(car)) }
    fun set(next: QuirkSettings) { q = next; known.setQuirks(car, next) }
    val on = stringResource(R.string.quirks_on)
    val off = stringResource(R.string.quirks_off)
    Text(stringResource(R.string.quirks_for_car, known.carName(car).orEmpty().ifBlank { car }), style = MaterialTheme.typography.titleSmall)
    QuirkRow(stringResource(R.string.quirks_pixel_aspect_title), stringResource(R.string.quirks_pixel_aspect_subtitle), q.pixelAspect, on, off) {
        set(q.copy(pixelAspect = it))
    }
    QuirkRow(stringResource(R.string.quirks_video_resolutions_title), stringResource(R.string.quirks_video_resolutions_subtitle), q.videoResolutions, on, off) {
        set(q.copy(videoResolutions = it))
    }
    QuirkRow(stringResource(R.string.quirks_single_channel_capture_title), stringResource(R.string.quirks_single_channel_capture_subtitle), q.singleChannelCapture, on, off) {
        set(q.copy(singleChannelCapture = it))
    }
    QuirkRow(stringResource(R.string.quirks_wifi_media_silence_title), stringResource(R.string.quirks_wifi_media_silence_subtitle), q.wifiMediaStartSilence, on, off) {
        set(q.copy(wifiMediaStartSilence = it))
    }
    QuirkRow(stringResource(R.string.quirks_wifi_guidance_silence_title), stringResource(R.string.quirks_wifi_guidance_silence_subtitle), q.wifiGuidanceStartSilence, on, off) {
        set(q.copy(wifiGuidanceStartSilence = it))
    }
    QuirkRow(stringResource(R.string.quirks_media_on_gain_transient_title), stringResource(R.string.quirks_media_on_gain_transient_subtitle), q.mediaOnGainTransient, on, off) {
        set(q.copy(mediaOnGainTransient = it))
    }
    QuirkRow(stringResource(R.string.quirks_pause_on_guidance_only_title), stringResource(R.string.quirks_pause_on_guidance_only_subtitle), q.pauseOnGuidanceOnly, on, off) {
        set(q.copy(pauseOnGuidanceOnly = it))
    }
    QuirkRow(stringResource(R.string.quirks_wireless_title), stringResource(R.string.quirks_wireless_subtitle), q.wireless,
        stringResource(R.string.quirks_wireless_allow), stringResource(R.string.quirks_wireless_block)) {
        set(q.copy(wireless = it))
    }
    QuirkRow(stringResource(R.string.quirks_force_electric_title), stringResource(R.string.quirks_force_electric_subtitle), q.forceElectric, on, off) {
        set(q.copy(forceElectric = it))
    }
    QuirkRow(stringResource(R.string.quirks_exit_to_car_title), stringResource(R.string.quirks_exit_to_car_subtitle), q.exitToCar, on, off) {
        set(q.copy(exitToCar = it))
    }
    QuirkRow(stringResource(R.string.quirks_mic_setup_title), stringResource(R.string.quirks_mic_setup_subtitle), q.micSetup, on, off) {
        set(q.copy(micSetup = it))
    }
}

@Composable
private fun LogCard() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val log by DiagLog.lines.collectAsState()
    val title = stringResource(R.string.setup_section_connection_log)
    // Writes every retained line to a file the user picks, to attach to a bug report.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(DiagLog.lines.value.joinToString("\n").toByteArray()) }
        }.onFailure { Toast.makeText(ctx, it.message, Toast.LENGTH_SHORT).show() }
    }
    Section(title) {
        // Always shown, so the trace can be turned on before connecting.
        var verbose by remember { mutableStateOf(settings.verboseProtocolLog) }
        Toggle(stringResource(R.string.setup_toggle_verbose_log_title), stringResource(R.string.setup_toggle_verbose_log_subtitle), verbose) {
            verbose = it; settings.verboseProtocolLog = it; SessionAapLog.verbose = it
        }
        if (log.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            log.takeLast(40).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                // Copies every retained line, not only the ones shown above.
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(title, log.joinToString("\n")))
                }) { Text(stringResource(android.R.string.copy)) }
                TextButton(onClick = { save.launch("aare-log.txt") }) { Text(stringResource(R.string.setup_log_save)) }
                TextButton(onClick = { DiagLog.clear() }) { Text(stringResource(R.string.common_clear)) }
            }
        }
    }
}

/**
 * Exports the settings SettingsBackup allows to a JSON file the user picks (SAF), and imports one
 * back, with a confirmation before overwriting. What's left out and why is documented on
 * SettingsBackup itself. The file is read and parsed off the main thread, with a size limit;
 * [onImported] then has every card re-read the new values.
 */
@Composable
private fun SettingsBackupCard(settings: Settings, onImported: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = SettingsBackup.export(settings.all).toString(2)
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) } }
                    .onFailure { DiagLog.add("settings export failed: ${it.javaClass.simpleName}") }.isSuccess
            }
            if (!ok) Toast.makeText(ctx, R.string.setup_backup_export_failed, Toast.LENGTH_LONG).show()
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }
    Section(stringResource(R.string.setup_section_backup)) {
        Text(stringResource(R.string.setup_backup_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = { export.launch("aare-settings.json") }) { Text(stringResource(R.string.setup_backup_export)) }
            TextButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(stringResource(R.string.setup_backup_import)) }
        }
    }
    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.setup_backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.setup_backup_import_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        val imported = withContext(Dispatchers.IO) {
                            // On failure, log only the kind: a JSON error's message quotes the whole file.
                            runCatching {
                                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { SettingsBackup.readLimited(it) }
                                SettingsBackup.import(org.json.JSONObject(bytes.toString(Charsets.UTF_8)))
                            }.onFailure { DiagLog.add("settings import failed: ${it.javaClass.simpleName}") }.getOrNull()
                        }
                        if (imported == null) {
                            Toast.makeText(ctx, R.string.setup_backup_import_failed, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        settings.restore(imported.values)
                        if (imported.skipped.isNotEmpty()) DiagLog.add("settings import skipped ${imported.skipped.size}: ${imported.skipped.joinToString(limit = 10)}")
                        if (imported.blocked.isNotEmpty()) DiagLog.add("settings import left blocked keys unchanged: ${imported.blocked.joinToString()}")
                        val done = (if (imported.skipped.isEmpty()) ctx.getString(R.string.setup_backup_import_done)
                        else ctx.getString(R.string.setup_backup_import_done_skipped, imported.skipped.size)) +
                            (if (imported.blocked.isEmpty()) ""
                            else "\n" + ctx.getString(R.string.setup_backup_import_blocked, imported.blocked.joinToString()))
                        Toast.makeText(ctx, done, Toast.LENGTH_LONG).show()
                        onImported()
                    }
                }) { Text(stringResource(R.string.setup_backup_import_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private const val CAR_BUTTON_DEFAULT = "default"

/** Lets the user remap a key the car has sent (CarSession.onKey) to one of AARE's own actions. */
@Composable
private fun CarButtonsCard(settings: Settings) {
    val seen = remember { settings.carKeysSeen }
    var map by remember { mutableStateOf(settings.carKeyMap) }
    val options = listOf(CAR_BUTTON_DEFAULT to stringResource(R.string.setup_car_button_default)) +
        CarKeyAction.entries.map { it.id to stringResource(it.labelRes) }
    Section(stringResource(R.string.setup_section_car_buttons)) {
        Text(stringResource(R.string.setup_car_buttons_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (seen.isEmpty()) {
            Text(stringResource(R.string.setup_car_buttons_empty), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        seen.toList().sortedBy { (_, name) -> name }.forEach { (code, name) ->
            Spacer(Modifier.height(12.dp))
            Choices(name, options, map[code] ?: CAR_BUTTON_DEFAULT) { choice ->
                map = if (choice == CAR_BUTTON_DEFAULT) map - code else map + (code to choice)
                settings.carKeyMap = map
            }
        }
    }
}

@Composable
private fun LicenceLine() {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.setup_about_text, net.prototux.aare.BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The About page: what AARE is, links to the project, and everyone who helped build it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutScreen() {
    val ctx = LocalContext.current
    Section(stringResource(R.string.about_section_what_title)) {
        Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(16.dp))
    Section(stringResource(R.string.about_section_links_title)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.about_link_repository), Icons.Rounded.Code) {
                ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(AboutLinks.REPOSITORY)))
            }
            Pill(stringResource(R.string.about_link_issues), Icons.Rounded.BugReport) {
                ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(AboutLinks.ISSUES)))
            }
            Pill(stringResource(R.string.about_link_discussions), Icons.Rounded.Forum) {
                ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(AboutLinks.DISCUSSIONS)))
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Section(stringResource(R.string.about_section_thanks_title)) {
        AboutThanks.GROUPS.forEachIndexed { i, group ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Text(stringResource(group.titleRes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            ThanksHandles(group.handles)
        }
    }
    Spacer(Modifier.height(16.dp))
    Section(stringResource(R.string.about_section_oss_title)) {
        AboutOss.GROUPS.forEachIndexed { i, group ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Text(stringResource(group.titleRes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            group.components.forEach { OssComponentRow(it) }
        }
    }
    Spacer(Modifier.height(16.dp))
    LicenceLine()
}

/** One open-source component's name and licence; tapping it opens its project or licence page. */
@Composable
private fun OssComponentRow(component: OssComponent) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable { ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(component.url))) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(component.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            component.licence,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The handles of one Thanks group, wrapped onto as many lines as needed; each opens its GitHub profile. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThanksHandles(handles: List<String>) {
    val ctx = LocalContext.current
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        handles.forEach { handle ->
            Text(
                "@$handle",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { ctx.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(AboutLinks.profile(handle)))) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
