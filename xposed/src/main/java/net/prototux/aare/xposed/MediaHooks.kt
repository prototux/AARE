package net.prototux.aare.xposed

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.os.Build
import android.os.Bundle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.StringReader
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.security.MessageDigest
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Hooks loaded into a media app that only serves its Android Auto browse tree to Google's projection
 * host. Such an app (Spotify, VRadio, …) exposes a `MediaBrowserService` and, in `onGetRoot`, returns
 * a browsable root only when the caller is Android Auto — checked by package name, or by package name
 * plus signing certificate (the AOSP media sample's `PackageValidator`). AARE's own package is on
 * neither allowlist, so it is refused with a `SecurityException` or an empty root.
 *
 * On a phone where Android Auto's package is `com.google.android.projection.gearhead`, this makes
 * AARE's uid look like that caller to the app:
 *  - `onGetRoot(package, uid, hints)` is called with the package rewritten to Android Auto's when the
 *    caller is AARE, so a name-only check passes;
 *  - `getPackagesForUid` / `getNameForUid` map AARE's uid to Android Auto's package, so a validator
 *    that cross-checks the uid against the package (and then its signature) resolves Android Auto's
 *    real certificate instead of AARE's.
 *
 * Only AARE's uid is affected. A signature-checking validator still needs Android Auto installed so
 * its certificate is present to match; a name-only app (VRadio) needs only the first hook.
 */
object MediaHooks {

    private const val GEARHEAD = "com.google.android.projection.gearhead"

    /** Legacy framework browse service, and the media3 session/library services. */
    private val MEDIA_SERVICE_ACTIONS = listOf(
        "android.media.browse.MediaBrowserService",
        "androidx.media3.session.MediaLibraryService",
        "androidx.media3.session.MediaSessionService",
    )

    /** Installs the media hooks if [param]'s app exposes a media browse/session service; else no-op. */
    fun install(xposed: XposedInterface, param: PackageLoadedParam) {
        val uid = aareUid
        if (uid < 0) return
        val legacy = services(param.packageName, "android.media.browse.MediaBrowserService")
        val anyMedia = MEDIA_SERVICE_ACTIONS.any { services(param.packageName, it).isNotEmpty() }
        if (!anyMedia) return
        val cl = param.defaultClassLoader
        legacy.forEach { className -> hookOnGetRoot(xposed, cl, className, uid) }
        hookMedia3Controller(xposed, cl)
        hookUidToPackage(xposed, cl, uid)
        hookCallerAllowlist(xposed, cl, param.packageName)
    }

    /**
     * media3's `MediaSession.ControllerInfo.getPackageName()` → Android Auto's package when the
     * controller is AARE, so a media3 `MediaLibraryService` that checks the connecting controller's
     * package in `onConnect` accepts AARE. (A validator that also checks the signature still relies
     * on the uid→package mapping below, and so on Android Auto being installed for its certificate.)
     */
    private fun hookMedia3Controller(xposed: XposedInterface, cl: ClassLoader) {
        val cls = runCatching { cl.loadClass("androidx.media3.session.MediaSession\$ControllerInfo") }.getOrNull() ?: return
        val method = runCatching { cls.getMethod("getPackageName") }.getOrNull() ?: return
        xposed.hook(method).intercept { chain ->
            val pkg = chain.proceed()
            if (pkg == ModuleMain.AARE_PACKAGE) GEARHEAD else pkg
        }
    }

    /** `onGetRoot(String package, int uid, Bundle)` → called with Android Auto's package for AARE's uid. */
    private fun hookOnGetRoot(xposed: XposedInterface, cl: ClassLoader, className: String, uid: Int) {
        val cls = runCatching { cl.loadClass(className) }.getOrNull() ?: return
        val method = runCatching {
            cls.getMethod("onGetRoot", String::class.java, Int::class.javaPrimitiveType, Bundle::class.java)
        }.getOrNull() ?: return
        xposed.hook(method).intercept { chain ->
            if (chain.getArg(1) == uid) {
                val args = chain.args.toTypedArray()
                args[0] = GEARHEAD
                chain.proceed(args)
            } else {
                chain.proceed()
            }
        }
    }

    /** Map AARE's uid to Android Auto's package for the app's uid→package lookups. */
    private fun hookUidToPackage(xposed: XposedInterface, cl: ClassLoader, uid: Int) {
        val pm = runCatching { cl.loadClass("android.app.ApplicationPackageManager") }.getOrNull() ?: return
        runCatching { pm.getMethod("getPackagesForUid", Int::class.javaPrimitiveType) }.getOrNull()?.let { m ->
            xposed.hook(m).intercept { chain ->
                if (chain.getArg(0) == uid) arrayOf(GEARHEAD) else chain.proceed()
            }
        }
        runCatching { pm.getMethod("getNameForUid", Int::class.javaPrimitiveType) }.getOrNull()?.let { m ->
            xposed.hook(m).intercept { chain ->
                if (chain.getArg(0) == uid) GEARHEAD else chain.proceed()
            }
        }
    }

    // ---- caller allowlist injection (no Android Auto required) ----

    /**
     * Makes a signature-checking media app accept AARE with no Android Auto installed, by adding
     * AARE's own real certificate to the app's caller allowlist.
     *
     * Apps using the AOSP media sample's `PackageValidator` read an allowlist of (package, cert
     * hash) from `res/xml/allowed_media_browser_callers(_v2).xml` and admit a caller only if its real
     * certificate matches an entry. We hook `Resources.getXml` for exactly that resource and return a
     * parser over the original entries plus one for AARE (its real cert hash, computed here), so the
     * validator admits AARE by its genuine signature — no certificate is forged.
     *
     * Defensive: only this resource is touched, and any failure returns the original parser
     * unchanged, so a bug makes the injection a no-op rather than breaking the app's media.
     */
    private fun hookCallerAllowlist(xposed: XposedInterface, cl: ClassLoader, packageName: String) {
        val ids = allowlistResIds(packageName)
        if (ids.isEmpty()) return
        val hashes = aareCertHashes
        if (hashes.isEmpty()) return
        val getXml = runCatching {
            cl.loadClass("android.content.res.Resources").getMethod("getXml", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return
        xposed.hook(getXml).intercept { chain ->
            val original = chain.proceed()
            if (chain.getArg(0) in ids && original is XmlResourceParser) {
                runCatching { augmentedAllowlist(cl, original, hashes) }.getOrDefault(original)
            } else {
                original
            }
        }
    }

    /** The app's allowlist xml resource ids (`allowed_media_browser_callers` and `_v2`), if present. */
    private fun allowlistResIds(packageName: String): Set<Int> = runCatching {
        val ctx = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
        listOf("allowed_media_browser_callers", "allowed_media_browser_callers_v2")
            .map { ctx.resources.getIdentifier(it, "xml", packageName) }
            .filter { it != 0 }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * AARE's signing-certificate SHA-256, in the formats the AOSP validator might compare against
     * (colon-separated and plain hex, each lower and upper case). The validator strips whitespace but
     * not colons, and formats its own hash one specific way; injecting all four covers the variants.
     */
    private val aareCertHashes: List<String> by lazy {
        runCatching {
            val ctx = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(ModuleMain.AARE_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                pm.getPackageInfo(ModuleMain.AARE_PACKAGE, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
            }
            val cert = sigs.firstOrNull()?.toByteArray() ?: return@runCatching emptyList()
            val digest = MessageDigest.getInstance("SHA-256").digest(cert)
            val colonLower = digest.joinToString(":") { "%02x".format(it) }
            listOf(colonLower, colonLower.uppercase(), colonLower.replace(":", ""), colonLower.replace(":", "").uppercase())
        }.getOrDefault(emptyList())
    }

    /** A parser over [original]'s `<signature>` entries plus one admitting AARE by [hashes]. */
    private fun augmentedAllowlist(cl: ClassLoader, original: XmlResourceParser, hashes: List<String>): XmlResourceParser {
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<allowed_callers>\n")
            // Reproduce the app's own entries.
            var event = original.eventType
            while (event != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG && original.name == "signature") {
                    append(signatureXml(original))
                }
                event = original.next()
            }
            // AARE, by its real certificate hash.
            append("  <signature name=\"AARE\" package=\"").append(ModuleMain.AARE_PACKAGE).append("\">\n")
            hashes.forEach { append("    <key release=\"true\">").append(it).append("</key>\n") }
            append("  </signature>\n")
            append("</allowed_callers>\n")
        }
        val backing = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        return xmlResourceParserOver(cl, backing)
    }

    /** Re-serialises one `<signature>` element (with its `<key>` children) from [p] to XML text. */
    private fun signatureXml(p: XmlResourceParser): String = buildString {
        val pkg = p.getAttributeValue(null, "package") ?: ""
        val name = p.getAttributeValue(null, "name") ?: ""
        append("  <signature name=\"").append(name).append("\" package=\"").append(pkg).append("\">\n")
        var event = p.next()
        while (!(event == XmlResourceParser.END_TAG && p.name == "signature")) {
            if (event == XmlResourceParser.START_TAG && p.name == "key") {
                val release = p.getAttributeValue(null, "release") ?: "true"
                val hash = p.nextText().trim()
                append("    <key release=\"").append(release).append("\">").append(hash).append("</key>\n")
            }
            event = p.next()
        }
        append("  </signature>\n")
    }

    /**
     * Wraps an [XmlPullParser] as an [XmlResourceParser] via a dynamic proxy: the validator uses only
     * XmlPullParser methods, which delegate to [backing]; the resource-specific methods it never calls
     * return harmless defaults.
     */
    private fun xmlResourceParserOver(cl: ClassLoader, backing: XmlPullParser): XmlResourceParser {
        val handler = InvocationHandler { _, method, args ->
            val delegate = runCatching { backing.javaClass.getMethod(method.name, *method.parameterTypes) }.getOrNull()
            if (delegate != null) {
                delegate.invoke(backing, *(args ?: emptyArray()))
            } else when (method.returnType) {
                Int::class.javaPrimitiveType, Integer.TYPE -> -1
                Boolean::class.javaPrimitiveType, java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
        return Proxy.newProxyInstance(cl, arrayOf(XmlResourceParser::class.java), handler) as XmlResourceParser
    }

    /** The app's own service class names answering [action], from its manifest. */
    private fun services(packageName: String, action: String): List<String> = runCatching {
        val ctx = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
        val intent = Intent(action).setPackage(packageName)
        @Suppress("DEPRECATION")
        ctx.packageManager.queryIntentServices(intent, 0).mapNotNull { it.serviceInfo?.name }
    }.getOrDefault(emptyList())

    /** AARE's app uid, resolved once from this app process's package manager; -1 if absent. */
    private val aareUid: Int by lazy {
        runCatching {
            val ctx = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            ctx.packageManager.getApplicationInfo(ModuleMain.AARE_PACKAGE, 0).uid
        }.getOrDefault(-1)
    }
}
