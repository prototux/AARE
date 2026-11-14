package net.prototux.aare.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import net.prototux.aare.host.templates.maneuverVector
import java.io.ByteArrayOutputStream

/**
 * Turn arrows for an image cluster when the navigation app gives none (a maneuver without an
 * icon, phone guidance without an arrow). A default turn picture is drawn then, in
 * the bitmap format the cluster's colour depth calls for, so the cluster never gets a bare event.
 * AARE draws the same Material arrows as its own turn card.
 */
class TurnIcons {
    private val cache = HashMap<List<Int>, ByteArray>()

    /** A PNG of the arrow for a Car App Library Maneuver.TYPE_*, white, [width]×[height]. */
    @Synchronized
    fun png(type: Int, width: Int, height: Int, colourDepthBits: Int): ByteArray? {
        val key = listOf(type, width, height, colourDepthBits)
        cache[key]?.let { return it }
        return runCatching { render(maneuverVector(type), width, height, bitmapConfig(colourDepthBits)) }
            .onFailure { Log.w(TAG, "turn icon not drawn", it) }
            .getOrNull()
            ?.also { cache[key] = it }
    }

    private fun render(v: ImageVector, width: Int, height: Int, config: Bitmap.Config): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, config)
        val canvas = Canvas(bmp)
        // RGB_565 has no alpha: the arrow is white on black there.
        if (config == Bitmap.Config.RGB_565) canvas.drawColor(Color.BLACK)
        canvas.scale(width / v.viewportWidth, height / v.viewportHeight)
        draw(v.root, canvas, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** Fills the vector's paths (Material icons have no strokes or clip paths). */
    private fun draw(group: VectorGroup, canvas: Canvas, paint: Paint) {
        canvas.save()
        canvas.translate(group.translationX + group.pivotX, group.translationY + group.pivotY)
        canvas.rotate(group.rotation)
        canvas.scale(group.scaleX, group.scaleY)
        canvas.translate(-group.pivotX, -group.pivotY)
        for (node in group) {
            when (node) {
                is VectorGroup -> draw(node, canvas, paint)
                is VectorPath -> if (node.fill != null) {
                    val path = node.pathData.toPath().asAndroidPath()
                    path.fillType = if (node.pathFillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                    paint.alpha = (node.fillAlpha * 255).toInt().coerceIn(0, 255)
                    canvas.drawPath(path, paint)
                }
            }
        }
        canvas.restore()
    }

    companion object {
        private const val TAG = "AareTurnIcons"

        /**
         * The cluster's colour depth: 8 bits alpha only, 16 RGB_565, 32
         * ARGB_8888. It refuses other values; AARE draws those (or none) in full colour.
         */
        fun bitmapConfig(colourDepthBits: Int): Bitmap.Config = when (colourDepthBits) {
            8 -> Bitmap.Config.ALPHA_8
            16 -> Bitmap.Config.RGB_565
            else -> Bitmap.Config.ARGB_8888
        }
    }
}
