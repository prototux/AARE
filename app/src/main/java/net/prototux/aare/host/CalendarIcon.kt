package net.prototux.aare.host

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import java.time.LocalDate

/** Calendar apps whose launcher icon shows the day of the month, read from their own metadata. */
private val CALENDAR_PACKAGES = setOf("com.google.android.calendar", "com.samsung.android.calendar")

/** The `<meta-data>` key on the launcher activity naming the per-day icon array, as the app itself declares it. */
private const val DYNAMIC_ICONS_META_KEY = "com.google.android.calendar.dynamic_icons"

/** The 0-based index into a per-day icon array for [dayOfMonth] (1-31), or null if out of range. */
fun dynamicCalendarIconIndex(dayOfMonth: Int, arrayLength: Int): Int? {
    val index = dayOfMonth - 1
    return index.takeIf { it in 0 until arrayLength }
}

/**
 * Today's launcher icon for a calendar app that supports it (Google Calendar, Samsung Calendar):
 * the day-of-month entry of its `dynamic_icons` meta-data array, the same one it shows on the
 * phone's own home screen. Null for any other package, or when the app doesn't declare it.
 */
fun dynamicCalendarIcon(context: Context, packageName: String, sizePx: Int): Bitmap? {
    if (packageName !in CALENDAR_PACKAGES) return null
    return try {
        val pm = context.packageManager
        val activity = pm.getLaunchIntentForPackage(packageName)?.component ?: return null
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getActivityInfo(activity, PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            else @Suppress("DEPRECATION") pm.getActivityInfo(activity, PackageManager.GET_META_DATA)
        val arrayRes = info.metaData?.getInt(DYNAMIC_ICONS_META_KEY, 0)?.takeIf { it != 0 } ?: return null
        val resources = pm.getResourcesForApplication(packageName)
        val array = resources.obtainTypedArray(arrayRes)
        val resourceId = try {
            dynamicCalendarIconIndex(LocalDate.now().dayOfMonth, array.length())?.let { array.getResourceId(it, 0) }
        } finally {
            array.recycle()
        }
        resourceId?.takeIf { it != 0 }?.let { ResourcesCompat.getDrawable(resources, it, null)?.toBitmap(sizePx, sizePx) }
    } catch (e: Exception) {
        null
    }
}
