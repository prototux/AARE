package net.prototux.aare.ui.car

import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.host.templates.CarKeyboard
import net.prototux.aare.host.templates.CarTextField
import net.prototux.aare.host.templates.KeyboardMode
import net.prototux.aare.host.templates.KeyboardSink
import net.prototux.aare.media.Placeholder

/**
 * A plain web browser on the car screen: a [WebView] rendered inside AARE's own projection. A
 * toolbar on top drives it (back, forward, reload, home and an address field), and because the
 * phone's IME can't appear on the car's virtual display, the address field uses AARE's own
 * [CarKeyboard]. Typing a URL opens it; anything else is a DuckDuckGo search.
 *
 * AARE is not a certified Android Auto app, so this is a parked convenience: like the phone-screen
 * mirror it is paused while driving.
 */
@Composable
internal fun BrowserScreen(runtime: CarRuntime, driving: Boolean, startUrl: String, modifier: Modifier) {
    val home = startUrl.ifBlank { Settings.BROWSER_DEFAULT_URL }
    var web by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(home) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }

    fun go(query: String) {
        web?.loadUrl(toUrl(query))
        editing = false
    }

    DisposableEffect(Unit) {
        // Back closes the keyboard first, then walks the page history.
        val back: () -> Boolean = {
            when {
                editing -> { editing = false; true }
                web?.canGoBack() == true -> { web?.goBack(); true }
                else -> false
            }
        }
        runtime.screenBack = back
        onDispose { if (runtime.screenBack === back) runtime.screenBack = null }
    }

    Box(modifier.background(Color.Black)) {
        if (driving) {
            Placeholder(stringResource(R.string.browser_paused_driving), loading = false, icon = Icons.Rounded.PauseCircle)
            return@Box
        }
        Column(Modifier.fillMaxSize()) {
            // Toolbar
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 6.dp, vertical = 6.dp).rotaryArea(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), canBack) { web?.goBack() }
                ToolButton(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.browser_forward), canForward) { web?.goForward() }
                ToolButton(Icons.Rounded.Refresh, stringResource(R.string.browser_reload)) { web?.reload() }
                CarTextField(
                    value = if (editing) address else currentUrl,
                    hint = stringResource(R.string.browser_address_hint),
                    editing = editing,
                    onFocus = { address = currentUrl; editing = true },
                    onClear = { address = "" },
                    leadingIcon = Icons.Rounded.Public,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                ToolButton(Icons.Rounded.Home, stringResource(R.string.browser_home)) { web?.loadUrl(home) }
            }
            // The page; on a car with a knob and no touch, a cursor stands in for the finger.
            CursorPane(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    url?.let { currentUrl = it }
                                    canBack = view.canGoBack(); canForward = view.canGoForward()
                                }
                            }
                            loadUrl(home)
                            web = this
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // Car keyboard for the address field (the phone IME can't show on the car display).
            if (editing) {
                val sink = object : KeyboardSink {
                    override fun type(text: String) { address += text }
                    override fun backspace() { address = address.dropLast(1) }
                    override fun submit() { go(address) }
                    override fun hide() { editing = false }
                }
                CarKeyboard(KeyboardMode.Text, sink, submitIcon = Icons.Rounded.Public)
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, desc, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
    }
}

/** A URL to open: an address as-is (adding https:// when it has no scheme), or a DuckDuckGo search. */
private fun toUrl(query: String): String {
    val q = query.trim()
    return when {
        q.startsWith("http://") || q.startsWith("https://") -> q
        q.isNotEmpty() && !q.contains(' ') && q.contains('.') -> "https://$q"
        else -> "https://duckduckgo.com/?q=" + Uri.encode(q)
    }
}
