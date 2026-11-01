@file:Suppress("DEPRECATION") // PlaceListNavigation, RoutePreview and Map templates are deprecated but still used

package net.prototux.aare.host.templates

import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.OnContentRefreshDelegate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.navigation.model.MapTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.PanModeDelegate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import net.prototux.aare.ui.car.carFocusHighlight
import net.prototux.aare.ui.theme.LocalCarDimens
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- obstruction tracking

/** Bounds (in root px) of the map and of everything floating over it. */
internal class ObstructionRegistry {
    var map by mutableStateOf(Rect.Zero)
    val rects = mutableStateMapOf<Any, Rect>()
}

internal val LocalObstructions = staticCompositionLocalOf<ObstructionRegistry?> { null }

/**
 * Marks a card/strip as covering the map: its bounds shrink the visible area reported to the
 * app, and it swallows touches so they do not reach the map gesture layer underneath.
 */
@Composable
internal fun Modifier.obstructsMap(): Modifier {
    val registry = LocalObstructions.current ?: return this
    val key = remember { Any() }
    DisposableEffect(registry) { onDispose { registry.rects.remove(key) } }
    return this
        .onGloballyPositioned { registry.rects[key] = it.boundsInRoot() }
        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
}

// ---------------------------------------------------------------- scaffold

/**
 * A map template frame: the app's map surface fills the area, gestures go to its
 * SurfaceCallback, the action strip sits top end and the map action strip bottom end, and
 * [overlay] lays the template's cards on the start (driver) side.
 *
 * Pan mode (Action.PAN in the map action strip, or dragging the map when it is offered) hides
 * the cards and the action strip and reports PanModeDelegate.sendPanModeChanged. The visible
 * area (map minus whatever floats over it) and the stable area (same, outside pan mode) are
 * sent to the app whenever the layout changes.
 */
@Composable
internal fun MapScaffold(
    actionStrip: ActionStrip?,
    mapActionStrip: ActionStrip?,
    panModeDelegate: PanModeDelegate?,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    val host = LocalTemplateHost.current
    val gutter = LocalCarDimens.current.gutter
    val registry = remember { ObstructionRegistry() }
    val panState = remember { mutableStateOf(false) }
    val panMode = panState.value
    val panOffered = mapActionStrip?.getFirstActionOfType(Action.TYPE_PAN) != null
    val delegate by rememberUpdatedState(panModeDelegate)
    val setPan: (Boolean) -> Unit = { on ->
        if (on != panState.value) {
            panState.value = on
            delegate?.let { d -> safeCall("onPanModeChanged") { d.sendPanModeChanged(on, host.done) } }
        }
    }
    if (panMode && !panOffered) LaunchedEffect(Unit) { setPan(false) }
    // the gesture detector outlives recompositions: read the current template through this
    val onMapDrag by rememberUpdatedState { if (panOffered) setPan(true) }
    // Like Android Auto, drags and flings move the map only where the app offers panning.
    val canPan by rememberUpdatedState(panOffered || panMode)
    DisposableEffect(Unit) {
        onDispose { if (panState.value) delegate?.let { d -> safeCall("onPanModeChanged") { d.sendPanModeChanged(false, host.done) } } }
    }

    Box(modifier.fillMaxSize().onGloballyPositioned { registry.map = it.boundsInRoot() }) {
        // The app's map surface itself is behind this, drawn by CarAppScreen (it must outlive templates).
        Box(Modifier.fillMaxSize().mapGestures(host, canPan = { canPan }) { onMapDrag() })
        CompositionLocalProvider(
            LocalObstructions provides registry,
            LocalPanControl provides PanControl(panMode) { setPan(!panState.value) },
            LocalInCard provides true,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(gutter)) {
                if (!panMode) {
                    overlay()
                    ActionStripView(actionStrip, ActionLook.MapStrip, vertical = false, Modifier.align(Alignment.TopEnd).obstructsMap())
                }
                ActionStripView(mapActionStrip, ActionLook.MapStrip, vertical = true, Modifier.align(Alignment.BottomEnd).obstructsMap())
            }
        }
    }
    ReportAreas(registry, panState)
}

