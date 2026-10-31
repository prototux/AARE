package net.prototux.aare.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.prototux.aare.DiagLog
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.audio.CaptureMethod
import net.prototux.aare.audio.CaptureMethods
import net.prototux.aare.car.DrivingMode
import net.prototux.aare.car.PhoneLocationFeed
import net.prototux.aare.cert.CertificateStore
import net.prototux.aare.host.AndroidAutoApps
import net.prototux.aare.host.AssistantApp
import net.prototux.aare.host.Assistants
import net.prototux.aare.host.CarAppCatalog
import net.prototux.aare.host.CarAppInfo
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.host.PhoneApp
import net.prototux.aare.host.UnsupportedCarApp
import net.prototux.aare.mirror.MirrorTouchService
import net.prototux.aare.service.ProjectionService
import net.prototux.aare.ui.car.WALLPAPER_NAMES
import net.prototux.aare.ui.theme.PhoneTheme
import rikka.shizuku.Shizuku
import java.text.DateFormat

/** The phone side: what's connected, what's set up, and the settings. */
class MainActivity : ComponentActivity() {
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
    ADVANCED(R.string.setup_page_advanced_title, R.string.setup_page_advanced_subtitle, Icons.Rounded.Tune),
}

@Composable
private fun Home() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val certs = remember { CertificateStore(ctx) }
    val state by ProjectionService.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    LaunchedEffect(Unit) { ProjectionService.startIdleIfEnabled(ctx) }
    // Re-check permissions and capture methods whenever the user comes back to the app.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = page != null) { page = null }

    AnimatedContent(page, label = "page", transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }) { p ->
        if (p == null) {
            LazyColumn(
                Modifier.fillMaxSize().systemBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { Header() }
                item { StatusCard(state) }
                item { Setup(state, refresh, { refresh++ }) { page = it } }
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                        Page.entries.forEach { CategoryRow(it) { page = it } }
                    }
                }
                item { About() }
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
                        Page.CONNECTION -> item { ConnectionSettings(settings) }
                        Page.ADVANCED -> {
                            item { HeadUnitServerSettings(settings) }
                            item { CertificatesCard(certs, state) }
                            item { LogCard() }
                        }
                    }
                }
            }
        }
    }
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
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        content()
    }
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
private fun Pill(label: String, icon: ImageVector?, onClick: () -> Unit) {
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
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { recheck() }
    // Only the permissions this Android version has: a newer one reads as denied on older releases and
    // can't be requested, so setup would never finish. Before 12, Bluetooth and Wi-Fi scans need location.
    val runtimePerms = buildList {
        add(Manifest.permission.RECORD_AUDIO); add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { add(Manifest.permission.POST_NOTIFICATIONS); add(Manifest.permission.NEARBY_WIFI_DEVICES) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        addAll(listOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALENDAR))
    }
    @Suppress("UNUSED_EXPRESSION") refresh
    val perms = runtimePerms.all(::granted)
    val notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    val audio = CaptureMethods.audioPolicyAvailable(ctx) || CaptureMethods.shizukuAvailable() || state.audioCapture
    if (perms && notificationAccess && audio) return

    Section(stringResource(R.string.setup_finish_title)) {
        Check(stringResource(R.string.setup_check_permissions_title), stringResource(R.string.setup_check_permissions_subtitle), perms) {
            permissions.launch(runtimePerms.toTypedArray())
        }
        Check(stringResource(R.string.setup_check_notification_access_title), stringResource(R.string.setup_check_notification_access_subtitle), notificationAccess) {
            ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        Check(stringResource(R.string.audio_settings_title), stringResource(R.string.setup_check_audio_subtitle), audio) { open(Page.AUDIO) }
    }
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

// ---------------------------------------------------------------- settings

@Composable
private fun ConnectionSettings(settings: Settings) {
    var wireless by remember { mutableStateOf(settings.wireless) }
    var fps60 by remember { mutableStateOf(settings.prefer60fps) }
    var maxH by remember { mutableIntStateOf(settings.maxVideoHeight) }
    var resume by remember { mutableStateOf(settings.resumeMedia) }
    var cars by remember { mutableStateOf(settings.knownCars) }
    var carGps by remember { mutableStateOf(settings.carGpsForPhone) }
    val ctx = LocalContext.current
    val pinned = remember { net.prototux.aare.PinnedHeadUnits(ctx) }
    var pins by remember { mutableStateOf(pinned.all) }
    // A head unit trusted from the notification while this screen is open shows up right away.
    DisposableEffect(pinned) { onDispose(pinned.listen { pins = pinned.all }) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.setup_section_connecting)) {
            Toggle(stringResource(R.string.setup_toggle_wireless_title), stringResource(R.string.setup_toggle_wireless_subtitle), wireless) { wireless = it; settings.wireless = it }
            Toggle(stringResource(R.string.setup_toggle_resume_music_title), stringResource(R.string.setup_toggle_resume_music_subtitle), resume) { resume = it; settings.resumeMedia = it }
        }
        Section(stringResource(R.string.setup_section_location)) {
            Toggle(stringResource(R.string.setup_toggle_car_gps_title), stringResource(R.string.setup_toggle_car_gps_subtitle), carGps) {
                carGps = it; settings.carGpsForPhone = it
            }
            if (carGps) Check(stringResource(R.string.setup_check_mock_location_title), stringResource(R.string.setup_check_mock_location_subtitle), PhoneLocationFeed.allowed(ctx)) {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
        }
        Section(stringResource(R.string.setup_section_video)) {
            Toggle(stringResource(R.string.setup_toggle_60fps_title), stringResource(R.string.setup_toggle_60fps_subtitle), fps60) { fps60 = it; settings.prefer60fps = it }
            Choices(stringResource(R.string.setup_choice_max_resolution), listOf(480, 720, 1080, 1440).map { "$it" to "${it}p" }, "$maxH") {
                maxH = it.toInt(); settings.maxVideoHeight = maxH
            }
            Text(stringResource(R.string.setup_hint_lower_resolution), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Section(stringResource(R.string.huserver_label)) {
        Toggle(stringResource(R.string.huserver_label), stringResource(R.string.huserver_toggle_subtitle), huServer) {
            huServer = it; settings.headUnitServer = it
            if (it) ProjectionService.startIdleIfEnabled(ctx) else if (!ProjectionService.state.value.connected) ProjectionService.stop(ctx)
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
    var replyText by remember { mutableStateOf(settings.autoReplyText) }
    @Suppress("UNUSED_EXPRESSION") refresh
    val access = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Section(stringResource(R.string.msg_settings_title)) {
        if (!access) Check(stringResource(R.string.setup_check_notification_access_title), stringResource(R.string.msg_check_notification_access_subtitle), false) {
            ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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
            ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        Toggle(stringResource(R.string.msg_toggle_autoreply_title), stringResource(R.string.msg_toggle_autoreply_subtitle), autoReply) {
            autoReply = it; settings.autoReply = it
        }
        if (autoReply) OutlinedTextField(replyText, { replyText = it; settings.autoReplyText = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text(stringResource(R.string.msg_autoreply_field_label)) }, singleLine = true)
    }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
    var rail by remember { mutableStateOf(settings.railPosition) }
    var phoneScreen by remember { mutableStateOf(settings.phoneScreen) }
    var darkBar by remember { mutableStateOf(settings.darkBar) }
    var passenger by remember { mutableStateOf(settings.ignoreDriving) }
    var weather by remember { mutableStateOf(settings.showWeather) }
    var wallpaper by remember { mutableStateOf(settings.wallpaper) }
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { saveWallpaper(ctx, uri) }
            if (ok) {
                // Through "none", so the car reloads the photo even if one was already set.
                settings.wallpaper = "none"; settings.wallpaper = Settings.WALLPAPER_CUSTOM
                wallpaper = Settings.WALLPAPER_CUSTOM
            }
        }
    }
    var calendar by remember { mutableStateOf(settings.showCalendar) }
    @Suppress("UNUSED_EXPRESSION") refresh
    val touch = AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        .split(':').any { ComponentName.unflattenFromString(it) == ComponentName(ctx, MirrorTouchService::class.java) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.setup_section_look)) {
            Choices(stringResource(R.string.setup_choice_colors), listOf(Settings.THEME_AUTO to stringResource(R.string.setup_choice_colors_auto), Settings.THEME_DARK to stringResource(R.string.setup_choice_dark), Settings.THEME_LIGHT to stringResource(R.string.setup_choice_light)), theme) {
                theme = it; settings.carTheme = it
            }
            Spacer(Modifier.height(12.dp))
            Choices(stringResource(R.string.setup_choice_bar), listOf(Settings.RAIL_AUTO to stringResource(R.string.setup_choice_bar_auto), Settings.RAIL_LEFT to stringResource(R.string.setup_choice_left), Settings.RAIL_RIGHT to stringResource(R.string.setup_choice_right), Settings.RAIL_BOTTOM to stringResource(R.string.setup_choice_bottom)), rail) {
                rail = it; settings.railPosition = it
            }
            Toggle(stringResource(R.string.setup_toggle_dark_bar_title), stringResource(R.string.setup_toggle_dark_bar_subtitle), darkBar) { darkBar = it; settings.darkBar = it }
        }
        Section(stringResource(R.string.setup_section_dashboard)) {
            Choices(stringResource(R.string.setup_choice_wallpaper), listOf("none" to stringResource(R.string.setup_wallpaper_none)) + WALLPAPER_NAMES.map { it to it.replaceFirstChar(Char::uppercase) } +
                listOf(Settings.WALLPAPER_CUSTOM to stringResource(R.string.setup_wallpaper_photo)), wallpaper) { choice ->
                if (choice == Settings.WALLPAPER_CUSTOM) photoPicker.launch("image/*")
                else { wallpaper = choice; settings.wallpaper = choice }
            }
            Spacer(Modifier.height(8.dp))
            Toggle(stringResource(R.string.setup_toggle_weather_title), stringResource(R.string.setup_toggle_weather_subtitle), weather) {
                weather = it; settings.showWeather = it
            }
            Toggle(stringResource(R.string.setup_toggle_calendar_title), stringResource(R.string.setup_toggle_calendar_subtitle), calendar) {
                calendar = it; settings.showCalendar = it
            }
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
                    ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                Text(stringResource(R.string.phone_hint_touch_capture),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Copies the chosen photo into AARE's files, at most 1920 pixels wide, as the car's wallpaper. */
private fun saveWallpaper(context: android.content.Context, uri: Uri): Boolean = runCatching {
    val src = context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
    val scale = minOf(1f, 1920f / maxOf(src.width, src.height))
    val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
    java.io.File(context.filesDir, Settings.WALLPAPER_FILE).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
}.isSuccess

/** Car audio: the capture methods, best first, and what each needs. */
@Composable
private fun AudioSettings(settings: Settings, state: ProjectionService.State, refresh: Int, recheck: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(settings.carAudio) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK && r.data != null) ProjectionService.giveAudioConsent(ctx, r.resultCode, r.data!!)
        recheck()
    }
    DisposableEffect(Unit) {
        val l = Shizuku.OnRequestPermissionResultListener { _, _ -> recheck() }
        runCatching { Shizuku.addRequestPermissionResultListener(l) }
        onDispose { runCatching { Shizuku.removeRequestPermissionResultListener(l) } }
    }
    @Suppress("UNUSED_EXPRESSION") refresh
    val policy = CaptureMethods.audioPolicyAvailable(ctx)
    val shizuku = CaptureMethods.shizukuAvailable()
    val shizukuAsk = CaptureMethods.shizukuNeedsPermission()
    val best = when {
        policy -> CaptureMethod.AUDIO_POLICY
        shizuku -> CaptureMethod.SHIZUKU
        state.audioCapture -> CaptureMethod.PLAYBACK_CAPTURE
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(stringResource(R.string.audio_settings_title)) {
            Toggle(stringResource(R.string.audio_toggle_enable_title), stringResource(R.string.audio_toggle_enable_subtitle), enabled) {
                enabled = it; settings.carAudio = it
            }
            Spacer(Modifier.height(4.dp))
            Text(best?.let { stringResource(R.string.audio_using_method, it.label) } ?: stringResource(R.string.audio_no_method),
                style = MaterialTheme.typography.bodyMedium, color = if (best == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Section(stringResource(R.string.audio_section_methods)) {
            Check(CaptureMethod.AUDIO_POLICY.label,
                if (policy) stringResource(R.string.audio_policy_ready)
                else stringResource(R.string.audio_policy_not_ready),
                policy) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/prototux/AARE#car-audio")))
            }
            Check(CaptureMethod.SHIZUKU.label,
                when {
                    shizuku -> stringResource(R.string.audio_shizuku_ready)
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> stringResource(R.string.audio_shizuku_needs_android12)
                    shizukuAsk -> stringResource(R.string.audio_shizuku_tap_allow)
                    else -> stringResource(R.string.audio_shizuku_install_hint)
                },
                shizuku) {
                if (shizukuAsk) runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST) }
                else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            }
            Check(CaptureMethod.PLAYBACK_CAPTURE.label,
                if (state.audioCapture) stringResource(R.string.audio_playback_ready)
                else stringResource(R.string.audio_playback_not_ready),
                state.audioCapture) {
                val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
                consent.launch(if (Build.VERSION.SDK_INT >= 34) mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    else mpm.createScreenCaptureIntent())
            }
        }
    }
}

private const val SHIZUKU_REQUEST = 42

/** A labelled row of mutually exclusive options. */
@Composable
private fun Choices(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(6.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(selected = value == selected, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
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
    // Same rules as the car's launcher (CarRuntime.applyApps): an app with a car app shows once, as that.
    val lists by produceState<LauncherLists?>(null) {
        value = withContext(Dispatchers.IO) {
            val car = CarAppCatalog.query(ctx)
            val carPkgs = car.mapTo(HashSet()) { it.packageName }
            val media = AndroidAutoApps.mediaApps(ctx).filter { it.packageName !in carPkgs }
            val shown = carPkgs + media.map { it.packageName }
            LauncherLists(car, media, AndroidAutoApps.messagingApps(ctx).filter { it.packageName !in shown })
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
            AppRow(null, stringResource(R.string.phone_section_title), stringResource(R.string.phone_app_row_subtitle), phoneScreen) { phoneScreen = it; settings.phoneScreen = it }
        }
        if (l != null) AppOrder(settings, l, hidden, phoneScreen)
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

/** The order of the car's app list: the visible apps, with up and down buttons. */
@Composable
private fun AppOrder(settings: Settings, l: LauncherLists, hidden: Set<String>, phoneScreen: Boolean) {
    // Keys as CarRuntime.orderKey: packages, or the built-in apps' ids.
    val all = buildList {
        l.carApps.forEach { add(Triple(it.packageName, it.label, it.icon)) }
        l.media.forEach { add(Triple(it.packageName, it.label, it.icon)) }
        l.messaging.forEach { add(Triple(it.packageName, it.label, it.icon)) }
    }.filter { it.first !in hidden } + listOfNotNull(
        Triple(CarRuntime.PHONE_ID, stringResource(R.string.setup_app_entry_phone), null),
        Triple(CarRuntime.SETTINGS_ID, stringResource(R.string.setup_app_entry_settings), null),
        Triple(CarRuntime.NOTIFICATIONS_ID, stringResource(R.string.setup_app_entry_notifications), null),
        if (phoneScreen) Triple(CarRuntime.PHONE_SCREEN_ID, stringResource(R.string.phone_section_title), null) else null,
    )
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

@Composable
private fun AppGroup(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
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
    fun choose(pkg: String?) { chosen = pkg; settings.assistantPackage = pkg }
    Section(stringResource(R.string.assistant_settings_title)) {
        val defaultLabel = phoneDefault?.let { pkg -> choices.firstOrNull { it.packageName == pkg }?.label ?: Assistants.label(ctx, pkg) ?: pkg }
        AssistantRow(
            title = stringResource(R.string.assistant_same_as_phone),
            subtitle = defaultLabel ?: stringResource(R.string.assistant_no_default),
            app = null, selected = chosen == null, onSelect = { choose(null) },
        )
        val hasCarAppSubtitle = stringResource(R.string.assistant_has_car_app_subtitle)
        val opensOnPhoneSubtitle = stringResource(R.string.assistant_opens_on_phone)
        choices.forEach { app ->
            AssistantRow(
                title = app.label,
                subtitle = if (app.hasCarApp) hasCarAppSubtitle else opensOnPhoneSubtitle,
                app = app, selected = chosen == app.packageName, onSelect = { choose(app.packageName) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.assistant_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        TextButton(onClick = { certPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.cert_action_import)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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

/** The picked file's name as the provider shows it, for naming an imported certificate. */
private fun displayName(ctx: Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: ctx.getString(R.string.cert_imported_fallback)

@Composable
private fun LogCard() {
    val log by DiagLog.lines.collectAsState()
    if (log.isEmpty()) return
    Section(stringResource(R.string.setup_section_connection_log)) {
        log.takeLast(40).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { DiagLog.clear() }) { Text(stringResource(R.string.common_clear)) }
    }
}

@Composable
private fun About() {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.setup_about_text, net.prototux.aare.BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
