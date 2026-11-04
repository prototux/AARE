package net.prototux.aare.host.templates

import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.SearchTemplate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.ui.theme.LocalCarDimens

/**
 * SearchTemplate: a search field in the header, results below, and our on-screen keyboard.
 * Typing is refused while driving (like Android Auto); results stay usable.
 */
@Composable
internal fun SearchTemplateView(t: SearchTemplate) {
    val host = LocalTemplateHost.current
    val d = LocalCarDimens.current
    val delegate = t.searchCallbackDelegate
    // local text state: the app echoes results, not the text, so we own what is typed
    var query by remember { mutableStateOf(t.initialSearchText.orEmpty()) }
    var keyboardWanted by remember { mutableStateOf(t.isShowKeyboardByDefault) }
    val keyboard = keyboardWanted && !host.driving

    fun changed(text: String) {
        query = text
        safeCall("onSearchTextChanged") { delegate.sendSearchTextChanged(text, host.done) }
    }
    val sink = object : KeyboardSink {
        override fun type(text: String) = changed(query + text)
        override fun backspace() {
            if (query.isNotEmpty()) changed(query.dropLast(1))
        }
        override fun submit() {
            keyboardWanted = false
            safeCall("onSearchSubmitted") { delegate.sendSearchSubmitted(query, host.done) }
        }
        override fun hide() {
            keyboardWanted = false
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().height(d.touchTarget + 20.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            t.headerAction?.let {
                ActionView(it, ActionLook.Header)
                Spacer(Modifier.width(8.dp))
            }
            CarTextField(
                value = query,
                hint = t.searchHint ?: stringResource(R.string.media_action_search),
                editing = keyboard,
                onFocus = {
                    if (host.driving) host.showParkedOnlyMessage() else keyboardWanted = true
                },
                onClear = { changed("") },
                modifier = Modifier.weight(1f),
                leadingIcon = Icons.Rounded.Search,
            )
            ActionStripView(t.actionStrip, ActionLook.Strip, vertical = false, Modifier.padding(start = 12.dp))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val results: @Composable (Modifier) -> Unit = { m ->
                Box(m) {
                    val list = t.itemList
                    when {
                        t.isLoading -> LoadingBox()
                        list != null && list.items.isNotEmpty() ->
                            ItemListView(listOf(ListSection(null, list)), ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
                        list != null && query.isNotEmpty() && !keyboard -> EmptyMessage(list.noItemsMessage, stringResource(R.string.tpl_no_results))
                    }
                }
            }
            if (!keyboard) {
                results(Modifier.fillMaxSize())
            } else if (maxWidth >= 900.dp && maxWidth > maxHeight * 1.6f) {
                // wide screens: results on the driver side, keyboard beside them
                Row(Modifier.fillMaxSize()) {
                    results(Modifier.weight(0.42f).fillMaxHeight())
                    CarKeyboard(KeyboardMode.Text, sink, Icons.Rounded.Search, Modifier.weight(0.58f).align(Alignment.Bottom), keyHeight = 64.dp)
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    results(Modifier.weight(1f).fillMaxWidth())
                    CarKeyboard(KeyboardMode.Text, sink, Icons.Rounded.Search)
                }
            }
        }
    }
}
