package com.gusak.lab.radarnebo.desktop

import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders every `@Preview` composable in the shared UI headlessly, so a preview that crashes or
 * draws nothing fails the build rather than only showing up broken in the IDE.
 */
class PreviewsRenderTest {
    private val previewFiles = listOf(
        "com.gusak.lab.radarnebo.AppKt",
        "com.gusak.lab.radarnebo.ui.MapScreenKt",
        "com.gusak.lab.radarnebo.ui.FavoritesScreenKt",
        "com.gusak.lab.radarnebo.ui.SettingsScreenKt",
        "com.gusak.lab.radarnebo.ui.TargetsPanelKt",
        "com.gusak.lab.radarnebo.ui.CinematicToggleKt",
        "com.gusak.lab.radarnebo.ui.LiquidGlassKt",
        "com.gusak.lab.radarnebo.ui.map.DroneGlyphKt",
    )

    /** Composable functions compile to static methods taking (Composer, Int changed-flags). */
    private fun previews(): List<Method> = previewFiles.flatMap { name ->
        Class.forName(name).declaredMethods.filter {
            it.name.endsWith("Preview") && it.parameterTypes.contentEquals(arrayOf(Composer::class.java, Int::class.javaPrimitiveType))
        }
    }

    @Test
    fun everyPreviewRenders() {
        val methods = previews()
        assertTrue(methods.size >= 20, "expected all previews, found ${methods.map { it.name }}")
        val out = File(System.getProperty("snapshot.dir") ?: "build/snapshots", "previews").apply { mkdirs() }
        methods.forEach { method ->
            method.isAccessible = true
            ImageComposeScene(width = 700, height = 1100, density = Density(1f)) {
                method.invoke(null, currentComposer, 0)
            }.use { scene ->
                var image = scene.render(0)
                repeat(5) { image = scene.render((it + 1) * 16_000_000L) }
                val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(out, "${method.declaringClass.simpleName}-${method.name}.png").writeBytes(png)
            }
        }
    }
}
