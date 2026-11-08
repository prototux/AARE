package net.prototux.aare.video

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
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

    fun dispatchTouch(event: MotionEvent) {
        main.post {
            presentation?.window?.decorView?.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    fun dispatchKey(event: KeyEvent) {
        main.post {
            val decor = presentation?.window?.decorView ?: return@post
            if (decor.findFocus() == null) takeFocus(decor)
            decor.dispatchKeyEvent(event)
        }
    }

    /**
     * Gives the car window's content view focus so rotary / D-pad keys have somewhere to go.
     *
     * Keys and touches are injected straight into the window's view hierarchy, bypassing
     * ViewRootImpl, so the window never gets window focus and never leaves touch mode (ViewRootImpl
     * normally does that on the first key). In touch mode Compose treats clickables as unfocusable,
     * a plain requestFocus() fails, and every key is dropped (dispatchKeyEvent returns false).
     * requestFocusFromTouch() leaves touch mode first, then requests focus.
     */
    private fun takeFocus(decor: View) {
        val target = firstFocusable(decor) ?: return
        target.isFocusableInTouchMode = true
        runCatching { target.requestFocusFromTouch() }.getOrElse {
            Log.w(TAG, "requestFocusFromTouch failed", it)
            target.requestFocus()
        }
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    fun release() {
        main.post {
            presentation?.dismissSafely()
            presentation = null
            display.release()
        }
    }

    private companion object {
        const val TAG = "AareDisplay"
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
