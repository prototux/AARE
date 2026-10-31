@file:Suppress("DEPRECATION") // PlaceListNavigation, RoutePreview and Map templates are deprecated but still used

package net.prototux.aare.host.templates

import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.model.signin.SignInTemplate
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import androidx.annotation.OptIn as AndroidXOptIn

/**
 * Template renderer entry point, as the [TemplateRenderer] the host plugs in:
 * `renderTemplate(template, host, modifier)`.
 */
val renderTemplate: TemplateRenderer = { template, host, modifier -> RenderTemplate(template, host, modifier) }

/**
 * Draws any Car App Library [Template] with Compose, calling the template's delegates and the
 * [host] for everything else. Use it inside CarTheme: colours and type come from MaterialTheme.
 *
 * Unknown templates degrade to a card naming the template, with a Back button.
 *
 * State such as scroll position, typed search text or optimistic toggle/selection lives in the
 * composition, so it survives template refreshes. The host should call this inside
 * `key(templateWrapper.id) { ... }` so that a new screen (a new TemplateWrapper id) starts fresh.
 */
@Composable
fun RenderTemplate(template: Template, host: TemplateHost, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalTemplateHost provides host) {
        Box(modifier) { TemplateContent(template) }
    }
}

/** Dispatches on the template class; also used for TabContents and map content templates. */
@AndroidXOptIn(markerClass = [ExperimentalCarApi::class])
@Composable
internal fun TemplateContent(template: Template) {
    when (template) {
        is ListTemplate -> ListTemplateView(template)
        is GridTemplate -> GridTemplateView(template)
        is PaneTemplate -> PaneTemplateView(template)
        is MessageTemplate -> MessageTemplateView(template)
        is LongMessageTemplate -> LongMessageTemplateView(template)
        is SearchTemplate -> SearchTemplateView(template)
        is SignInTemplate -> SignInTemplateView(template)
        is TabTemplate -> TabTemplateView(template)
        is SectionedItemTemplate -> SectionedItemTemplateView(template)
        is NavigationTemplate -> NavigationTemplateView(template)
        is MapWithContentTemplate -> MapWithContentTemplateView(template)
        is PlaceListMapTemplate -> PlaceListMapTemplateView(template)
        is PlaceListNavigationTemplate -> PlaceListNavigationTemplateView(template)
        is RoutePreviewNavigationTemplate -> RoutePreviewNavigationTemplateView(template)
        is MapTemplate -> MapTemplateView(template)
        is MediaPlaybackTemplate -> MediaPlaybackTemplateView(template)
        else -> UnsupportedTemplate(template)
    }
}

/**
 * MediaPlaybackTemplate (experimental): the library only carries a header; the now-playing UI
 * itself comes from the app's media session, which the host shows elsewhere.
 */
@AndroidXOptIn(markerClass = [ExperimentalCarApi::class])
@Composable
private fun MediaPlaybackTemplateView(t: MediaPlaybackTemplate) {
    val host = LocalTemplateHost.current
    TemplateScaffold(headerSpec(t.header)) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIconView(96.dp)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.media_playing_from, host.appLabel), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** A calm card for templates we do not know (newer library versions, custom classes). */
@Composable
internal fun UnsupportedTemplate(template: Template) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        CarCard(Modifier.widthIn(max = 560.dp)) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Construction, null, tint = scheme.tertiary, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.common_unsupported_screen),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    template.javaClass.simpleName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                ActionView(Action.BACK, ActionLook.Button)
            }
        }
    }
}
