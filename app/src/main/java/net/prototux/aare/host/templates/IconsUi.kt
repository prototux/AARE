package net.prototux.aare.host.templates

import android.graphics.Bitmap
import android.util.LruCache
import androidx.car.app.model.CarIcon
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Draws a CarIcon at [size].
 *
 * Standard icons (BACK, ALERT, ERROR, PAN, ...) are drawn with Material icons; APP_ICON with the
 * app's launcher icon (or its initial); custom icons are loaded through [TemplateHost.loadIcon]
 * off the main thread and cached by content, since every template refresh deserializes new
 * CarIcon objects (a nav app refreshes every second: no flicker allowed).
 *
 * The CarIcon tint wins; otherwise [monochromeTint] is applied to resource (vector) icons only,
 * like Android Auto does for action buttons. Photos/bitmaps are never tinted without a tint.
 */
@Composable
internal fun CarIconView(
    icon: CarIcon?,
    size: Dp,
    modifier: Modifier = Modifier,
    monochromeTint: Color? = null,
    contentScale: ContentScale = ContentScale.Fit,
    shape: Shape? = null,
    contentDescription: String? = null,
    widthFactor: Float = 1f,
) {
    if (icon == null) return
    val scheme = MaterialTheme.colorScheme
    val tint: Color? = if (icon.tint.isSet()) icon.tint.resolve(scheme, LocalContentColor.current) else null
    when (icon.type) {
        CarIcon.TYPE_CUSTOM -> {
            // wide images (lanes, junctions) get a box [widthFactor] times wider than tall
            val sizePx = with(LocalDensity.current) { (size * maxOf(1f, widthFactor)).roundToPx() }
            val bitmap = rememberCarIconBitmap(icon, sizePx)
            val box = modifier.size(width = size * widthFactor, height = size)
            val clipped = if (shape != null) box.clip(shape) else box
            if (bitmap == null) {
                Box(clipped)
                return
            }
            val filterColor = tint ?: monochromeTint?.takeIf { icon.isResource() }
            Image(
                bitmap,
                contentDescription,
                clipped,
                contentScale = contentScale,
                colorFilter = filterColor?.let { ColorFilter.tint(it) },
            )
        }
        CarIcon.TYPE_APP_ICON -> AppIconView(size, modifier)
        else -> {
            val vector = standardIcon(icon.type) ?: return
            val fallback = when (icon.type) {
                CarIcon.TYPE_ERROR -> scheme.error
                CarIcon.TYPE_ALERT -> scheme.tertiary
                else -> monochromeTint ?: LocalContentColor.current
            }
            Icon(vector, contentDescription, modifier.size(size), tint = tint ?: fallback)
        }
    }
}

/** Material equivalents of the library's standard icons. */
internal fun standardIcon(type: Int): ImageVector? = when (type) {
    CarIcon.TYPE_BACK -> Icons.AutoMirrored.Rounded.ArrowBack
    CarIcon.TYPE_ALERT -> Icons.Rounded.Warning
    CarIcon.TYPE_ERROR -> Icons.Rounded.ErrorOutline
    CarIcon.TYPE_PAN -> Icons.Rounded.OpenWith
    CarIcon.TYPE_COMPOSE_MESSAGE -> Icons.Rounded.Edit
    CarIcon.TYPE_MEDIA_PLAYBACK -> Icons.Rounded.MusicNote
    else -> null
}

/** The app's icon (via host.loadIcon(CarIcon.APP_ICON)), or its initial on a tonal circle. */
@Composable
internal fun AppIconView(size: Dp, modifier: Modifier = Modifier) {
    val host = LocalTemplateHost.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap = rememberCarIconBitmap(CarIcon.APP_ICON, sizePx, key = "app-icon@${System.identityHashCode(host)}@$sizePx")
    if (bitmap != null) {
        Image(bitmap, host.appLabel, modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(
            modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                host.appLabel.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = with(LocalDensity.current) { (size * 0.45f).toSp() },
                lineHeight = 0.sp,
            )
        }
    }
}

