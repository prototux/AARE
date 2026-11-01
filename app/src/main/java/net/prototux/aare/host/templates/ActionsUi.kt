package net.prototux.aare.host.templates

import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.ui.car.carFocusHighlight
import net.prototux.aare.ui.theme.LocalCarDimens

/** Where an action is drawn; decides its size and default colours. */
internal enum class ActionLook {
    /** Header start/end: plain round icon button, or a tonal pill when titled. */
    Header,

    /** Action strip in a full-screen header: tonal circle/pill. */
    Strip,

    /** Floating over a map (ActionStrip and map ActionStrip): elevated circle/pill. */
    MapStrip,

    /** Pane/Message/SignIn buttons: large pill, filled when FLAG_PRIMARY. */
    Button,

    /** ListTemplate/GridTemplate floating actions. */
    Fab,
}

/** Pan-mode state of the enclosing map template, for Action.PAN buttons. */
internal class PanControl(val active: Boolean, val toggle: () -> Unit)

internal val LocalPanControl = compositionLocalOf<PanControl?> { null }

/**
 * Draws one Action and wires it: BACK pops the app's screen, APP_ICON shows the app icon,
 * PAN toggles the map's pan mode, everything else sends its OnClickDelegate (parked-only aware).
 * [fill] lets a titled action share a row's width; [forcePrimary] draws it filled regardless of
 * FLAG_PRIMARY (the route preview's Navigate button).
 */
@Composable
internal fun ActionView(
    action: Action,
    look: ActionLook,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    forcePrimary: Boolean = false,
) {
    val host = LocalTemplateHost.current
    val pan = LocalPanControl.current
    val d = LocalCarDimens.current
    val scheme = MaterialTheme.colorScheme

    if (action.type == Action.TYPE_APP_ICON) {
        Box(modifier.size(d.touchTarget), contentAlignment = Alignment.Center) { AppIconView(48.dp) }
        return
    }
    if (action.type == Action.TYPE_PAN && pan == null) return // no map here: nothing to pan

    var vector: ImageVector? = null
    var carIcon: CarIcon? = null
    var title: CarText? = null
    var onClick: (() -> Unit)? = null
    when (action.type) {
        Action.TYPE_BACK -> {
            vector = Icons.AutoMirrored.Rounded.ArrowBack
            onClick = host::back
        }
        Action.TYPE_PAN -> {
            vector = if (pan?.active == true) Icons.Rounded.Close else Icons.Rounded.OpenWith
            onClick = pan?.toggle
        }
        else -> {
            carIcon = action.icon
            title = action.title
            val delegate = action.onClickDelegate
            if (delegate != null) onClick = { host.click(delegate) }
        }
    }
    // stable copies for the content lambda
    val vec = vector
    val icon = carIcon
    val label = title
    val titled = !CarText.isNullOrEmpty(label)
    val primary = forcePrimary || (action.flags and Action.FLAG_PRIMARY) != 0
    val custom = if (action.backgroundColor.isSet()) carColor(action.backgroundColor, scheme.primary) else null
    val (bg, fg) = when {
        custom != null -> custom to contentColorOn(custom)
        primary -> scheme.primary to scheme.onPrimary
        look == ActionLook.Header && !titled -> Color.Transparent to scheme.onSurface
        look == ActionLook.Fab -> scheme.primaryContainer to scheme.onPrimaryContainer
        look == ActionLook.Button -> scheme.surfaceContainerHighest to scheme.onSurface
        look == ActionLook.MapStrip -> scheme.surfaceContainerHigh to scheme.onSurface
        else -> scheme.surfaceContainerHigh to scheme.onSurface
    }
    val height: Dp = when (look) {
        ActionLook.Header, ActionLook.Strip -> if (titled) 64.dp else d.touchTarget
        ActionLook.MapStrip -> if (titled) 64.dp else 68.dp
        ActionLook.Button, ActionLook.Fab -> d.touchTarget
    }
    val minWidth = when {
        look == ActionLook.Button && titled -> 160.dp
        else -> height
    }
    val hasIcon = vec != null || icon != null
    val iconSize = if (titled) 30.dp else d.iconSize
    ActionSurface(
        bg = bg,
        height = height,
        minWidth = minWidth,
        elevated = look == ActionLook.MapStrip || look == ActionLook.Fab,
        enabled = action.isEnabled,
        onClick = onClick,
        modifier = modifier,
        horizontalPadding = if (titled) (if (look == ActionLook.Button) 28.dp else 20.dp) else 0.dp,
    ) {
        if (vec != null) {
            Icon(vec, label.plain().ifEmpty { null }, tint = fg, modifier = Modifier.size(iconSize))
        } else if (icon != null) {
            CarIconView(icon, iconSize, monochromeTint = fg)
        }
        if (titled) {
            if (hasIcon) Spacer(Modifier.width(10.dp))
            CarTextView(
                label,
                if (fill) Modifier.weight(1f, fill = false) else Modifier,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
            )
        }
    }
}

@Composable
private fun ActionSurface(
    bg: Color,
    height: Dp,
    minWidth: Dp,
    elevated: Boolean,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier,
    horizontalPadding: Dp,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .then(if (elevated) Modifier.shadow(6.dp, shape) else Modifier)
            .height(height)
            .widthIn(min = minWidth)
            .clip(shape)
            .background(bg)
            .then(if (onClick != null && enabled) Modifier.carFocusHighlight(shape).clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

/** An ActionStrip as a row (top end of a screen) or a column (map controls). */
@Composable
internal fun ActionStripView(strip: ActionStrip?, look: ActionLook, vertical: Boolean, modifier: Modifier = Modifier) {
    val actions = strip?.actions.orEmpty()
    if (actions.isEmpty()) return
    if (vertical) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
            actions.forEach { ActionView(it, look) }
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            actions.forEach { ActionView(it, look) }
        }
    }
}

/** Floating actions of List/Grid/SectionedItem templates, bottom end. */
@Composable
internal fun FloatingActions(actions: List<Action>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        actions.forEach { ActionView(it, ActionLook.Fab) }
    }
}

/** Bottom buttons of Pane/Message/LongMessage/SignIn templates; [fill] shares the width. */
@Composable
internal fun ActionButtons(actions: List<Action>, modifier: Modifier = Modifier, fill: Boolean = false) {
    if (actions.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        actions.forEach { a ->
            ActionView(a, ActionLook.Button, if (fill) Modifier.weight(1f) else Modifier, fill = fill)
        }
    }
}

/** Space to leave under scrolling content so floating actions never hide the last row. */
internal fun fabClearance(actions: List<Action>): Dp = if (actions.isEmpty()) 0.dp else 76.dp + 32.dp
