package net.prototux.aare.aap.channels

import aap_protobuf.service.control.message.UpdateUiConfigRequestOuterClass.UpdateUiConfigRequest
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig
import aap_protobuf.service.media.shared.message.UiThemeOuterClass.UiTheme

/**
 * A head unit's UPDATE_UI_CONFIG_REQUEST, applied as expected. A head
 * unit before protocol 4.3 sends only what changes, merged into the config in use; a newer one
 * sends the whole config, which replaces it. Neither may change the theme this way (older ones
 * may not even name it), and a request without a config is wrong.
 */
internal object UiConfigUpdate {
    sealed interface Result
    /** The config in use from now on. */
    class Accepted(val config: UiConfig) : Result
    class Refused(val why: String) : Result

    fun apply(current: UiConfig?, request: UpdateUiConfigRequest, headUnitAtLeast43: Boolean): Result {
        if (!request.hasUiConfig()) return Refused("no UI config in the request")
        val update = request.uiConfig
        if (!headUnitAtLeast43) {
            if (update.hasUiTheme()) return Refused("a head unit before 4.3 may not set the theme in an update")
            return Accepted((current ?: UiConfig.getDefaultInstance()).toBuilder().mergeFrom(update).build())
        }
        // Unset counts as automatic, on both sides.
        val theme = current?.uiTheme ?: UiTheme.UI_THEME_AUTOMATIC
        if (update.uiTheme != theme) return Refused("the theme can't change in an update (${theme.name} -> ${update.uiTheme.name})")
        return Accepted(update)
    }
}
