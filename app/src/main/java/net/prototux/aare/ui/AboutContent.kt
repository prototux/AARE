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
