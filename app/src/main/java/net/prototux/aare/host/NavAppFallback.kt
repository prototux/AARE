package net.prototux.aare.host

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlin.random.Random

/** The maps app most phones default to; when it is, AARE leaves the usual nav app pick alone. */
const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

/**
 * The Korean-market navigation car apps AARE falls back to, in no particular order, when there is
 * no chosen or single nav app and the phone's default maps app isn't Google Maps.
 */
val KOREAN_NAV_FALLBACK_PACKAGES = setOf(
    "com.locnall.KimGiSa", // KakaoNavi
    "com.skt.tmap.ku", // Tmap
    "com.nhn.android.nmap", // Naver Map
)

/**
 * A random pick among the [installedNavPackages] that are one of [KOREAN_NAV_FALLBACK_PACKAGES],
 * or the only one if just one is installed; null if none are.
 */
fun pickFallbackNavPackage(installedNavPackages: Collection<String>, random: Random = Random): String? {
    val options = installedNavPackages.filter { it in KOREAN_NAV_FALLBACK_PACKAGES }
    return when (options.size) {
        0 -> null
        1 -> options[0]
        else -> options[random.nextInt(options.size)]
    }
}

/** The package resolved as the phone's default handler for a maps intent, or null if none is set. */
fun defaultMapsPackage(context: Context): String? {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=0,0"))
    return context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
}
