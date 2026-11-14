package net.prototux.aare.video

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import net.prototux.aare.ui.car.CarFocusVisibility

/**
 * The car screen, rendered on the phone.
 *
 * A private virtual display (no MediaProjection needed: it only shows our own windows) draws
 * into the encoder's input surface. A [Presentation] on that display hosts the Compose UI,
 * and touches from the head unit are dispatched straight into its window.
 */
class ProjectionDisplay(
    private val context: Context,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    surface: Surface,
    private val content: @Composable () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val display: VirtualDisplay
    private var presentation: CarPresentation? = null

    init {
        val dm = context.getSystemService(DisplayManager::class.java)
        display = dm.createVirtualDisplay(
            "AARE car", width, height, densityDpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
    }

    /** Must be called on the main thread. */
    fun show() {
        val p = CarPresentation(context, display.display, content)
        p.show()
        presentation = p
    }

    /** Swaps the target surface (e.g. after the encoder restarted). */
    fun setSurface(surface: Surface?) {
        display.surface = surface
    }

    /** Forces a redraw of the presentation window so a new buffer is produced for the encoder. */
    fun invalidate() {
        main.post {
            presentation?.window?.decorView?.invalidate()
        }
    }

    fun dispatchTouch(event: MotionEvent) {
        main.post {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) CarFocusVisibility.keyInput = false
            presentation?.window?.decorView?.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    fun dispatchKey(event: KeyEvent) {
        main.post { dispatchKeyNow(event) }
    }

    /**
     * Moves the car screen's focus with the D-pad key [keyCode], a press and a release (main
     * thread). True when it moved, or when this first key only showed it (left touch mode).
     */
    fun moveFocus(keyCode: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        val moved = dispatchKeyNow(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        dispatchKeyNow(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
        return moved
    }

    /** Main thread. True when the car screen used the key (or leaving touch mode used it up). */
    private fun dispatchKeyNow(event: KeyEvent): Boolean {
        val decor = presentation?.window?.decorView ?: return false
        // Injected keys skip the view root's input stages, which is where a navigation key
        // normally takes a window out of touch mode. Left in it, Compose stays in touch input
        // mode, where clickables can't take focus: a rotary knob or D-pad would have nothing to
        // move to. Only for navigation keys, so a stray steering-wheel key on a touch screen
        // doesn't bring up focus highlights; and checked every time, as the window manager may
        // put the window back in touch mode. Like the platform, the key that leaves touch mode
        // is used up doing so (the first knob detent shows the focus, it doesn't move it on),
        // but only if it did leave it: a refused switch must not eat every key.
        val navigation = event.action == KeyEvent.ACTION_DOWN && isNavigationKey(event.keyCode)
        if (navigation) CarFocusVisibility.keyInput = true
        if (navigation && decor.isInTouchMode) {
            decor.requestFocusFromTouch()
            if (!decor.isInTouchMode) return true
        }
        return decor.dispatchKeyEvent(event)
    }

    /**
     * Runs the car screen's enabled Compose BackHandler, if it has one (main thread). False when
     * none is enabled, for the caller's own Back handling.
     */
    fun back(): Boolean {
        val d = presentation?.onBackPressedDispatcher ?: return false
        // Never let the dispatcher fall back to its default: that would dismiss the Presentation.
        if (!d.hasEnabledCallbacks()) return false
        d.onBackPressed()
        return true
    }

    fun release() {
        main.post {
            presentation?.dismissSafely()
            presentation = null
            display.release()
        }
    }

    private companion object {
        /** Keys that move or use focus, as in the platform's own check for leaving touch mode. */
        fun isNavigationKey(code: Int) = when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> true
            else -> false
        }
    }

    private class CarPresentation(
        outer: Context,
        display: Display,
        private val content: @Composable () -> Unit,
    ) : Presentation(outer, display), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
        OnBackPressedDispatcherOwner {

        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val onBackPressedDispatcher = OnBackPressedDispatcher { super.onBackPressed() }

        override fun onCreate(savedInstanceState: Bundle?) {
            savedState.performRestore(savedInstanceState)
            super.onCreate(savedInstanceState)
            registry.currentState = Lifecycle.State.CREATED
            window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            val view = ComposeView(context).apply { setContent(content) }
            val decor: View = window!!.decorView
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeOnBackPressedDispatcherOwner(this)
            setContentView(view)
        }

        override fun onStart() {
            super.onStart()
            registry.currentState = Lifecycle.State.RESUMED
        }

        override fun onStop() {
            registry.currentState = Lifecycle.State.CREATED
            super.onStop()
        }

        fun dismissSafely() {
            runCatching { dismiss() }
            registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }
}
