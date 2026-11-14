package net.prototux.aare.media

/**
 * Media apps offered as the default "what to play" when the user hasn't picked one yet and
 * nothing is already playing, in priority order: the first of these actually installed wins.
 */
val DEFAULT_MEDIA_APP_PACKAGES = listOf(
    "com.spotify.music",
    "com.google.android.apps.youtube.music",
    "com.pandora.android",
    "com.amazon.mp3",
)

/** The first of [DEFAULT_MEDIA_APP_PACKAGES] present in [installedPackages], or null if none are. */
fun pickDefaultMediaApp(installedPackages: Collection<String>): String? =
    DEFAULT_MEDIA_APP_PACKAGES.firstOrNull { it in installedPackages }
