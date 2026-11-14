package net.prototux.aare.host.templates

import androidx.car.app.model.CarColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import net.prototux.aare.ui.theme.CarColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppThemeColorsTest {
    private val day = lightColorScheme(primary = Color(0xFF1565C0), tertiary = Color(0xFF6A1B9A), background = Color.White)
    private val night = darkColorScheme(primary = Color(0xFF90CAF9), tertiary = Color(0xFFCE93D8), background = Color.Black)
    private val colors = CarColors(
        accept = Color.Green, decline = Color.Red, onCall = Color.White,
        red = Color.Red, green = Color.Green, blue = Color.Blue, yellow = Color.Yellow, inkOnLight = Color.Black,
    )
    private val app = AppThemeColors(
        primary = AppThemeColors.DayNight(0xFFFF6F00.toInt(), 0xFFFFB74D.toInt()),
        secondary = AppThemeColors.DayNight(0xFF2E7D32.toInt(), 0xFF81C784.toInt()),
    )

    @Test
    fun primaryAndSecondaryAreTheAppsColoursDayAndNight() {
        assertEquals(Color(0xFFFF6F00), CarColor.PRIMARY.resolve(day, colors, Color.Gray, app))
        assertEquals(Color(0xFFFFB74D), CarColor.PRIMARY.resolve(night, colors, Color.Gray, app))
        assertEquals(Color(0xFF2E7D32), CarColor.SECONDARY.resolve(day, colors, Color.Gray, app))
        assertEquals(Color(0xFF81C784), CarColor.SECONDARY.resolve(night, colors, Color.Gray, app))
    }

    @Test
    fun withoutAppColoursTheThemeAccentStays() {
        assertEquals(day.primary, CarColor.PRIMARY.resolve(day, colors, Color.Gray))
        assertEquals(night.tertiary, CarColor.SECONDARY.resolve(night, colors, Color.Gray))
        // only the pair the app sets changes
        val primaryOnly = AppThemeColors(primary = app.primary)
        assertEquals(Color(0xFFFF6F00), CarColor.PRIMARY.resolve(day, colors, Color.Gray, primaryOnly))
        assertEquals(day.tertiary, CarColor.SECONDARY.resolve(day, colors, Color.Gray, primaryOnly))
    }

    @Test
    fun otherColoursIgnoreTheAppTheme() {
        assertEquals(colors.red, CarColor.RED.resolve(day, colors, Color.Gray, app))
        assertEquals(Color.Gray, CarColor.DEFAULT.resolve(day, colors, Color.Gray, app))
        val custom = CarColor.createCustom(0xFF112233.toInt(), 0xFF445566.toInt())
        assertEquals(Color(0xFF445566), custom.resolve(night, colors, Color.Gray, app))
    }

    @Test
    fun aPairNeedsBothColours() {
        assertEquals(AppThemeColors.DayNight(1, 2), AppThemeColors.pair(1, 2))
        assertNull(AppThemeColors.pair(0xFFFF0000.toInt(), 0))
        assertNull(AppThemeColors.pair(0, 0xFFFF0000.toInt()))
    }
}
