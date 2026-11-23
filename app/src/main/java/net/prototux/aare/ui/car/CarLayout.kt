package net.prototux.aare.ui.car

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.ui.theme.CarAccents
import net.prototux.aare.ui.theme.CarThemes
import net.prototux.aare.ui.theme.label

/** Where things go on the car screen. What they look like is the theme's job (`CarThemeSpec`). */
enum class CarLayout(
    /** Stored in the settings: never change it once released. */
    val id: String,
) {
    /** A taskbar on the driver's side or along the bottom, and a dashboard of cards, like Android Auto's. */
    TASKBAR("taskbar"),

    /** A dock on the driver's side and a home screen of app pages, like CarPlay's. */
    DOCK("dock"),

    /** A bar along the bottom and the app grid as the home screen, like Android Auto's from 2019 to 2022. */
    CLASSIC("classic");

    companion object {
        fun byId(id: String?): CarLayout = entries.firstOrNull { it.id == id } ?: TASKBAR
    }
}

/** A theme with the layout it was designed for: what the settings' "Style" picks in one go. Named after its theme. */
data class CarStyle(val themeId: String, val layout: CarLayout)

object CarStyles {
    val all: List<CarStyle> = listOf(
        CarStyle("aare", CarLayout.TASKBAR),
        CarStyle("android_auto", CarLayout.TASKBAR),
        CarStyle("android_auto_2019", CarLayout.CLASSIC),
        CarStyle("carplay", CarLayout.DOCK),
        CarStyle("material_you", CarLayout.TASKBAR),
        CarStyle("high_contrast", CarLayout.TASKBAR),
        CarStyle("night", CarLayout.TASKBAR),
        CarStyle("retro", CarLayout.TASKBAR),
        CarStyle("frosted_glass", CarLayout.TASKBAR),
        CarStyle("dark_glass", CarLayout.TASKBAR),
    )

    /** The styles this phone can show (Material You needs Android 12). */
    val available: List<CarStyle> get() = CarThemes.available.map { it.id }.let { ids -> all.filter { it.themeId in ids } }

    /** The style a theme and layout make up, or null for a mix of the user's own ("Custom"). */
    fun matching(themeId: String, layout: CarLayout): CarStyle? =
        all.firstOrNull { it.themeId == themeId && it.layout == layout }
}

/**
 * What a style, theme or accent looks like, for the dot next to its name: its night background to
 * its accent, or its one color. Empty for one without a color of its own (a mix, the theme's or the
 * phone's accent).
 */
internal fun styleSwatch(value: String): List<Color> {
    CarAccents.all.firstOrNull { it.id == value }?.let { return listOf(it.dark.primary) }
    val theme = CarThemes.all.firstOrNull { it.id == value.substringBefore('/') } ?: return emptyList()
    return listOf(theme.night.background, theme.night.primary)
}

/** A dot in [colors] (see [styleSwatch]). */
@Composable
internal fun Swatch(colors: List<Color>, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape)
        .background(Brush.linearGradient(if (colors.size == 1) colors + colors else colors))
        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

/**
 * The Style, Theme, Layout and Accent choices, for the car's and the phone's settings (each draws
 * a choice its own way, through [choice]). A style sets the theme and layout; either alone makes
 * the style "Custom". The accent stays as it is.
 */
@Composable
internal fun StyleChoices(
    themeId: String,
    layoutId: String,
    accentId: String,
    choice: @Composable (title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) -> Unit,
    onChange: (themeId: String, layoutId: String) -> Unit,
    onAccent: (accentId: String) -> Unit,
    /** For the long lists (style, theme, accent): a drop-down on the phone, where [choice]'s row can't fit them. */
    select: @Composable (title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) -> Unit = choice,
) {
    val layout = CarLayout.byId(layoutId)
    val theme = CarThemes.byId(themeId)
    val current = CarStyles.matching(theme.id, layout)
    val custom = "custom"
    val styles = CarStyles.available.map { "${it.themeId}/${it.layout.id}" to CarThemes.byId(it.themeId).label() } +
        (if (current == null) listOf(custom to stringResource(R.string.setup_style_custom)) else emptyList())
    select(stringResource(R.string.setup_choice_style), styles, current?.let { "${it.themeId}/${it.layout.id}" } ?: custom) { picked ->
        CarStyles.all.firstOrNull { "${it.themeId}/${it.layout.id}" == picked }?.let { onChange(it.themeId, it.layout.id) }
    }
    Spacer(Modifier.height(12.dp))
    select(stringResource(R.string.setup_choice_theme), CarThemes.available.map { it.id to it.label() }, theme.id) { onChange(it, layout.id) }
    Spacer(Modifier.height(12.dp))
    choice(stringResource(R.string.setup_choice_layout), listOf(
        CarLayout.TASKBAR.id to stringResource(R.string.setup_layout_taskbar),
        CarLayout.DOCK.id to stringResource(R.string.setup_layout_dock),
        CarLayout.CLASSIC.id to stringResource(R.string.setup_layout_classic),
    ), layout.id) { onChange(theme.id, it) }
    Spacer(Modifier.height(12.dp))
    val accents = listOf(CarAccents.THEME to stringResource(R.string.accent_theme)) +
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(CarAccents.PHONE to stringResource(R.string.accent_phone)) else emptyList()) +
        CarAccents.all.map { it.id to stringResource(it.nameRes) }
    select(stringResource(R.string.setup_choice_accent), accents, accentId, onAccent)
}