/** Sends visible/stable area changes (map-surface px) to the app, deduplicated. */
@Composable
private fun ReportAreas(registry: ObstructionRegistry, panState: androidx.compose.runtime.State<Boolean>) {
    val host = LocalTemplateHost.current
    LaunchedEffect(registry, host) {
        var lastVisible: List<Int>? = null
        var lastStable: List<Int>? = null
        snapshotFlow { Triple(registry.map, registry.rects.values.toList(), panState.value) }
            .distinctUntilChanged()
            .collect { (map, rects, pan) ->
                if (map.width <= 0f || map.height <= 0f) return@collect
                val free = MapGeometry.unobstructed(map.toBox(), rects.map { it.toBox() })
                val area = listOf(
                    (free.left - map.left).roundToInt(), (free.top - map.top).roundToInt(),
                    (free.right - map.left).roundToInt(), (free.bottom - map.top).roundToInt(),
                )
                if (area != lastVisible) {
                    lastVisible = area
                    safeCall("setVisibleArea") { host.setVisibleArea(area[0], area[1], area[2], area[3]) }
                }
                // stable area: the layout with all chrome shown, i.e. outside pan mode
                if (!pan && area != lastStable) {
                    lastStable = area
                    safeCall("setStableArea") { host.setStableArea(area[0], area[1], area[2], area[3]) }
                }
            }
    }
}

private fun Rect.toBox() = MapGeometry.Box(left, top, right, bottom)

/**
 * Map gestures in SurfaceCallback conventions: onScroll gets GestureDetector-style distances
 * (previous minus current), onFling velocities in px/s, onScale a per-event factor around the
 * focus point, onClick a tap position. [onDragStart] lets the scaffold enter pan mode; scrolls
 * and flings are only sent while [canPan] (checked after it, so the first drag that enters pan
 * mode already moves the map). Zoom and taps always go through.
 */
private fun Modifier.mapGestures(host: TemplateHost, canPan: () -> Boolean, onDragStart: () -> Unit): Modifier = pointerInput(host) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dragging = false
        var multiTouch = false
        var slop = Offset.Zero
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                if (!dragging) onDragStart()
                multiTouch = true
                dragging = true
                val zoom = event.calculateZoom()
                val centroid = event.calculateCentroid(useCurrent = true)
                if (zoom != 1f && zoom.isFinite()) host.mapScale(centroid.x, centroid.y, zoom)
                val pan = event.calculatePan()
                if (pan != Offset.Zero && canPan()) host.mapScroll(-pan.x, -pan.y)
            } else {
                val c = pressed[0]
                tracker.addPosition(c.uptimeMillis, c.position)
                val delta = c.positionChange()
                if (!dragging) {
                    slop += delta
                    if (slop.getDistance() > viewConfiguration.touchSlop) {
                        dragging = true
                        onDragStart()
                        if (canPan()) host.mapScroll(-slop.x, -slop.y)
                    }
                } else if (delta != Offset.Zero && canPan()) {
                    host.mapScroll(-delta.x, -delta.y)
                }
            }
            event.changes.forEach { it.consume() }
        }
        if (!dragging) {
            host.mapClick(down.position.x, down.position.y)
        } else if (!multiTouch) {
            val v = tracker.calculateVelocity()
            if ((abs(v.x) > 300f || abs(v.y) > 300f) && canPan()) host.mapFling(v.x, v.y)
        }
    }
}

// ---------------------------------------------------------------- card placement

/** Width of the start-side card column: about 40% of the screen, within readable bounds. */
internal fun BoxWithConstraintsScope.panelWidth(): Dp = (maxWidth * 0.42f).coerceIn(320.dp, 480.dp).coerceAtMost(maxWidth)

/** Narrow/portrait screens put cards above/below the map instead of beside it. */
internal val BoxWithConstraintsScope.narrow: Boolean get() = maxWidth < 600.dp || maxHeight > maxWidth

/**
 * The floating content card of map templates: start side and full height on wide screens,
 * a bottom sheet-like card on narrow ones.
 */
@Composable
internal fun BoxWithConstraintsScope.MapContentCard(content: @Composable () -> Unit) {
    val r = LocalCarDimens.current.cardRadius
    val m = if (narrow) {
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(maxHeight * 0.55f)
    } else {
        Modifier.align(Alignment.TopStart).width(panelWidth()).fillMaxHeight()
    }
    CarCard(m.shadow(8.dp, RoundedCornerShape(r)).obstructsMap()) { content() }
}

