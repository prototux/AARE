package net.prototux.aare.host.templates

import androidx.car.app.model.signin.InputSignInMethod
import androidx.car.app.model.signin.PinSignInMethod
import androidx.car.app.model.signin.ProviderSignInMethod
import androidx.car.app.model.signin.QRCodeSignInMethod
import androidx.car.app.model.signin.SignInTemplate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.aare.R

/**
 * SignInTemplate: instructions on the driver side, the sign-in method on the other (text input
 * with our keyboard, PIN, QR code or a provider button). Parked only, like Android Auto.
 */
@Composable
internal fun SignInTemplateView(t: SignInTemplate) {
    val host = LocalTemplateHost.current
    val header = headerSpec(null, t.title, t.headerAction, t.actionStrip)
    TemplateScaffold(header) {
        when {
            t.isLoading -> LoadingBox()
            host.driving -> EmptyMessage(null, stringResource(R.string.tpl_sign_in_parked_only), icon = Icons.Rounded.Lock)
            else -> SignInBody(t)
        }
    }
}

@Composable
private fun SignInBody(t: SignInTemplate) {
    val method = t.signInMethod
    var keyboardSlot: (@Composable () -> Unit)? = null
    var methodUi: @Composable () -> Unit = {}
    when (method) {
        is InputSignInMethod -> {
            val state = rememberInputState(method)
            methodUi = { InputMethodField(method, state) }
            if (state.editing) keyboardSlot = { CarKeyboard(keyboardMode(method.keyboardType), state.sink, Icons.AutoMirrored.Rounded.KeyboardReturn) }
        }
        is PinSignInMethod -> methodUi = { PinCode(method.pinCode.plain()) }
        is QRCodeSignInMethod -> methodUi = { QrCodeView(method.uri.toString()) }
        is ProviderSignInMethod -> methodUi = { ActionView(method.action, ActionLook.Button) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 16.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                CarTextView(t.instructions, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 6)
                if (t.additionalText != null) {
                    Spacer(Modifier.height(12.dp))
                    CarTextView(t.additionalText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
                }
                if (t.actions.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    ActionButtons(t.actions)
                }
            }
            Spacer(Modifier.width(32.dp))
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { methodUi() }
        }
        keyboardSlot?.invoke()
    }
}

private fun keyboardMode(type: Int): KeyboardMode = when (type) {
    InputSignInMethod.KEYBOARD_EMAIL -> KeyboardMode.Email
    InputSignInMethod.KEYBOARD_NUMBER -> KeyboardMode.Number
    InputSignInMethod.KEYBOARD_PHONE -> KeyboardMode.Phone
    else -> KeyboardMode.Text
}

/** Text typed into an InputSignInMethod, forwarded to its InputCallbackDelegate. */
private class InputState(initial: String, initiallyEditing: Boolean, val method: () -> InputSignInMethod, val host: TemplateHost) {
    var text by mutableStateOf(initial)
    var editing by mutableStateOf(initiallyEditing)

    fun change(value: String) {
        text = value
        safeCall("onInputTextChanged") { method().inputCallbackDelegate.sendInputTextChanged(value, host.done) }
    }

    val sink = object : KeyboardSink {
        override fun type(text: String) = change(this@InputState.text + text)
        override fun backspace() {
            if (text.isNotEmpty()) change(text.dropLast(1))
        }
        override fun submit() {
            editing = false
            safeCall("onInputSubmitted") { method().inputCallbackDelegate.sendInputSubmitted(text, host.done) }
        }
        override fun hide() {
            editing = false
        }
    }
}

@Composable
private fun rememberInputState(method: InputSignInMethod): InputState {
    val host = LocalTemplateHost.current
    val latest = rememberUpdatedState(method)
    // A new field (username, then password, from the same screen) starts afresh; the same one re-sent with an
    // error keeps what was typed. Not keyed on the default value: apps may echo the typed text into it.
    return remember(method.inputType, method.keyboardType, method.hint.plain()) {
        InputState(method.defaultValue.plain(), method.isShowKeyboardByDefault, { latest.value }, host)
    }
}

@Composable
private fun InputMethodField(method: InputSignInMethod, state: InputState) {
    val password = method.inputType == InputSignInMethod.INPUT_TYPE_PASSWORD
    val passwordHint = stringResource(R.string.tpl_password_hint)
    Column(Modifier.fillMaxWidth()) {
        CarTextField(
            value = state.text,
            hint = method.hint.plain().ifEmpty { if (password) passwordHint else "" },
            editing = state.editing,
            onFocus = { state.editing = true },
            onClear = { state.change("") },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = if (password) Icons.Rounded.Lock else Icons.Rounded.Person,
            password = password,
            error = method.errorMessage != null,
        )
        if (method.errorMessage != null) {
            Spacer(Modifier.height(8.dp))
            CarTextView(
                method.errorMessage,
                Modifier.padding(start = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun PinCode(pin: String) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        pin.forEach { c ->
            Box(
                Modifier.size(width = 56.dp, height = 76.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(c.toString(), fontSize = 40.sp, color = scheme.onSurface, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A QR code, always dark on white with a quiet zone so phone cameras read it in night mode. */
@Composable
private fun QrCodeView(text: String) {
    val qr = remember(text) { QrCode.encode(text) }
    if (qr == null) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        return
    }
    BoxWithConstraints {
        val side = minOf(maxWidth, maxHeight, 320.dp)
        Box(
            Modifier.size(side).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(side / (qr.size + 8) * 4),
        ) {
            Canvas(Modifier.fillMaxSize().aspectRatio(1f)) {
                val cell = size.width / qr.size
                for (y in 0 until qr.size) for (x in 0 until qr.size) {
                    // cells slightly oversized so no hairlines appear between them
                    if (qr[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
