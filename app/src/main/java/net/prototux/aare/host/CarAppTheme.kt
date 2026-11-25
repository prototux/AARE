package net.prototux.aare.host

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.os.Build
import android.util.Log
import net.prototux.aare.host.templates.AppThemeColors

/** Reads the colours a Car App Library app declares for the car ([AppThemeColors]). */
internal object CarAppTheme {
    private const val TAG = "CarAppTheme"
    const val THEME_KEY = "androidx.car.app.theme"

    /**
     * The theme an app uses on the car: the one its car service names in its meta-data, else the
     * one its application names there, else the application's own theme. 0 when there is none.
     */
    fun themeRes(serviceMeta: Int, appMeta: Int, appTheme: Int): Int = when {
        serviceMeta != 0 -> serviceMeta
        appMeta != 0 -> appMeta
        else -> appTheme
    }

    /** The app's car colours; [AppThemeColors.NONE] when it has no theme or it can't be read. */
    fun load(context: Context, service: ComponentName): AppThemeColors {
        val pkg = service.packageName
        return try {
            val pm = context.packageManager
            val serviceMeta = runCatching { pm.service(service).metaData?.getInt(THEME_KEY) }.getOrNull() ?: 0
            val app = pm.application(pkg)
            val res = themeRes(serviceMeta, app.metaData?.getInt(THEME_KEY) ?: 0, app.theme)
            if (res == 0) return AppThemeColors.NONE
            // The attributes are the app's own (the library's, merged into its resources), so they
            // are looked up by name in its package, against its theme.
            val theme = context.createPackageContext(pkg, 0).apply { setTheme(res) }.theme
            AppThemeColors(
                primary = theme.colorPair(pkg, "carColorPrimary", "carColorPrimaryDark"),
                secondary = theme.colorPair(pkg, "carColorSecondary", "carColorSecondaryDark"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "$pkg: can't read its car theme", e)
            AppThemeColors.NONE
        }
    }

    private fun Resources.Theme.colorPair(pkg: String, day: String, night: String): AppThemeColors.DayNight? {
        val dayAttr = resources.getIdentifier(day, "attr", pkg)
        val nightAttr = resources.getIdentifier(night, "attr", pkg)
        if (dayAttr == 0 || nightAttr == 0) return null
        return AppThemeColors.pair(color(dayAttr), color(nightAttr))
    }

    private fun Resources.Theme.color(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        try {
            return a.getColor(0, 0)
        } finally {
            a.recycle()
        }
    }

    private fun PackageManager.service(c: ComponentName): ServiceInfo =
        if (Build.VERSION.SDK_INT >= 33) getServiceInfo(c, PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        else @Suppress("DEPRECATION") getServiceInfo(c, PackageManager.GET_META_DATA)

    private fun PackageManager.application(pkg: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= 33) getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        else @Suppress("DEPRECATION") getApplicationInfo(pkg, PackageManager.GET_META_DATA)
}