// ---------------------------------------------------------------- templates

/** MapWithContentTemplate: the app's map with a List/Grid/Pane/Message template in a card. */
@Composable
internal fun MapWithContentTemplateView(t: MapWithContentTemplate) {
    val controller = t.mapController
    MapScaffold(t.actionStrip, controller?.mapActionStrip, controller?.panModeDelegate) {
        val content = t.contentTemplate
        MapContentCard {
            when (content) {
                is ListTemplate, is GridTemplate, is PaneTemplate, is MessageTemplate -> TemplateContent(content)
                else -> UnsupportedTemplate(content)
            }
        }
    }
}

/** PlaceListMapTemplate (deprecated, still common for POI apps): places list over the map. */
@Composable
internal fun PlaceListMapTemplateView(t: PlaceListMapTemplate) {
    MapScaffold(t.actionStrip, null, null) {
        MapContentCard {
            MapListCard(
                header = headerSpec(null, t.title, t.headerAction),
                list = t.itemList,
                loading = t.isLoading,
                limitType = ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST,
                refresh = t.onContentRefreshDelegate,
            )
        }
    }
}

/** PlaceListNavigationTemplate (deprecated): like PlaceListMapTemplate, for navigation apps. */
@Composable
internal fun PlaceListNavigationTemplateView(t: PlaceListNavigationTemplate) {
    MapScaffold(t.actionStrip, t.mapActionStrip, t.panModeDelegate) {
        MapContentCard {
            @Suppress("DEPRECATION")
            MapListCard(
                header = headerSpec(t.header, t.title, t.headerAction),
                list = t.itemList,
                loading = t.isLoading,
                limitType = ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST,
                refresh = t.onContentRefreshDelegate,
            )
        }
    }
}

/** RoutePreviewNavigationTemplate (deprecated): route choices and a big Navigate button. */
@Composable
internal fun RoutePreviewNavigationTemplateView(t: RoutePreviewNavigationTemplate) {
    MapScaffold(t.actionStrip, t.mapActionStrip, t.panModeDelegate) {
        MapContentCard {
            @Suppress("DEPRECATION")
            MapListCard(
                header = headerSpec(t.header, t.title, t.headerAction),
                list = t.itemList,
                loading = t.isLoading,
                limitType = ConstraintManager.CONTENT_LIMIT_TYPE_ROUTE_LIST,
                footer = t.navigateAction?.let { action ->
                    { ActionView(action, ActionLook.Button, Modifier.fillMaxWidth().padding(12.dp), fill = true, forcePrimary = true) }
                },
            )
        }
    }
}

/** MapTemplate (deprecated, replaced by MapWithContentTemplate): a Pane or list over the map. */
@Composable
internal fun MapTemplateView(t: MapTemplate) {
    val controller = t.mapController
    MapScaffold(t.actionStrip, controller?.mapActionStrip, controller?.panModeDelegate) {
        val pane = t.pane
        MapContentCard {
            if (pane != null) {
                Column(Modifier.fillMaxSize()) {
                    val header = headerSpec(t.header)
                    if (!header.isEmpty) HeaderBar(header)
                    PaneContent(pane, Modifier.weight(1f))
                }
            } else {
                MapListCard(headerSpec(t.header), t.itemList, loading = false, limitType = ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
            }
        }
    }
}

/** Header, list (or spinner / empty message) and optional footer, for the map list templates. */
@Composable
private fun MapListCard(
    header: HeaderSpec,
    list: ItemList?,
    loading: Boolean,
    limitType: Int,
    refresh: OnContentRefreshDelegate? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val host = LocalTemplateHost.current
    Column(Modifier.fillMaxSize()) {
        val extra: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = refresh?.let { d ->
            {
                Box(
                    Modifier.size(LocalCarDimens.current.touchTarget).clip(CircleShape)
                        .carFocusHighlight(CircleShape)
                        .clickable { safeCall("onContentRefreshRequested") { d.sendContentRefreshRequested(host.done) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(32.dp))
                }
            }
        }
        if (!header.isEmpty || extra != null) HeaderBar(header, extra)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> LoadingBox()
                list == null -> EmptyMessage(null, "Nothing to show")
                else -> ItemListView(listOf(ListSection(null, list)), limitType)
            }
        }
        footer?.invoke()
    }
}
