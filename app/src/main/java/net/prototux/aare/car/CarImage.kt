package net.prototux.aare.car

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Only PNG images of at most 256x256 go on the wire for media album art
 * and phone-status caller thumbnails: the head unit decodes whatever the phone sends and refuses it unless
 * the format is PNG and both sides are within the bound. AARE scales to fit inside that box,
 * keeping aspect ratio, and never upscales past the source (there's nothing to gain from it, and
 * it would just cost more CPU to encode).
 */
internal object CarImage {
    const val MAX_SIDE = 256

    data class Size(val width: Int, val height: Int)

    /**
     * The size to scale [srcWidth]x[srcHeight] down to so neither side exceeds [maxSide], keeping
     * aspect ratio. An image already inside the bound keeps its own size. Pure arithmetic so it's
     * testable without an Android bitmap.
     */
    fun targetSize(srcWidth: Int, srcHeight: Int, maxSide: Int = MAX_SIDE): Size {
        val w = srcWidth.coerceAtLeast(1)
        val h = srcHeight.coerceAtLeast(1)
        val scale = minOf(1.0, maxSide.toDouble() / w, maxSide.toDouble() / h)
        return Size((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
    }

    /** Scales [bmp] to fit inside [maxSide]x[maxSide] (if it doesn't already) and PNG-encodes it. */
    fun toCarPng(bmp: Bitmap, maxSide: Int = MAX_SIDE): ByteArray {
        val size = targetSize(bmp.width, bmp.height, maxSide)
        val scaled = if (size.width == bmp.width && size.height == bmp.height) bmp
            else Bitmap.createScaledBitmap(bmp, size.width, size.height, true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
