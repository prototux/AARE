package net.prototux.aare.ui.car

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A control a rotary knob or D-pad can't reach or highlight is unusable on a knob-only head unit
 * (most Mazdas, older iDrive, Lexus/Mercedes touchpads), even though it works fine by touch. This
 * scans the car-screen sources (not the phone UI) for the raw patterns that skip [carClickable]'s
 * focus highlight, so a new one can't slip in unnoticed the way DockShell's tap targets and
 * DashboardSettings' card rows once did.
 *
 * It's a plain text scan, not a compiler check: no Robolectric, no UI test framework, nothing but
 * [java.io.File] and a handful of [Regex]. That keeps it cheap, but it also means it can be fooled
 * by, say, a renamed import; it's a safety net, not a guarantee.
 *
 * A call site that's exempt (a decorative element, a gesture with no knob equivalent yet, a
 * control [carClickable] doesn't cover) goes in [allowlist] with a one-line reason, rather than
 * being special-cased in the scan itself. Prefer migrating to [carClickable] over adding to it.
 */
class CarScreenReachabilityCheckTest {

    /** Car-screen packages, relative to the app module's root; the phone UI isn't checked. */
    private val scanRoots = listOf(
        "src/main/java/net/prototux/aare/ui/car",
        "src/main/java/net/prototux/aare/media",
        "src/main/java/net/prototux/aare/host",
    )

    /**
     * One construct the scan rejects, and the regex (applied line by line) that finds it. With
     * [keyPathBefore], it's accepted when one of the [KEY_PATH_LOOKBACK] lines before it matches
     * that: the same modifier chain gives the gesture a knob path.
     */
    private data class BannedPattern(val name: String, val regex: Regex, val keyPathBefore: Regex? = null)

    private val bannedPatterns = listOf(
        BannedPattern("a raw .clickable", Regex("""\.clickable\s*[(\{]""")),
        BannedPattern("a raw .toggleable", Regex("""\.toggleable\s*[(\{]""")),
        BannedPattern("a raw .selectable", Regex("""\.selectable\s*[(\{]""")),
        BannedPattern("a raw combinedClickable", Regex("""\bcombinedClickable\s*[(\{]""")),
        BannedPattern("a tap gesture detector", Regex("""\bdetectTapGestures\s*\(""")),
        // A drag that changes a value is fine with carAdjustable as its knob path, just before it.
        BannedPattern("a drag gesture detector", Regex("""\bdetectDragGestures\s*\("""), keyPathBefore = Regex("""\.carAdjustable\s*\(""")),
        BannedPattern("a Material Slider", Regex("""\bSlider\s*\(""")),
        BannedPattern("a Material Switch", Regex("""\bSwitch\s*\(""")),
        BannedPattern("a Material Checkbox", Regex("""\bCheckbox\s*\(""")),
    )

    /**
     * A justified exception: [file] (relative to the app module's root, as printed by a failure of
     * this test), a [linePattern] substring that pins it to one line (so the entry doesn't silently
     * cover a different, unreviewed call site later added to the same file), and the one-line
     * [reason] it's fine as is. Keep this list short: prefer moving the call site to [carClickable]
     * over adding to it.
     */
    private data class Allowed(val file: String, val linePattern: String, val reason: String)

