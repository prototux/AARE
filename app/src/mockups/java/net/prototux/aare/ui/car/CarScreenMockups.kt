package net.prototux.aare.ui.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import net.prototux.aare.car.NextEvent
import net.prototux.aare.car.Weather
import net.prototux.aare.notify.PhoneGuidance
import net.prototux.aare.ui.theme.CarAccents
import net.prototux.aare.ui.theme.CarTheme
import net.prototux.aare.ui.theme.CarThemes
import net.prototux.aare.ui.theme.withAccent
import org.junit.Rule
import org.junit.Test

/**
 * Mockups of the car screen in each style, for the README: drawn on the JVM by Paparazzi with made-up
 * apps, music and directions (no emulator, no phone). The navigation app's map is a drawn stand-in,
 * and app icons and album art are generated. Run `./gradlew :app:recordPaparazziDebug --tests
 * '*CarScreenMockups*'`; the images land in app/src/test/snapshots/images. A real car screen can
 * differ (fonts, the map, the phone's status and colours).
 */
class CarScreenMockups {
    @get:Rule
    val paparazzi = Paparazzi(
        // A common head-unit size, at 160 dpi so 1 dp is 1 pixel.
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1280,
            screenHeight = 720,
            xdpi = 160,
            ydpi = 160,
            density = Density.MEDIUM,
            orientation = ScreenOrientation.LANDSCAPE,
            softButtons = false,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Test fun aare() = shot("aare", night = true)
    @Test fun aareDay() = shot("aare", night = false)
    @Test fun androidAuto() = shot("android_auto", night = true)
    @Test fun androidAuto2019() = shot("android_auto_2019", night = true, screen = CarScreen.Launcher)
    @Test fun carPlay() = shot("carplay", night = true)
    @Test fun carPlayDay() = shot("carplay", night = false)
    @Test fun materialYou() = shot("material_you", night = true)
    @Test fun highContrast() = shot("high_contrast", night = true)
    @Test fun nightAmber() = shot("night", night = true)
    @Test fun nightRed() = shot("night", night = true, accent = "red")
    @Test fun retro() = shot("retro", night = true)
    @Test fun retroAmber() = shot("retro", night = true, accent = "amber")

    /** The style made of [themeId] and its layout, as the settings' Style picker would set it. */
    private fun shot(themeId: String, night: Boolean, accent: String? = null, screen: CarScreen = CarScreen.Dashboard) {
        val style = CarStyles.all.first { it.themeId == themeId }
        val state = mockState(night, screen).apply {
            theme = CarThemes.byId(themeId).withAccent(CarAccents.byId(accent))
            layout = style.layout
        }
        paparazzi.snapshot {
            CarTheme(night = state.night, theme = state.theme) {
                CarShell(state, NoActions, appContent = { _, m -> MapStandIn(night, m) }, navMap = { _, m -> MapStandIn(night, m) })
            }
        }
    }

    private fun mockState(night: Boolean, screen: CarScreen) = CarUiState().apply {
        this.night = night
        this.screen = screen
        val maps = app("maps", "Maps", CarAppKind.NAVIGATION, 0xFF34A853.toInt())
        val music = app("music", "Music", CarAppKind.MEDIA, 0xFFE8453C.toInt())
        apps += listOf(
            maps, music,
            app("podcasts", "Podcasts", CarAppKind.MEDIA, 0xFF8E44AD.toInt()),
            app("radio", "Radio", CarAppKind.MEDIA, 0xFFF39C12.toInt()),
            app("messages", "Messages", CarAppKind.MESSAGING, 0xFF1A73E8.toInt()),
            app("phone", "Phone", CarAppKind.PHONE, 0xFF0F9D58.toInt()),
            app("notifications", "Notifications", CarAppKind.NOTIFICATIONS, 0xFF5F6368.toInt()),
            app("settings", "Settings", CarAppKind.SETTINGS, 0xFF607D8B.toInt()),
        )
        activeNavAppId = maps.id
        activeMediaAppId = music.id
        recentAppIds += listOf("podcasts", "messages")
        nowPlaying = NowPlaying(music.id, "Night Drive", "The Synthwaves", albumArt(), playing = true,
            positionMs = 95_000, durationMs = 240_000, updatedAtMs = 0)
        // Directions only off the dashboard: on it, they would replace the map with a directions card.
        if (screen != CarScreen.Dashboard) phoneGuidance = PhoneGuidance("mock", "maps", "Maps", "300 m", "Turn right onto Aarstrasse",
            "12 min · 8.4 km", arrow = null, distance = null, instruction = "Turn right onto Aarstrasse")
        weather = Weather(18, "°C", 1, day = !night)
        nextEvent = NextEvent("Coffee with Sam", startMs = System.currentTimeMillis() + 45 * 60_000, allDay = false, location = "Bern")
    }

    private fun app(id: String, label: String, kind: CarAppKind, color: Int) = CarAppEntry(id, "mock.$id", label, appIcon(label, color), kind)

    /** A round icon in [color] with the label's initial. */
    private fun appIcon(label: String, color: Int): Bitmap {
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawCircle(48f, 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; textSize = 52f; textAlign = Paint.Align.CENTER }
        c.drawText(label.take(1), 48f, 66f, text)
        return b
    }

    /** A sunset gradient in place of album art. */
    private fun albumArt(): Bitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 256f, 256f, 0xFFFF7E5F.toInt(), 0xFF6A3093.toInt(), Shader.TileMode.CLAMP) }
        Canvas(b).drawRect(0f, 0f, 256f, 256f, p)
        return b
    }

    private object NoActions : CarActions {
        override fun openApp(app: CarAppEntry) = Unit
        override fun goHome() = Unit
        override fun openLauncher() = Unit
        override fun assistant() = Unit
        override fun mediaPlayPause() = Unit
        override fun mediaNext() = Unit
        override fun mediaPrevious() = Unit
        override fun dismissNotice(notice: CarNotice) = Unit
        override fun answerCall() = Unit
        override fun endCall() = Unit
        override fun navigateTo(place: String) = Unit
    }
}

/** A drawn stand-in for a navigation app's map: streets, a route and the car's position. */
@androidx.compose.runtime.Composable
private fun MapStandIn(night: Boolean, modifier: Modifier) {
    val ground = if (night) Color(0xFF1B2330) else Color(0xFFE8EAED)
    val street = if (night) Color(0xFF34404F) else Color.White
    Canvas(modifier.fillMaxSize().background(ground)) {
        val w = size.width
        val h = size.height
        for (i in 1..5) drawLine(street, Offset(0f, h * i / 6f), Offset(w, h * i / 6f - 30f), strokeWidth = 14f)
        for (i in 1..4) drawLine(street, Offset(w * i / 5f, 0f), Offset(w * i / 5f + 40f, h), strokeWidth = 14f)
        val route = Path().apply { moveTo(w * 0.5f, h); lineTo(w * 0.52f, h * 0.55f); lineTo(w * 0.8f, h * 0.5f) }
        drawPath(route, Color(0xFF4285F4), style = Stroke(width = 16f))
        drawCircle(Color.White, 18f, Offset(w * 0.505f, h * 0.85f))
        drawCircle(Color(0xFF4285F4), 12f, Offset(w * 0.505f, h * 0.85f))
    }
}
