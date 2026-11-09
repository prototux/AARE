package net.prototux.aare.ui.car

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.host.CarRuntime
import net.prototux.aare.media.Placeholder

/**
 * A plain web browser on the car screen: a [WebView] rendered inside AARE's own projection, so the
 * car's touches reach it like any other car screen and no extra app is launched. AARE is not a
 * certified Android Auto app, so this is a parked convenience — it is paused while driving, like the
 * phone-screen mirror. The page it opens is set on the phone ([Settings.browserUrl]).
 */
@Composable
internal fun BrowserScreen(runtime: CarRuntime, driving: Boolean, startUrl: String, modifier: Modifier) {
    var web by remember { mutableStateOf<WebView?>(null) }
    // The head unit / hardware Back key walks the page history before leaving the browser.
    DisposableEffect(Unit) {
        val back: () -> Boolean = { web?.takeIf { it.canGoBack() }?.also { it.goBack() } != null }
        runtime.screenBack = back
        onDispose { if (runtime.screenBack === back) runtime.screenBack = null }
    }
    Box(modifier.background(Color.Black)) {
        if (!driving) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Keep navigation inside this WebView rather than handing off to a browser app.
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        loadUrl(startUrl.ifBlank { Settings.BROWSER_DEFAULT_URL })
                        web = this
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Placeholder(stringResource(R.string.browser_paused_driving), loading = false, icon = Icons.Rounded.PauseCircle)
        }
    }
}
