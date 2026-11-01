package net.prototux.aare.host.templates

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardCapslock
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.ui.theme.LocalCarDimens

/** Layout of the on-screen keyboard, from InputSignInMethod.KEYBOARD_* or search. */
internal enum class KeyboardMode { Text, Email, Number, Phone }

/** The edit operations the keyboard produces. */
internal interface KeyboardSink {
    fun type(text: String)
    fun backspace()
    fun submit()
    fun hide()
}

private sealed interface Key {
    val weight: Float

    data class Char(val c: String, override val weight: Float = 1f) : Key
    data class Fn(val kind: FnKind, override val weight: Float) : Key
    data class Gap(override val weight: Float) : Key
}

private enum class FnKind { Shift, Backspace, Symbols, Letters, Space, Submit, Hide }

private val LETTERS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val SYMBOLS = listOf("1234567890", "@#&_-+()/", "*\"':;!?")

/**
 * A car-sized on-screen keyboard. The app is drawn on a virtual display streamed to the car,
 * where the phone's IME cannot appear, so text entry needs its own keyboard.
 */
@Composable
internal fun CarKeyboard(mode: KeyboardMode, sink: KeyboardSink, submitIcon: ImageVector, modifier: Modifier = Modifier, keyHeight: Dp = 58.dp) {
    var symbols by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    val rows: List<List<Key>> = when (mode) {
        KeyboardMode.Number, KeyboardMode.Phone -> listOf(
            listOf(Key.Char("1"), Key.Char("2"), Key.Char("3"), Key.Fn(FnKind.Backspace, 1f)),
            listOf(Key.Char("4"), Key.Char("5"), Key.Char("6"), Key.Fn(FnKind.Submit, 1f)),
            listOf(Key.Char("7"), Key.Char("8"), Key.Char("9"), Key.Fn(FnKind.Hide, 1f)),
            listOf(Key.Char(if (mode == KeyboardMode.Phone) "+" else "."), Key.Char("0"), Key.Char(if (mode == KeyboardMode.Phone) "#" else "-"), Key.Gap(1f)),
        )
        else -> {
            val src = if (symbols) SYMBOLS else LETTERS
            val r1 = src[0].map { Key.Char(it.toString()) }
            val r2 = listOf(Key.Gap(0.5f)) + src[1].map { Key.Char(it.toString()) } + listOf(Key.Gap(if (symbols) 0.5f + (9 - src[1].length) else 0.5f))
            val r3 = listOf(Key.Fn(if (symbols) FnKind.Letters else FnKind.Shift, 1.5f)) +
                src[2].map { Key.Char(it.toString()) } + listOf(Key.Fn(FnKind.Backspace, 1.5f))
            val email = mode == KeyboardMode.Email
            val r4 = listOfNotNull(
                Key.Fn(if (symbols) FnKind.Letters else FnKind.Symbols, 1.5f),
                if (email) Key.Char("@") else Key.Char(","),
                Key.Fn(FnKind.Space, if (email) 3.5f else 4.5f),
                if (email) Key.Char(".com", 1f) else null,
                Key.Char("."),
                Key.Fn(FnKind.Hide, 1f),
                Key.Fn(FnKind.Submit, 1.5f),
            )
            listOf(r1, r2, r3, r4)
        }
    }
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().background(scheme.surfaceContainerLow).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    val m = Modifier.weight(key.weight).height(keyHeight)
                    when (key) {
                        is Key.Gap -> Spacer(m)
                        is Key.Char -> {
                            val label = if (shift && !symbols) key.c.uppercase() else key.c
                            KeyCap(m, scheme.surfaceContainerHighest, onClick = {
                                sink.type(label)
                                shift = false
                            }) {
                                Text(label, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Clip)
                            }
                        }
                        is Key.Fn -> {
                            val (bg, fg) = when {
                                key.kind == FnKind.Submit -> scheme.primary to scheme.onPrimary
                                key.kind == FnKind.Shift && shift -> scheme.primaryContainer to scheme.onPrimaryContainer
                                else -> scheme.surfaceContainerHigh to scheme.onSurface
                            }
                            KeyCap(m, bg, onClick = {
                                when (key.kind) {
                                    FnKind.Shift -> shift = !shift
                                    FnKind.Backspace -> sink.backspace()
                                    FnKind.Symbols -> symbols = true
                                    FnKind.Letters -> symbols = false
                                    FnKind.Space -> sink.type(" ")
                                    FnKind.Submit -> sink.submit()
                                    FnKind.Hide -> sink.hide()
                                }
                            }) {
                                when (key.kind) {
                                    FnKind.Symbols -> Text(stringResource(R.string.kbd_symbols_label), style = MaterialTheme.typography.labelLarge, color = fg)
                                    FnKind.Letters -> Text(stringResource(R.string.kbd_letters_label), style = MaterialTheme.typography.labelLarge, color = fg)
                                    else -> Icon(fnIcon(key.kind, submitIcon), null, tint = fg, modifier = Modifier.size(30.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fnIcon(kind: FnKind, submit: ImageVector): ImageVector = when (kind) {
    FnKind.Shift -> Icons.Rounded.KeyboardCapslock
    FnKind.Backspace -> Icons.AutoMirrored.Rounded.Backspace
    FnKind.Space -> Icons.Rounded.SpaceBar
    FnKind.Submit -> submit
    FnKind.Hide -> Icons.Rounded.KeyboardHide
    else -> Icons.AutoMirrored.Rounded.KeyboardReturn
}

@Composable
private fun KeyCap(modifier: Modifier, bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * A read-only text field look: the text (or hint), a blinking caret while the keyboard is open,
 * and a clear button. Tapping it asks for the keyboard via [onFocus].
 */
@Composable
internal fun CarTextField(
    value: String,
    hint: String,
    editing: Boolean,
    onFocus: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = Icons.Rounded.Search,
    password: Boolean = false,
    error: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val d = LocalCarDimens.current
    // animate only while editing: the car display is a video stream, idle frames are free
    val caretAlpha = if (editing) {
        val alpha by rememberInfiniteTransition(label = "caret").animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(530), RepeatMode.Reverse),
            label = "caret",
        )
        alpha
    } else {
        1f
    }
    val border = when {
        error -> scheme.error
        editing -> scheme.primary
        else -> Color.Transparent
    }
    Row(
        modifier.height(d.touchTarget - 4.dp).clip(RoundedCornerShape(percent = 50)).background(border)
            .padding(2.dp).clip(RoundedCornerShape(percent = 50)).background(scheme.surfaceContainerHigh)
            .clickable(onClick = onFocus).padding(start = 22.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
        }
        val shown = if (password) "•".repeat(value.length) else value
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (shown.isEmpty() && !editing) {
                Text(hint, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                // keep the end (where the caret is) visible for long text
                Text(
                    shown.takeLast(60),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (editing) Box(Modifier.padding(start = 2.dp).width(3.dp).height(30.dp).alpha(caretAlpha).background(scheme.primary))
                if (shown.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(percent = 50)).clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.common_clear), tint = scheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            }
        }
    }
}
