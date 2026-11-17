package net.prototux.aare.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.graphics.drawable.toBitmap

/** A Car App Library app installed on the phone. */
data class CarAppInfo(
    val service: ComponentName,
    val label: String,
    val icon: Bitmap?,
    val categories: Set<String>,
    val minApiLevel: Int,
    /** <uses-permission> names from the app's manifest (AndroidManifest, not the runtime grant). */
    val requestedPermissions: Set<String>,
) {
    val packageName: String get() = service.packageName
    val isNavigation: Boolean get() = decideIsNavigation(categories, requestedPermissions)

    companion object {
        const val SERVICE_ACTION = "androidx.car.app.CarAppService"
        const val CATEGORY_NAVIGATION = "androidx.car.app.category.NAVIGATION"
        const val CATEGORY_POI = "androidx.car.app.category.POI"
        const val CATEGORY_IOT = "androidx.car.app.category.IOT"
        const val CATEGORY_MESSAGING = "androidx.car.app.category.MESSAGING"
        const val CATEGORY_CLUSTER = "androidx.car.app.category.FEATURE_CLUSTER"
        const val MIN_API_KEY = "androidx.car.app.minCarApiLevel"
        /** Gates NavigationTemplate and the rest of the navigation template set. */
        const val PERMISSION_NAVIGATION_TEMPLATES = "androidx.car.app.NAVIGATION_TEMPLATES"
        /** Gates drawing straight to the car's surface (the map canvas); navigation apps hold this too. */
        const val PERMISSION_ACCESS_SURFACE = "androidx.car.app.ACCESS_SURFACE"
    }
}

/**
 * Whether a Car App Library app qualifies for the navigation role (dashboard map card, nav rail
 * slot, "navigate to"). The NAVIGATION category in the service's intent filter is only the app's
 * own claim, declared in its manifest: any app can add it. Android Auto instead goes by whether
 * the app actually requests the navigation-template permission, since that's what gates
 * NavigationTemplate and the rest of the navigation template set; an app with the category but not
 * the permission request can still use the Car App Library, just as a templated app.
 */
internal fun decideIsNavigation(categories: Set<String>, requestedPermissions: Set<String>): Boolean =
    CarAppInfo.CATEGORY_NAVIGATION in categories && CarAppInfo.PERMISSION_NAVIGATION_TEMPLATES in requestedPermissions

/** Finds Car App Library apps: services answering `androidx.car.app.CarAppService`. */
object CarAppCatalog {
    private const val TAG = "CarAppCatalog"
    // Packages already logged as category-only, so a package manager refresh doesn't repeat it.
    private val loggedCategoryOnly = HashSet<String>()

    fun query(context: Context, iconSizePx: Int = 128): List<CarAppInfo> {
        val pm = context.packageManager
        val intent = Intent(CarAppInfo.SERVICE_ACTION)
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_RESOLVED_FILTER
        val found = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            else @Suppress("DEPRECATION") pm.queryIntentServices(intent, flags)
        return found.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (si.packageName == context.packageName) return@mapNotNull null
            val categories = buildSet {
                ri.filter?.categoriesIterator()?.forEach { add(it) }
            }
            val appInfo = si.applicationInfo
            val minApi = appInfo.metaData?.getInt(CarAppInfo.MIN_API_KEY, 0)?.takeIf { it > 0 }
                ?: si.metaData?.getInt(CarAppInfo.MIN_API_KEY, 0) ?: 0
            val requestedPermissions = requestedPermissions(pm, si.packageName)
            val info = CarAppInfo(
                service = ComponentName(si.packageName, si.name),
                label = (si.loadLabel(pm).takeIf { it.isNotBlank() } ?: appInfo.loadLabel(pm)).toString(),
                icon = runCatching { si.loadIcon(pm).toBitmap(iconSizePx, iconSizePx) }.getOrNull(),
                categories = categories,
                minApiLevel = minApi,
                requestedPermissions = requestedPermissions,
            )
            if (CarAppInfo.CATEGORY_NAVIGATION in categories && !info.isNavigation && loggedCategoryOnly.add(info.packageName)) {
                Log.i(TAG, "${info.packageName} declares the navigation category but not ${CarAppInfo.PERMISSION_NAVIGATION_TEMPLATES}: treated as a templated app")
            }
            info
        }.distinctBy { it.service }
    }

    private fun requestedPermissions(pm: PackageManager, packageName: String): Set<String> = runCatching {
        val pi = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            else @Suppress("DEPRECATION") pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        pi.requestedPermissions?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())
}
