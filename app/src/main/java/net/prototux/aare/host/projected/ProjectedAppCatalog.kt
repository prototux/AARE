package net.prototux.aare.host.projected

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap

/**
 * An installed app that renders through the private `ICarProjection` protocol instead of the public
 * Car App Library. These apps expose an exported projection service but guard it with an in-process
 * permission check rather than a manifest permission, so there is no safe way to discover them by
 * intent: a known short list is used instead.
 */
data class ProjectedAppInfo(
    val service: ComponentName,
    val label: String,
    val icon: Bitmap?,
) {
    val packageName: String get() = service.packageName
}

object ProjectedAppCatalog {

    /** Known projection services, by (package, service class). */
    private val KNOWN = listOf(
        ComponentName("com.google.android.apps.maps", "com.google.android.apps.gmm.car.GmmCarProjectionService"),
    )

    /** The known projection apps actually installed (and still exporting the service) on this phone. */
    fun query(context: Context, iconSizePx: Int = 128): List<ProjectedAppInfo> {
        val pm = context.packageManager
        return KNOWN.mapNotNull { component ->
            val info = runCatching {
                if (Build.VERSION.SDK_INT >= 33) pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getServiceInfo(component, 0)
            }.getOrNull() ?: return@mapNotNull null
            if (!info.exported) return@mapNotNull null
            ProjectedAppInfo(
                service = component,
                label = info.applicationInfo.loadLabel(pm).toString(),
                icon = runCatching { info.applicationInfo.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull(),
            )
        }
    }

    fun find(context: Context, packageName: String): ProjectedAppInfo? =
        query(context).firstOrNull { it.packageName == packageName }
}
