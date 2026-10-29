package com.gusak.lab.radarnebo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gusak.lab.radarnebo.data.ColorPalette
import com.gusak.lab.radarnebo.data.MapBackground
import com.gusak.lab.radarnebo.data.MapMode
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.ui.map.LocalMapTiles
import com.gusak.lab.radarnebo.ui.map.MapSelection
import com.gusak.lab.radarnebo.ui.map.MapTiles
import com.gusak.lab.radarnebo.ui.preview.PreviewData
import io.ktor.client.HttpClient
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Screenshots of every map overlay and dialog, for each map background in each theme, to review text contrast
 * by eye. Satellite tiles are downloaded, so this only runs with `-Dcontrast.screenshots.dir=<dir>`:
 * `./gradlew :shared:jvmTest --tests '*ContrastScreenshotsTest*' -PcontrastScreenshots`.
 */
@OptIn(ExperimentalTestApi::class)
class ContrastScreenshotsTest {
    private val out = System.getProperty("contrast.screenshots.dir")?.let { File(it).apply { mkdirs() } }
    private val palettes = System.getProperty("contrast.palettes")?.split(",")?.map { ColorPalette.valueOf(it) } ?: listOf(ColorPalette.Radar)

    private enum class Dialog { None, Favorites, Settings }

    private companion object {
        const val PAGE_HEIGHT = 1720
        const val SETTINGS_PAGES = 3
    }

    @Test
    fun everyMapBackgroundAndTheme() {
        assumeTrue("run with -PcontrastScreenshots", out != null)
        for (palette in palettes) for (theme in listOf(ThemeMode.Light, ThemeMode.Dark)) for (background in MapBackground.entries) {
            shoot(palette, theme, background)
        }
    }

    private fun shoot(palette: ColorPalette, theme: ThemeMode, background: MapBackground) {
        val name = "${palette.name.lowercase()}-${theme.name.lowercase()}-${background.name.lowercase()}"
        val shots = mutableListOf<Image>()
        runDesktopComposeUiTest(840, PAGE_HEIGHT) { shootScenes(theme, palette, background, name, shots) }
        writeSheets(name, shots + settingsPages(theme, palette, background, name))
    }

