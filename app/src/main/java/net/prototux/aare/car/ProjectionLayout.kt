package net.prototux.aare.car

import aap_protobuf.service.media.shared.message.InsetsOuterClass.Insets
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration

/**
 * Where the car UI goes in a [width] x [height] video frame. The head unit shows only the frame
 * minus its margins (the visible area, which its touch panel covers); the UI is also kept clear of
 * the content insets inside it (what the head unit draws over, like a rounded corner or its own bar).
 */
data class ProjectionLayout(
    val width: Int,
    val height: Int,
    val marginLeft: Int,
    val marginTop: Int,
    val marginRight: Int,
    val marginBottom: Int,
    val insetLeft: Int = 0,
    val insetTop: Int = 0,
    val insetRight: Int = 0,
    val insetBottom: Int = 0,
) {
    val visibleWidth get() = width - marginLeft - marginRight
    val visibleHeight get() = height - marginTop - marginBottom
    val padLeft get() = marginLeft + insetLeft
    val padTop get() = marginTop + insetTop
    val padRight get() = marginRight + insetRight
    val padBottom get() = marginBottom + insetBottom
    /** The UI's own area: the visible one minus the content insets. */
    val contentWidth get() = width - padLeft - padRight
    val contentHeight get() = height - padTop - padBottom

    /** For the connection log: empty when the whole frame is the UI's. */
    fun describe(): String {
        fun sides(l: Int, t: Int, r: Int, b: Int) = "l$l t$t r$r b$b"
        val m = if (marginLeft + marginTop + marginRight + marginBottom > 0) " margins ${sides(marginLeft, marginTop, marginRight, marginBottom)}" else ""
        val i = if (insetLeft + insetTop + insetRight + insetBottom > 0) " insets ${sides(insetLeft, insetTop, insetRight, insetBottom)}" else ""
        return m + i
    }

    companion object {
        fun of(config: VideoConfiguration, width: Int, height: Int): ProjectionLayout {
            val ui = config.uiConfig.takeIf { config.hasUiConfig() }
            val insets = when {
                ui == null -> null
                ui.hasContentInsets() -> ui.contentInsets
                // The stable insets stand in when a head unit only sends those.
                ui.hasStableContentInsets() -> ui.stableContentInsets
                else -> null
            }
            return of(width, height, config.widthMargin, config.heightMargin, ui?.takeIf { it.hasMargins() }?.margins, insets)
        }

        /**
         * Explicit per-side [margins] replace the [widthMargin]/[heightMargin] split evenly around a
         * centred rect (the official phone does the same): head units keep sending those for older
         * phones, so adding both would count the margins twice.
         *
         * Every number is a uint32 read into an Int: a huge one (a C head unit's -1 for "unset")
         * comes out negative, and negative padding crashes the composition. So each side is
         * clamped to 0 and to what leaves at least one pixel of visible and of content area.
         */
        fun of(width: Int, height: Int, widthMargin: Int, heightMargin: Int, margins: Insets?, insets: Insets?): ProjectionLayout {
            val (ml, mr) = if (margins != null) clampPair(width, margins.left, margins.right)
                else widthMargin.coerceIn(0, width - 1).let { it / 2 to it - it / 2 }
            val (mt, mb) = if (margins != null) clampPair(height, margins.top, margins.bottom)
                else heightMargin.coerceIn(0, height - 1).let { it / 2 to it - it / 2 }
            val (il, ir) = clampPair(width - ml - mr, insets?.left ?: 0, insets?.right ?: 0)
            val (iTop, iBottom) = clampPair(height - mt - mb, insets?.top ?: 0, insets?.bottom ?: 0)
            return ProjectionLayout(width, height, ml, mt, mr, mb, il, iTop, ir, iBottom)
        }

        /** Two opposite sides of a [total]-pixel span, each at least 0, leaving at least 1 pixel between them. */
        private fun clampPair(total: Int, a: Int, b: Int): Pair<Int, Int> {
            val first = a.coerceIn(0, (total - 1).coerceAtLeast(0))
            return first to b.coerceIn(0, (total - 1 - first).coerceAtLeast(0))
        }
    }
}
