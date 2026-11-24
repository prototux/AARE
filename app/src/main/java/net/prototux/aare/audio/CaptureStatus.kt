package net.prototux.aare.audio

/**
 * What each car audio capture method is doing, for the car audio screen: the one in use, why the
 * ones before it aren't used, and the ones left as backup. Pure: [report] works from what the phone
 * offers ([Setup]), the live session ([Live]) and what this phone refused at earlier drives.
 */
object CaptureStatus {
    /** What the phone offers, in more detail than [CaptureOrder.Env]: enough to say why not. */
    data class Setup(
        val sdk: Int,
        /** AARE holds MODIFY_AUDIO_ROUTING itself (the privileged install). */
        val privileged: Boolean,
        /** An su binary is there (Root.found), and the user allowed root. */
        val rootFound: Boolean,
        val rootAllowed: Boolean,
        val shizukuInstalled: Boolean,
        val shizukuRunning: Boolean,
        val shizukuAllowed: Boolean,
        /** A screen-capture consent is held. */
        val consent: Boolean,
    ) {
        val env: CaptureOrder.Env get() = CaptureOrder.Env(
            privileged = privileged,
            shizuku = sdk >= S && shizukuRunning && shizukuAllowed,
            root = sdk >= S && rootAllowed,
            playbackCapture = sdk >= Q && consent,
        )
    }

    /** The session, while connected: the music channel's method, and the methods that failed in it (with why). */
    data class Live(val active: CaptureMethod?, val failed: Map<CaptureMethod, String?> = emptyMap())

    /** A method this phone refused at a drive: why, and when (epoch ms). */
    data class Refusal(val reason: String?, val atMs: Long)

    enum class State {
        /** Running now; before a drive, the one expected. */
        IN_USE,
        /** Would work, after the one in use. */
        BACKUP,
        /** Failed in this session. */
        FAILED,
        /** Refused at the last drive (before a drive): tried first again anyway. */
        REFUSED_LAST_DRIVE,
        /** The user can set it up: [Reason] says how. */
        NEEDS_SETUP,
        /** Nothing to set up in AARE for it on this phone. */
        NOT_AVAILABLE,
        /** Another method is forced. */
        NOT_TRIED,
    }

    enum class Reason {
        NOT_SYSTEM_APP, ANDROID_TOO_OLD, NOT_ROOTED, ROOT_NOT_ALLOWED,
        SHIZUKU_NOT_INSTALLED, SHIZUKU_NOT_RUNNING, SHIZUKU_NOT_ALLOWED, NO_CONSENT,
    }

    data class Entry(
        val method: CaptureMethod,
        val state: State,
        /** Why it can't run (NEEDS_SETUP, NOT_AVAILABLE). */
        val reason: Reason? = null,
        /** The system's refusal, for FAILED and REFUSED_LAST_DRIVE. */
        val detail: String? = null,
        /** When it was refused (REFUSED_LAST_DRIVE), epoch ms. */
        val atMs: Long? = null,
    )

    data class Report(val connected: Boolean, val active: CaptureMethod?, val forced: CaptureMethod?, val methods: List<Entry>)

    /**
     * Every method in [CaptureOrder.ORDER]. While connected ([live]), the session says which one
     * runs and which failed; before a drive, the one expected is the one automatic would pick,
     * counting what this phone [refused] at the last drives (it is still tried first, in case that
     * was a one-off).
     */
    fun report(setup: Setup, live: Live?, refused: Map<CaptureMethod, Refusal>, forced: CaptureMethod?): Report {
        val env = setup.env
        val active = if (live != null) live.active else CaptureOrder.best(env, exclude = refused.keys, forced = forced)
        val entries = CaptureOrder.ORDER.map { m ->
            when {
                forced != null && m != forced -> Entry(m, State.NOT_TRIED)
                m == active -> Entry(m, State.IN_USE)
                live != null && m in live.failed -> Entry(m, State.FAILED, detail = live.failed[m])
                live == null && m in refused -> refused.getValue(m).let { Entry(m, State.REFUSED_LAST_DRIVE, detail = it.reason, atMs = it.atMs) }
                !CaptureOrder.offers(env, m) -> whyNot(setup, m)
                else -> Entry(m, State.BACKUP)
            }
        }
        return Report(live != null, active, forced, entries)
    }

    /** Why [setup] doesn't offer [m]: something to set up, or nothing AARE can do. */
    internal fun whyNot(setup: Setup, m: CaptureMethod): Entry {
        fun setUp(r: Reason) = Entry(m, State.NEEDS_SETUP, r)
        fun none(r: Reason) = Entry(m, State.NOT_AVAILABLE, r)
        val root = when {
            setup.sdk < S -> none(Reason.ANDROID_TOO_OLD)
            setup.rootAllowed -> null
            setup.rootFound -> setUp(Reason.ROOT_NOT_ALLOWED)
            else -> none(Reason.NOT_ROOTED)
        }
        val shizuku = when {
            setup.sdk < S -> none(Reason.ANDROID_TOO_OLD)
            !setup.shizukuInstalled -> none(Reason.SHIZUKU_NOT_INSTALLED)
            !setup.shizukuRunning -> setUp(Reason.SHIZUKU_NOT_RUNNING)
            !setup.shizukuAllowed -> setUp(Reason.SHIZUKU_NOT_ALLOWED)
            else -> null
        }
        return when (m) {
            CaptureMethod.AUDIO_POLICY -> none(Reason.NOT_SYSTEM_APP)
            CaptureMethod.ROOT_POLICY -> root ?: none(Reason.NOT_ROOTED)
            CaptureMethod.SHIZUKU_POLICY -> shizuku ?: none(Reason.SHIZUKU_NOT_INSTALLED)
            // Either would do: say the one the user can act on, root first, as the order goes.
            CaptureMethod.SHIZUKU -> listOfNotNull(root, shizuku).let { all ->
                all.firstOrNull { it.state == State.NEEDS_SETUP } ?: all.firstOrNull() ?: none(Reason.NOT_ROOTED)
            }.copy(method = m)
            CaptureMethod.PLAYBACK_CAPTURE -> if (setup.sdk < Q) none(Reason.ANDROID_TOO_OLD) else setUp(Reason.NO_CONSENT)
        }
    }

    /** The session's state while connected (CarSession sets it, the car audio screen reads it); null when not. */
    val live = kotlinx.coroutines.flow.MutableStateFlow<Live?>(null)

    /** [refused] as Settings keeps it: one "METHOD<tab>atMs<tab>reason" line each. */
    fun encode(refused: Map<CaptureMethod, Refusal>): String =
        refused.entries.joinToString("\n") { (m, r) -> "${m.name}\t${r.atMs}\t${r.reason.orEmpty().replace('\n', ' ').replace('\t', ' ')}" }

    fun decode(text: String?): Map<CaptureMethod, Refusal> = text.orEmpty().lines().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        val m = CaptureMethod.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        m to Refusal(parts.getOrNull(2)?.takeIf { it.isNotEmpty() }, at)
    }.toMap()

    private const val Q = 29
    private const val S = 31
}
