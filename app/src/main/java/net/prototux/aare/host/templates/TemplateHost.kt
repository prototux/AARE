package net.prototux.aare.host.templates

import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap

/**
 * What the template renderer needs from the car app host.
 *
 * The renderer draws an app's current [Template] with Compose and calls the template's own
 * delegates (OnClickDelegate.sendClick, SearchCallbackDelegate.sendSearchTextChanged, ...)
 * directly, passing [done] as their OnDoneCallback. Host-level operations go through here.
 */
@Stable
interface TemplateHost {
    /** Car moving: honour parked-only actions and list limits, hide keyboards. */
    val driving: Boolean

    /**
     * The car's screen takes touch. Map drags then pan by themselves, so the Pan button an app
     * offers is only needed on cars with a knob or a touchpad, as with Android Auto.
     */
    val touchscreen: Boolean get() = true

    /** The car's night mode: app resource icons have day and night variants. */
    val night: Boolean

    /** The app's label, for headers that use Action.APP_ICON. */
    val appLabel: String

    /**
     * Tint the app's own icons with the text colour, whatever they look like. This is done
     * for a few apps whose icons only work tinted ([net.prototux.aare.host.AppQuirks.FORCE_ICON_TINT]).
     */
    val forceIconTint: Boolean get() = false

    /** Max items for a content type (ConstraintManager.CONTENT_LIMIT_TYPE_*). */
    fun contentLimit(type: Int): Int

    /** Callback handed to every delegate call; reports failures to the host's log. */
    val done: androidx.car.app.OnDoneCallback

    /** Action.BACK, or the system back key: asks the app to pop its screen. */
    fun back()

    /** Loads a CarIcon (app resources, bitmaps, URIs, standard icons) at roughly [sizePx]. */
    fun loadIcon(icon: CarIcon, sizePx: Int): ImageBitmap?

    /** A parked-only action was tapped while driving: tell the driver why nothing happens. */
    fun showParkedOnlyMessage()

    /**
     * The navigation app's map surface, for NavigationTemplate, MapWithContentTemplate,
     * PlaceListMapTemplate, RoutePreviewNavigationTemplate and MapTemplate. The renderer lays its
     * cards over it and reports the unobstructed area with [setVisibleArea]/[setStableArea]
     * (in pixels of the map surface).
     */
    @Composable
    fun MapSurface(modifier: Modifier)

    fun setVisibleArea(left: Int, top: Int, right: Int, bottom: Int)
    fun setStableArea(left: Int, top: Int, right: Int, bottom: Int)

    /** Map gestures in pan mode, forwarded to the app's SurfaceCallback. */
    fun mapScroll(dx: Float, dy: Float)
    fun mapFling(vx: Float, vy: Float)
    fun mapScale(focusX: Float, focusY: Float, factor: Float)
    fun mapClick(x: Float, y: Float)
}

/** Entry point implemented by the renderer: draws any supported template. */
typealias TemplateRenderer = @Composable (template: Template, host: TemplateHost, modifier: Modifier) -> Unit