    private val allowlist = listOf(
        Allowed(
            "src/main/java/net/prototux/aare/ui/car/CarFocus.kt",
            "carFocusHighlight(shape).clickable(enabled = enabled, onClick = onClick)",
            "carClickable's own implementation: the one .clickable car-screen code should route through.",
        ),
        Allowed(
            "src/main/java/net/prototux/aare/ui/car/CarFocus.kt",
            ".combinedClickable(enabled = enabled, onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick)",
            "carClickable's own long-press variant (the rail's 'open beside').",
        ),
        Allowed(
            "src/main/java/net/prototux/aare/ui/car/HomeScreen.kt",
            ".clickable(enabled = false) {}",
            "Not a control: swallows taps behind the confirmation card so they don't reach the home screen.",
        ),
        Allowed(
            "src/main/java/net/prototux/aare/ui/car/RailWidgets.kt",
            "Checkbox(checked = shown, onCheckedChange = null)",
            "Decorative: the row around it is carClickable.",
        ),
        Allowed(
            "src/main/java/net/prototux/aare/ui/car/CarSettingsScreen.kt",
            "Switch(checked = checked, onCheckedChange = onChange)",
            "the row around it is carClickable and already knob-focusable and highlighted; no " +
                "knob-steppable switch exists yet (carAdjustable, issue #320 item 3).",
        ),
        Allowed(
            "src/main/java/net/prototux/aare/ui/car/DashboardSettings.kt",
            "Checkbox(checked = shown, onCheckedChange = null, enabled = canToggle)",
            "decorative only (onCheckedChange = null); the row around it is carClickable and carries the knob focus and highlight.",
        ),
        Allowed(
            "src/main/java/net/prototux/aare/host/templates/ListUi.kt",
            "Switch(",
            "the row around it is carClickable and already knob-focusable and highlighted, same as CarSettingsScreen's SwitchRow.",
        ),
        Allowed(
            "src/main/java/net/prototux/aare/media/NowPlayingView.kt",
            "Slider(",
            "the playback seek bar; stepping it by knob needs carAdjustable, not yet built (issue #320 item 3).",
        ),
        Allowed(
            "src/main/java/net/prototux/aare/ui/car/CallCard.kt",
            "detectDragGestures(",
            "repositions the floating in-call strip only, a touch convenience with no knob equivalent; " +
                "the strip's tap and its hang-up button are both carClickable.",
        ),
    )

    /** The app module's root: these tests run with it as the working directory. */
    private fun moduleRoot(): File {
        val cwd = File(".").absoluteFile
        check(File(cwd, "src/main/java").isDirectory) { "expected to run from the app module's root, got $cwd" }
        return cwd
    }

    @Test
    fun everyCarScreenControlIsKnobReachableOrAllowlisted() {
        val root = moduleRoot()
        val violations = mutableListOf<String>()
        val usedAllowlistEntries = mutableSetOf<Allowed>()

        for (scanRoot in scanRoots) {
            val dir = File(root, scanRoot)
            check(dir.isDirectory) { "scan root doesn't exist: $scanRoot (did it move?)" }
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val relativePath = file.relativeTo(root).path.replace(File.separatorChar, '/')
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    val lineNumber = index + 1
                    bannedPatterns.forEach { pattern ->
                        val keyPath = pattern.keyPathBefore?.let { re ->
                            lines.subList(maxOf(0, index - KEY_PATH_LOOKBACK), index).any { re.containsMatchIn(it) }
                        } == true
                        if (pattern.regex.containsMatchIn(line) && !keyPath) {
                            val allowed = allowlist.firstOrNull { it.file == relativePath && line.contains(it.linePattern) }
                            if (allowed != null) {
                                usedAllowlistEntries += allowed
                            } else {
                                violations += "$relativePath:$lineNumber: ${pattern.name} (\"${line.trim()}\"). " +
                                    "Use Modifier.carClickable(shape, onClick) instead, or add an allowlist entry " +
                                    "with a one-line reason to CarScreenReachabilityCheckTest.allowlist."
                            }
                        }
                    }
                }
            }
        }

        assertTrue(
            "Found car-screen controls the knob/D-pad can't reach or highlight:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )

        val stale = allowlist.filterNot { it in usedAllowlistEntries }
        assertTrue(
            "Stale allowlist entries (nothing matches them any more; remove them):\n" +
                stale.joinToString("\n") { "${it.file}: ${it.linePattern}" },
            stale.isEmpty(),
        )
    }

    private companion object {
        /** How many lines before a gesture its modifier chain's carAdjustable may be. */
        const val KEY_PATH_LOOKBACK = 4
    }
}
