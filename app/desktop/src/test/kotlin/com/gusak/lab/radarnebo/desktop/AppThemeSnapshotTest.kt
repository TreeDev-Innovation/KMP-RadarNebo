package com.gusak.lab.radarnebo.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.gusak.lab.radarnebo.App
import com.gusak.lab.radarnebo.data.InMemoryStorage
import com.gusak.lab.radarnebo.data.ThemeMode
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Renders the full app (demo data) in both themes, including the floating liquid-glass bar. */
@OptIn(ExperimentalComposeUiApi::class)
class AppThemeSnapshotTest {
    private val width = 1000
    private val height = 760

    private fun save(image: Image, name: String) {
        val out = File(System.getProperty("snapshot.dir") ?: "build/snapshots").apply { mkdirs() }
        File(out, name).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    /** Centre of nav item [index]: bar is 3 x 84dp items + 2 x 6dp padding, centred, 12dp above the bottom. */
    private fun navItem(index: Int) = Offset(width / 2f - 132f + 6f + 84f * index + 42f, height - 12f - 32f)

    private fun render(mode: ThemeMode) {
        val storage = InMemoryStorage().apply {
            putString("theme_mode", mode.name)
            putString("favorites", """[{"id":"1","name":"Kyiv","lat":50.4501,"lon":30.5234},{"id":"2","name":"Kharkiv","lat":49.9935,"lon":36.2304}]""")
        }
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        var applied: Pair<ThemeMode, Boolean>? = null
        ImageComposeScene(width, height, Density(1f)) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                App(storage, onThemeApplied = { m, dark -> applied = m to dark })
            }
        }.use { scene ->
            var t = 0L
            fun frames(n: Int): Image {
                var image = scene.render(t)
                repeat(n) {
                    Thread.sleep(5) // let the view model's first (demo) poll land on the main dispatcher
                    t += 16_000_000L
                    image = scene.render(t)
                }
                return image
            }
            val name = mode.name.lowercase()
            save(frames(60), "app-$name-map.png")
            scene.sendPointerEvent(PointerEventType.Press, navItem(2))
            scene.sendPointerEvent(PointerEventType.Release, navItem(2))
            save(frames(60), "app-$name-settings.png")
            assertEquals(mode to (mode == ThemeMode.Dark), applied)
        }
        owner.viewModelStore.clear()
    }

    @Test
    fun darkTheme() = render(ThemeMode.Dark)

    @Test
    fun lightTheme() = render(ThemeMode.Light)
}
