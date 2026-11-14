package net.prototux.aare.media

/**
 * Bundle keys of the Android Auto media contract.
 *
 * Copied from `androidx.media.utils.MediaConstants` and `android.service.media.MediaBrowserService`:
 * androidx.media is only a runtime dependency of media3-session, and the strings are a frozen
 * wire contract, so we keep our own copies instead of adding a dependency.
 */
internal object MediaKeys {
    // ---- root hints we send (also SEARCH_SUPPORTED and CONTENT_STYLE_SUPPORTED below)
    const val ROOT_CHILDREN_LIMIT = "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT"
    const val ROOT_CHILDREN_SUPPORTED_FLAGS = "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_SUPPORTED_FLAGS"
    /** The size browse list icons are drawn at, in pixels (Google's own key, no androidx copy). */
    const val BROWSER_ICON_SIZE = "com.google.android.gms.car.media.BrowserIconSize"

    /** MediaBrowserCompat.MediaItem.FLAG_BROWSABLE: like Android Auto, we ask for browsable root children. */
    const val FLAG_BROWSABLE = 1

    // ---- browse hints the app returns (root extras, and per-item extras for its children)
    const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    const val CONTENT_STYLE_SINGLE_ITEM = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"
    const val CONTENT_STYLE_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
    const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    const val IS_EXPLICIT = "android.media.IS_EXPLICIT"
    const val COMPLETION_STATUS = "android.media.extra.PLAYBACK_STATUS"
    const val COMPLETION_PERCENTAGE = "androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE"

    // ---- session extras: keep a skip button's slot even while it can't be used
    const val RESERVE_PREVIOUS = "android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_PREVIOUS"
    const val RESERVE_NEXT = "android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_NEXT"
    const val RESERVE_PREVIOUS_LEGACY = "com.google.android.gms.car.media.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_PREVIOUS"
    const val RESERVE_NEXT_LEGACY = "com.google.android.gms.car.media.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_NEXT"

    // ---- playback error resolution (PlaybackException / PlaybackState extras)
    const val ERROR_RESOLUTION_ACTION_LABEL = "android.media.extras.ERROR_RESOLUTION_ACTION_LABEL"
    const val ERROR_RESOLUTION_ACTION_INTENT = "android.media.extras.ERROR_RESOLUTION_ACTION_INTENT"

    /** What we advertise: Android Auto's values, so apps hand us the tree they built for it. */
    const val ROOT_CHILDREN_LIMIT_VALUE = 4
    /** Browse icon size (56 dp), sent as pixels at the phone's density. */
    const val BROWSER_ICON_SIZE_DP = 56
    const val ART_SIZE_PX = 512
}