private fun CarIcon.isResource(): Boolean =
    runCatching { icon?.type == IconCompat.TYPE_RESOURCE }.getOrDefault(false)

/** Loads (and caches) the bitmap of a custom CarIcon; keeps showing the previous one while loading. */
@Composable
private fun rememberCarIconBitmap(icon: CarIcon, sizePx: Int, key: Any? = null): ImageBitmap? {
    val host = LocalTemplateHost.current
    val night = host.night
    // Keyed on identity: CarIcon.equals finds any two bitmap icons equal, so a new arrow would keep the old key.
    // Each refresh deserializes new icons, so the content key is still computed only once per refresh.
    val cacheKey: Any = remember(Same(icon), sizePx, key, night) { key ?: contentKey(icon, night)?.let { "$it@$sizePx" } ?: Same(icon) }
    var bitmap by remember { mutableStateOf(IconCache.get(cacheKey)) }
    LaunchedEffect(cacheKey) {
        val cached = IconCache.get(cacheKey)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        // loadIcon may hit another app's resources or a content URI: keep it off the main thread
        val loaded = withContext(Dispatchers.IO) { runCatching { host.loadIcon(icon, sizePx) }.getOrNull() }
        if (loaded != null) IconCache.put(cacheKey, loaded)
        bitmap = loaded
    }
    return bitmap
}

/**
 * A key that is equal for equal icon content across deserializations, or null if unknown.
 * Resources resolve differently by day and night, so [night] is part of their key.
 */
private fun contentKey(icon: CarIcon, night: Boolean): String? = runCatching {
    val ic = icon.icon ?: return null
    when (ic.type) {
        IconCompat.TYPE_RESOURCE -> "res:${ic.resPackage}:${ic.resId}:${if (night) "night" else "day"}"
        IconCompat.TYPE_URI, IconCompat.TYPE_URI_ADAPTIVE_BITMAP -> "uri:${ic.uri}"
        IconCompat.TYPE_BITMAP, IconCompat.TYPE_ADAPTIVE_BITMAP -> ic.bitmap?.let { "bmp:${it.width}x${it.height}:${pixelHash(it)}" }
        else -> null
    }
}.getOrNull()

/**
 * Content hash of a bitmap. Small ones (maneuver arrows, lanes, junctions) hash every pixel: two arrows may differ
 * only in an exit number or a highlighted lane. Bigger ones (photos, album art) are told apart by 81 sampled pixels.
 */
private fun pixelHash(b: Bitmap): Int {
    if (b.width * b.height <= FULL_HASH_PIXELS) {
        val pixels = IntArray(b.width * b.height)
        b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
        return pixels.contentHashCode()
    }
    var h = 17
    val sx = maxOf(1, b.width / 8)
    val sy = maxOf(1, b.height / 8)
    var y = 0
    while (y < b.height) {
        var x = 0
        while (x < b.width) {
            h = h * 31 + b.getPixel(x, y)
            x += sx
        }
        y += sy
    }
    return h
}

/** Bitmaps up to this many pixels get a full content hash (a 512x128 lanes image is 64K). */
private const val FULL_HASH_PIXELS = 512 * 128

/** Equal only for the same object: a remember or cache key that doesn't go through CarIcon.equals. */
private class Same(private val value: Any) {
    override fun equals(other: Any?) = other is Same && other.value === value
    override fun hashCode() = System.identityHashCode(value)
}

/** Process-wide bitmap cache for template icons, bounded in bytes. */
private object IconCache {
    private val cache = object : LruCache<Any, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Any, value: ImageBitmap): Int = maxOf(1, value.width * value.height * 4)
    }

    fun get(key: Any): ImageBitmap? = cache.get(key)
    fun put(key: Any, value: ImageBitmap) {
        cache.put(key, value)
    }
}
