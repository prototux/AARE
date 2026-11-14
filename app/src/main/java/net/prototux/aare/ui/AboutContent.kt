package net.prototux.aare.ui

import net.prototux.aare.R

/** The project's own links, for the About screen. Not translatable: URLs, not UI text. */
object AboutLinks {
    const val REPOSITORY = "https://github.com/prototux/AARE"
    const val ISSUES = "https://github.com/prototux/AARE/issues"
    const val DISCUSSIONS = "https://github.com/prototux/AARE/discussions"

    /** Where a GitHub handle shown in the About screen's Thanks list opens to. */
    fun profile(handle: String) = "https://github.com/$handle"
}

/** One heading of the About screen's Thanks list, and the GitHub handles under it. */
data class ThanksGroup(val titleRes: Int, val handles: List<String>)

/**
 * Who to thank, grouped the way README.md's "Thanks" section does. Kept as static data, not
 * fetched at runtime: update it by hand (and the README) when README.md's Thanks section changes,
 * or a merged pull request's author isn't in it yet (`gh pr list --state merged --json author`).
 */
object AboutThanks {
    val GROUPS = listOf(
        ThanksGroup(
            R.string.about_thanks_security_review,
            listOf("dustfeather"),
        ),
        ThanksGroup(
            R.string.about_thanks_code_and_docs,
            listOf(
                "s-ratashnyuk", "JVKeller", "jameswynn", "pafolol", "jooray", "KrishnaX12",
                "freezed23", "MattMaaaan", "l0rdg3x", "akanshu-sapra", "filipeniquini",
            ),
        ),
        ThanksGroup(
            R.string.about_thanks_head_unit_reports,
            listOf(
                "Shane11r", "dani203", "pgwipeout", "tylertneal", "rutgerputter", "ranjowd",
                "remyzerems", "breversa", "alkralor", "xaoc7", "matthias-hoste", "paulhiggs",
                "F4FXL", "sheshuv", "NorwegianGoat", "rogerngks", "notsobadd", "Rikardogonc",
                "opalek84", "l0rdg3x", "spasticmonkey55-glitch", "gitzone83", "djshag01",
                "mirchisait", "filipeniquini", "MattMaaaan", "cfercl",
            ),
        ),
        ThanksGroup(
            R.string.about_thanks_tips,
            listOf("teccheck", "JVKeller"),
        ),
    )
}

/** One open-source component credited on the About screen: tapping it opens [url]. */
data class OssComponent(val name: String, val licence: String, val url: String)

/** One heading of the About screen's open-source-components list, and the components under it. */
data class OssGroup(val titleRes: Int, val components: List<OssComponent>)

/**
 * Every FOSS component the phone app actually ships, grouped the way gradle/libs.versions.toml and
 * each shipped module's build.gradle.kts (app, aap) resolve it: runtime dependencies
 * (implementation/api) only, not test- or debug-only ones, and not build plugins. The :xposed and
 * :headunit modules aren't part of the phone app's APK, so their dependencies (including
 * io.github.libxposed:api, compileOnly and never bundled even in :xposed's own APK) aren't listed
 * here.
 *
 * Licences come from each artifact's POM in the Gradle cache, or the upstream repository's LICENSE
 * where the POM has none (protobuf: BSD-3-Clause, confirmed in its own POM despite GitHub's license
 * detector reporting "NOASSERTION" for the repo). Kept as static data, not read from the dependency
 * graph at runtime: update it by hand when a dependency is added, removed or re-licensed.
 */
object AboutOss {
    val GROUPS = listOf(
        OssGroup(
            R.string.about_oss_libraries_title,
            listOf(
                // AndroidX core/lifecycle/activity, Jetpack Compose + Material3, Car App Library and
                // Media3 - all from the androidx.* Gradle groups, all Apache-2.0.
                OssComponent("AndroidX / Jetpack Compose (Google)", "Apache-2.0", "https://github.com/androidx/androidx"),
                OssComponent("kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
                OssComponent("Protocol Buffers (protobuf-javalite)", "BSD-3-Clause", "https://github.com/protocolbuffers/protobuf"),
                OssComponent("Shizuku API", "MIT", "https://github.com/RikkaApps/Shizuku-API"),
                OssComponent("Android Hidden API Bypass", "Apache-2.0", "https://github.com/LSPosed/AndroidHiddenApiBypass"),
            ),
        ),
        OssGroup(
            R.string.about_oss_protocol_title,
            listOf(
                // aap/src/main/proto/aap_protobuf: AARE's modified copy of aasdk's protobuf/
                // definitions - see aap/src/main/proto/NOTICE.md.
                OssComponent("aasdk protocol definitions", "GPL-3.0-or-later", "https://github.com/opencardev/aasdk"),
            ),
        ),
    )
}
