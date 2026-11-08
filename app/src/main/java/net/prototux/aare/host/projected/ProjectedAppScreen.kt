package net.prototux.aare.host.projected

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Shows a projected app on the car screen. A [SurfaceView] provides the render target; its surface
 * is handed to a [ProjectedHost], which binds the app and asks it to draw there. Touch on the view
 * is forwarded to the app in the surface's own pixel space (the SurfaceView fills this slot, so view
 * coordinates already match the projected display's coordinates).
 *
 * The host lives for as long as this composable is on screen: it starts when the surface is created
 * and stops when the surface is destroyed or the composable leaves.
 */
@Composable
fun ProjectedAppScreen(
    app: ProjectedAppInfo,
    densityDpi: Int,
    hostFactory: (ProjectedAppInfo) -> ProjectedHost,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            SurfaceView(context).apply {
                var host: ProjectedHost? = null
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        host = hostFactory(app).also { it.start(holder.surface, width, height, densityDpi) }
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        host?.stop()
                        host = null
                    }
                })
                // Forward touches to the projected app; coordinates are already in surface space.
                setOnTouchListener { _: View, event -> host?.sendMotion(event); true }
            }
        },
    )

    // Nothing to clean up here directly; the SurfaceHolder callback owns the host's lifetime.
    DisposableEffect(app.service) { onDispose { } }
}