    private fun SkikoComposeUiTest.shootScenes(theme: ThemeMode, palette: ColorPalette, background: MapBackground, name: String, shots: MutableList<Image>) {
        var scene by mutableStateOf(Scene())
        // Map markers pulse forever, so the clock only moves when told to.
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Host(theme, palette, background, scene)
            }
        }
        fun save(shot: String) {
            settle()
            val image = Image.makeFromBitmap(captureToImage().asSkiaBitmap())
            File(out, "$name-$shot.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            shots += image
        }
        save("overview")
        scene = Scene(start = MapScreenStart(camera = PreviewData.camera(PreviewData.city("Kyiv"), 5f)), quiet = true); save("cities-kyiv")
        scene = Scene(start = MapScreenStart(camera = PreviewData.camera(PreviewData.city("Lviv"), 4f)), quiet = true); save("cities-lviv")
        scene = Scene(start = MapScreenStart(selectedTrackId = PreviewData.Targets.SHAHED)); save("target-selected")
        scene = Scene(start = MapScreenStart(targetsOpen = true)); save("targets-list")
        scene = Scene(start = MapScreenStart(radiusOpen = true)); save("radius")
        scene = Scene(start = MapScreenStart(pendingFavorite = PreviewData.city("Poltava"))); save("add-favorite")
        val threatened = PreviewData.state(theme).let { s -> s.favorites.firstOrNull { f -> s.proximity.any { it.favorite.id == f.id } } ?: s.favorites.first() }
        scene = Scene(start = MapScreenStart(selection = MapSelection.Favorite(threatened))); save("favorite-card")
        scene = Scene(); settle()
        onNodeWithText("radar-nebo.worxbend.com", substring = true).performClick(); save("server-card")
        scene = Scene(mapMode = MapMode.Cinematic); save("cinematic")
        scene = Scene(dialog = Dialog.Favorites); save("favorites")
        onNodeWithText("Add by coordinates").performClick(); save("coordinates")
        scene = Scene(dialog = Dialog.Settings); save("settings")
    }

    /**
     * The whole Settings dialog, rendered in a window tall enough to need no scrolling (the screen never goes
     * idle, so the test can't scroll it), cut into phone-sized pages.
     */
    private fun settingsPages(theme: ThemeMode, palette: ColorPalette, background: MapBackground, name: String): List<Image> {
        var tall: Image? = null
        runDesktopComposeUiTest(840, PAGE_HEIGHT * SETTINGS_PAGES) {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    Host(theme, palette, background, Scene(dialog = Dialog.Settings))
                }
            }
            settle()
            tall = Image.makeFromBitmap(captureToImage().asSkiaBitmap())
        }
        return (0 until SETTINGS_PAGES).map { page ->
            val surface = Surface.makeRasterN32Premul(840, PAGE_HEIGHT)
            surface.canvas.drawImageRect(
                tall!!,
                Rect.makeXYWH(0f, (page * PAGE_HEIGHT).toFloat(), 840f, PAGE_HEIGHT.toFloat()),
                Rect.makeWH(840f, PAGE_HEIGHT.toFloat()),
            )
            surface.makeImageSnapshot().also {
                File(out, "$name-settings-page${page + 1}.png").writeBytes(it.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }

    /** The shots four to a page at half size, to review a combination at a glance. */
    private fun writeSheets(name: String, shots: List<Image>) {
        shots.chunked(4).forEachIndexed { page, four ->
            val w = four.first().width / 2
            val h = four.first().height / 2
            val surface = Surface.makeRasterN32Premul(w * 2, h * 2)
            four.forEachIndexed { i, image ->
                surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeXYWH((i % 2) * w.toFloat(), (i / 2) * h.toFloat(), w.toFloat(), h.toFloat()), SamplingMode.LINEAR, null, true)
            }
            File(out, "sheet-$name-${page + 1}.png").writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    private data class Scene(
        val start: MapScreenStart = MapScreenStart(),
        val quiet: Boolean = false,
        val mapMode: MapMode = MapMode.Static,
        val dialog: Dialog = Dialog.None,
    )

    @Composable
    private fun Host(theme: ThemeMode, palette: ColorPalette, background: MapBackground, scene: Scene) {
        val scope = rememberCoroutineScope()
        // Loads on the composition's own dispatcher, so tiles land between frames like in the app.
        val tiles = remember { MapTiles(HttpClient(), scope = scope) }
        val base = if (scene.quiet) PreviewData.quiet(theme, favorites = emptyList()) else PreviewData.state(theme)
        val state = base.copy(settings = base.settings.copy(mapBackground = background, colorPalette = palette, mapMode = scene.mapMode))
        RadarTheme(theme, palette) {
            CompositionLocalProvider(LocalMapTiles provides tiles) {
                androidx.compose.runtime.key(scene) {
                    val backdrop = rememberGlassBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        Surface(Modifier.fillMaxSize().glassBackdropSource(backdrop), color = MaterialTheme.colorScheme.background) {
                            MapScreen(state, onModeChange = {}, onAddFavorite = { _, _ -> }, start = scene.start, bottomInset = 88.dp)
                        }
                        GlassDialog(scene.dialog == Dialog.Favorites, "Favorite places", {}, backdrop, subtitle = "Threats within 50 km are flagged.", bottomInset = 88.dp) {
                            FavoritesScreen(state, onAdd = { _, _ -> }, onRemove = {})
                        }
                        GlassDialog(scene.dialog == Dialog.Settings, "Settings", {}, backdrop, bottomInset = 88.dp) {
                            SettingsScreen(state.settings, onChange = {})
                        }
                        GlassNavigationBar(
                            items = listOf(GlassNavItem("Map", "◎"), GlassNavItem("Favorites", "★", badge = 2), GlassNavItem("Settings", "⚙")),
                            selectedIndex = scene.dialog.ordinal,
                            onSelect = {},
                            backdrop = backdrop,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }

    /** Runs frames for a while, with pauses for downloads to land. */
    private fun SkikoComposeUiTest.settle() {
        repeat(40) {
            mainClock.advanceTimeBy(50)
            Thread.sleep(25)
        }
    }
}
