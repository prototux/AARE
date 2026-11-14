package net.prototux.aare.ui.car

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.ui.theme.CarThemes

/** Where things go on the car screen. What they look like is the theme's job (`CarThemeSpec`). */
enum class CarLayout(
    /** Stored in the settings: never change it once released. */
    val id: String,
) {
    /** A taskbar on the driver's side or along the bottom, and a dashboard of cards, like Android Auto's. */
    TASKBAR("taskbar"),

    /** A dock on the driver's side and a home screen of app pages, like CarPlay's. */
    DOCK("dock");

    companion object {
        fun byId(id: String?): CarLayout = entries.firstOrNull { it.id == id } ?: TASKBAR
    }
}

/** A theme with the layout it was designed for: what the settings' "Style" picks in one go. */
data class CarStyle(val themeId: String, val layout: CarLayout) {
    /** The theme's name, which is also the style's. */
    val name: String get() = CarThemes.byId(themeId).name
}

object CarStyles {
    val all: List<CarStyle> = listOf(
        CarStyle("aare", CarLayout.TASKBAR),
    )

    /** The style a theme and layout make up, or null for a mix of the user's own ("Custom"). */
    fun matching(themeId: String, layout: CarLayout): CarStyle? =
        all.firstOrNull { it.themeId == themeId && it.layout == layout }
}

/**
 * The Style, Theme and Layout choices, for the car's and the phone's settings (each draws a choice
 * its own way, through [choice]). A style sets both; a theme or layout alone makes the style "Custom".
 */
@Composable
internal fun StyleChoices(
    themeId: String,
    layoutId: String,
    choice: @Composable (title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) -> Unit,
    onChange: (themeId: String, layoutId: String) -> Unit,
) {
    val layout = CarLayout.byId(layoutId)
    val theme = CarThemes.byId(themeId)
    val current = CarStyles.matching(theme.id, layout)
    val custom = "custom"
    val styles = CarStyles.all.map { "${it.themeId}/${it.layout.id}" to it.name } +
        (if (current == null) listOf(custom to stringResource(R.string.setup_style_custom)) else emptyList())
    choice(stringResource(R.string.setup_choice_style), styles, current?.let { "${it.themeId}/${it.layout.id}" } ?: custom) { picked ->
        CarStyles.all.firstOrNull { "${it.themeId}/${it.layout.id}" == picked }?.let { onChange(it.themeId, it.layout.id) }
    }
    Spacer(Modifier.height(12.dp))
    choice(stringResource(R.string.setup_choice_theme), CarThemes.all.map { it.id to it.name }, theme.id) { onChange(it, layout.id) }
    Spacer(Modifier.height(12.dp))
    choice(stringResource(R.string.setup_choice_layout), listOf(
        CarLayout.TASKBAR.id to stringResource(R.string.setup_layout_taskbar),
        CarLayout.DOCK.id to stringResource(R.string.setup_layout_dock),
    ), layout.id) { onChange(theme.id, it) }
}
