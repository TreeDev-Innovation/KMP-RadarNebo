package com.gusak.lab.radarnebo.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.MapMode
import com.gusak.lab.radarnebo.ui.MapScreen
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.UiState
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class CinematicToggleTest {

    private val width = 900
    private val height = 600

    // Button: 48dp, 12dp from the top-right corner (no status bar inset on desktop).
    private val button = Offset(width - 12f - 24f, 12f + 24f)

    /** Saves a 2x crop around the toggle so its look in each state can be inspected. */
    private fun saveButton(image: Image, name: String) {
        val crop = 72
        val surface = Surface.makeRasterN32Premul(crop * 2, crop * 2)
        val src = Rect.makeXYWH(button.x - crop / 2, button.y - crop / 2, crop.toFloat(), crop.toFloat())
        surface.canvas.drawImageRect(image, src, Rect.makeWH(crop * 2f, crop * 2f))
        val out = File(System.getProperty("snapshot.dir") ?: "build/snapshots").apply { mkdirs() }
        File(out, name).writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    @Test
    fun toggleSwitchesBetweenStaticAndCinematic() {
        var mode by mutableStateOf(MapMode.Static)
        val changes = mutableListOf<MapMode>()
        ImageComposeScene(width, height, Density(1f)) {
            RadarTheme {
                MapScreen(
                    UiState(settings = AppSettings(mapMode = mode)),
                    onModeChange = { changes += it; mode = it },
                    onAddFavorite = { _, _ -> },
                )
            }
        }.use { scene ->
            var t = 0L
            fun frames(n: Int): Image {
                var image = scene.render(t)
                repeat(n) { t += 16_000_000L; image = scene.render(t) }
                return image
            }
            fun click() {
                scene.sendPointerEvent(PointerEventType.Press, button)
                scene.sendPointerEvent(PointerEventType.Release, button)
            }

            saveButton(frames(5), "toggle-static.png")
            click()
            saveButton(frames(4), "toggle-transition.png")
            saveButton(frames(60), "toggle-cinematic.png")
            assertEquals(MapMode.Cinematic, mode)

            click()
            frames(40)
            assertEquals(listOf(MapMode.Cinematic, MapMode.Static), changes)
            saveButton(frames(1), "toggle-static-again.png")
        }
    }
}
