package net.prototux.aare.aap.channels

import aap_protobuf.service.control.message.UpdateUiConfigRequestOuterClass.UpdateUiConfigRequest
import aap_protobuf.service.media.shared.message.UiConfigOuterClass.UiConfig

/**
 * A head unit's UPDATE_UI_CONFIG_REQUEST applied to the config in use. A head unit before
 * protocol 4.3 sends only what changes, merged into the config in use; a newer one sends the
 * whole config, which replaces it. The theme is not changed by an update: one the request names
 * is left out and the rest applied. A request without a config changes nothing.
 */
internal object UiConfigUpdate {
    /** The config in use after [request]. */
    fun apply(current: UiConfig?, request: UpdateUiConfigRequest, headUnitAtLeast43: Boolean): UiConfig {
        val base = current ?: UiConfig.getDefaultInstance()
        if (!request.hasUiConfig()) return base
        val update = request.uiConfig.toBuilder().clearUiTheme().build()
        if (!headUnitAtLeast43) return base.toBuilder().mergeFrom(update).build()
        return if (base.hasUiTheme()) update.toBuilder().setUiTheme(base.uiTheme).build() else update
    }
}
