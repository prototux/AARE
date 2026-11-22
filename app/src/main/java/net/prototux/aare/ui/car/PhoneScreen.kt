package net.prototux.aare.ui.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.TelephonyManager
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.prototux.aare.R
import net.prototux.aare.car.Calls
import net.prototux.aare.car.VoipApp
import net.prototux.aare.car.VoipCalls
import net.prototux.aare.car.VoipTarget
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.Pill
import net.prototux.aare.media.Placeholder
import net.prototux.aare.ui.theme.LocalCarColors
import net.prototux.aare.ui.theme.LocalCarDimens

/** One number to call: a recent call or a contact, or a contact through a calling app ([voip]). */
internal data class Callee(val name: String, val number: String, val detail: String, val type: Int = 0, val voip: VoipTarget? = null)

private enum class PhoneTab(val labelRes: Int) { RECENTS(R.string.phone_tab_recents), CONTACTS(R.string.phone_tab_contacts), KEYPAD(R.string.phone_tab_keypad) }

private val PERMISSIONS = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)

/**
 * Calls from the car, like Android Auto's phone app: recent calls, favourite contacts and a
 * keypad (parked only). Calls are placed by Android's telephony, so their sound goes through the
 * car's Bluetooth hands-free link as usual.
 */
@Composable
internal fun PhoneScreen(runtime: CarRuntime, modifier: Modifier) {
    val context = LocalContext.current
    val driving = runtime.ui.drivingRestricted
    if (PERMISSIONS.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
        Placeholder(stringResource(R.string.phone_permissions_placeholder), loading = false,
            icon = Icons.Rounded.Call, modifier = modifier.background(MaterialTheme.colorScheme.background))
        return
    }
    var tab by remember { mutableStateOf(PhoneTab.RECENTS) }
    var reload by remember { mutableIntStateOf(0) }
    // While a call is on, show it with a hang-up button; poll: no listener survives the screen anyway.
    val inCall by produceState(false) {
        while (true) { value = Calls.state(context) != TelephonyManager.CALL_STATE_IDLE; delay(1_500) }
    }
    LaunchedEffect(inCall) { if (!inCall) reload++ }
    val callingTitle = context.getString(R.string.phone_notice_calling_title)
    val call: (Callee) -> Unit = { c ->
        val voip = c.voip
        if (voip != null) {
            callThroughApp(context, runtime, c.name, voip)
        } else if (c.number.isNotBlank()) { // an empty tel: places no call, only a "Calling" banner with no name
            Calls.place(context, c.number)
            runtime.notice(callingTitle, c.name.ifBlank { c.number })
        }
    }

    Column(modifier.background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhoneTab.entries.forEach { t ->
                if (t != PhoneTab.KEYPAD || !driving) Pill(stringResource(t.labelRes), selected = tab == t, onClick = { tab = t })
            }
        }
        if (inCall && Calls.canEnd) InCallCard { Calls.end(context) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (if (driving && tab == PhoneTab.KEYPAD) PhoneTab.RECENTS else tab) {
                PhoneTab.RECENTS -> {
                    val recents by produceState<List<Callee>?>(null, reload) { value = withContext(Dispatchers.IO) { recents(context) } }
                    CalleeList(recents, stringResource(R.string.phone_no_recent_calls), call)
                }
                PhoneTab.CONTACTS -> {
                    val contacts by produceState<List<Callee>?>(null, reload) { value = withContext(Dispatchers.IO) { contacts(context) } }
                    CalleeList(contacts, stringResource(R.string.phone_no_contacts), call)
                }
                PhoneTab.KEYPAD -> Keypad { number -> call(Callee("", number, "")) }
            }
        }
    }
}

/**
 * A call through a messaging app. Without the microphone permission the other side wouldn't hear
 * the driver: the car screen says so and the phone gets a notification that opens the app's
 * settings. The call is still started.
 */
private fun callThroughApp(context: Context, runtime: CarRuntime, name: String, voip: VoipTarget) {
    val app = voip.app
    val micOk = VoipCalls.hasMicrophone(context, app.packageName)
    if (!micOk) {
        runtime.notice(context.getString(R.string.voip_mic_title, app.label), context.getString(R.string.voip_mic_car_text), app.icon)
        VoipCalls.askForMicrophone(context, voip)
    }
    when {
        !VoipCalls.place(context, voip) ->
            runtime.notice(context.getString(R.string.voip_call_failed_title, app.label), context.getString(R.string.voip_call_failed_text), app.icon)
        micOk -> runtime.notice(context.getString(R.string.phone_notice_calling_title), "$name · ${app.label}", app.icon)
    }
}

@Composable
private fun InCallCard(onHangUp: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Call, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(14.dp))
        Text(stringResource(R.string.phone_call_in_progress), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
        Box(Modifier.size(LocalCarDimens.current.touchTarget).clip(CircleShape).background(LocalCarColors.current.decline).clickable(onClick = onHangUp),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CallEnd, stringResource(R.string.phone_action_hang_up), tint = LocalCarColors.current.onCall)
        }
    }
}

@Composable
private fun CalleeList(list: List<Callee>?, empty: String, onCall: (Callee) -> Unit) {
    when {
        list == null -> Placeholder(stringResource(R.string.common_loading), loading = true)
        list.isEmpty() -> Placeholder(empty, loading = false, icon = Icons.Rounded.Person)
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list) { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = LocalCarDimens.current.touchTarget).clip(MaterialTheme.shapes.medium)
                        .clickable { onCall(c) }.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = when (c.type) {
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> Icons.AutoMirrored.Rounded.CallMissed
                        CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Rounded.CallMade
                        CallLog.Calls.INCOMING_TYPE -> Icons.AutoMirrored.Rounded.CallReceived
                        else -> Icons.Rounded.Person
                    }
                    val missed = c.type == CallLog.Calls.MISSED_TYPE
                    val appIcon = remember(c.voip) { c.voip?.app?.icon?.asImageBitmap() }
                    Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        if (appIcon != null) Image(appIcon, c.voip?.app?.label, Modifier.size(36.dp))
                        else Icon(icon, null, tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name.ifBlank { c.number }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Icon(Icons.Rounded.Call, stringResource(R.string.common_call), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun Keypad(onCall: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(number.ifEmpty { " " }, style = MaterialTheme.typography.displaySmall, maxLines = 1)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(LocalCarDimens.current.touchTarget).clip(CircleShape).clickable { number = number.dropLast(1) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.phone_keypad_delete))
                }
                Box(Modifier.size(LocalCarDimens.current.touchTarget + 8.dp).clip(CircleShape).background(LocalCarColors.current.accept)
                    .clickable(enabled = number.isNotEmpty()) { onCall(number) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Call, stringResource(R.string.common_call), tint = LocalCarColors.current.onCall)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        Box(Modifier.weight(1f).height(64.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { if (number.length < 20) number += k }, contentAlignment = Alignment.Center) {
                            Text(k, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- call history and contacts

private fun recents(context: Context): List<Callee> = runCatching {
    val out = ArrayList<Callee>()
    val seen = HashSet<String>()
    context.contentResolver.query(CallLog.Calls.CONTENT_URI,
        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
        null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
        while (c.moveToNext() && out.size < 30) {
            // Withheld and unknown callers have an empty number, not null: nothing to call back.
            val number = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(number)) continue
            val ago = DateUtils.getRelativeTimeSpanString(c.getLong(3), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            out += Callee(c.getString(1).orEmpty(), number, ago, c.getInt(2))
        }
    }
    out
}.getOrDefault(emptyList())

/**
 * Favourite (starred) contacts, or all of them if there are none: their phone numbers, and their
 * calls through the calling apps that can take them ([VoipCalls.available]).
 */
private fun contacts(context: Context): List<Callee> = runCatching {
    val apps = VoipCalls.available(context)
    val mimeTypes = listOf(Phone.CONTENT_ITEM_TYPE) + VoipCalls.CALL_MIME_TYPES.filterValues { it in apps }.keys
    val inMimeTypes = "${ContactsContract.Data.MIMETYPE} IN (${mimeTypes.joinToString { "?" }})"
    fun query(starred: Boolean): List<Callee> {
        val rows = ArrayList<ContactRow>()
        context.contentResolver.query(ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.MIMETYPE, ContactsContract.Data._ID,
                ContactsContract.Data.RAW_CONTACT_ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
            if (starred) "$inMimeTypes AND ${ContactsContract.Data.STARRED} = 1" else inMimeTypes, mimeTypes.toTypedArray(),
            "${ContactsContract.Data.DISPLAY_NAME} COLLATE NOCASE ASC")?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(1) ?: continue
                val type = if (mime == Phone.CONTENT_ITEM_TYPE) Phone.getTypeLabel(context.resources, c.getInt(5), c.getString(6)).toString() else ""
                rows += ContactRow(c.getString(0).orEmpty(), mime, c.getLong(2), c.getLong(3), c.getString(4), type)
            }
        }
        return contactCallees(rows, apps)
    }
    query(starred = true).ifEmpty { query(starred = false) }
}.getOrDefault(emptyList())

/** A contact data row: a phone number ([typeLabel] its type), or a calling app's row for the contact. */
internal data class ContactRow(val name: String, val mimeType: String, val dataId: Long, val rawContactId: Long, val data1: String?, val typeLabel: String = "")

/**
 * The car's contact list from the contacts' [rows] (sorted by name): their phone numbers, and
 * their calls through [apps] (by package; other apps' rows are left out). A calling app keeps its own
 * copy of the contact, with the phone number it calls: that number is shown with the app's name.
 * Duplicates (same name and digits, and app) go, and the list stops at [limit].
 */
internal fun contactCallees(rows: List<ContactRow>, apps: Map<String, VoipApp>, limit: Int = 200): List<Callee> {
    val phoneOfRawContact = rows.filter { it.mimeType == Phone.CONTENT_ITEM_TYPE && !it.data1.isNullOrBlank() }
        .associate { it.rawContactId to it.data1!! }
    val out = ArrayList<Callee>()
    val seen = HashSet<String>()
    for (r in rows) {
        if (out.size >= limit) break
        if (r.mimeType == Phone.CONTENT_ITEM_TYPE) {
            val number = r.data1?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(r.name + number.filter(Char::isDigit))) continue
            out += Callee(r.name, number, "${r.typeLabel} · $number")
        } else {
            val app = VoipCalls.CALL_MIME_TYPES[r.mimeType]?.let(apps::get) ?: continue
            // the app's own row holds an id of its own ("123@s.example.net"), not something to show
            val number = phoneOfRawContact[r.rawContactId] ?: r.data1?.substringBefore('@')?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(app.packageName + r.name + number.filter(Char::isDigit))) continue
            out += Callee(r.name, number, "${app.label} · $number", voip = VoipTarget(r.dataId, r.mimeType, app))
        }
    }
    return out
}
