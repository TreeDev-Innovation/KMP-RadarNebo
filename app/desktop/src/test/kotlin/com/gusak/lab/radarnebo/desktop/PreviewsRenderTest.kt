package com.gusak.lab.radarnebo.desktop

import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Renders every `@Preview` composable in the shared UI headlessly, so a preview that crashes or
 * draws nothing fails the build rather than only showing up broken in the IDE. PNGs land in
 * `build/snapshots/previews`.
 */
class PreviewsRenderTest {

    @Test
    fun everyPreviewIsFound() {
        val previews = PreviewRenderer.discover()
        assertEquals(PreviewRenderer.annotationCount(), previews.size, "every @Preview is a function the test can call")
        val duplicates = previews.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "preview function names are unique per file: $duplicates")
        assertTrue(previews.size >= 78, "expected the full preview set, found ${previews.size}")
    }

    @Test
    fun everyPreviewRenders() {
        val out = File(System.getProperty("snapshot.dir") ?: "build/snapshots", "previews").apply {
            deleteRecursively()
            mkdirs()
        }
        val failures = PreviewRenderer.discover().mapNotNull { preview ->
            runCatching {
                val image = PreviewRenderer.render(preview, density = 1f)
                File(out, "${preview.id}.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                if (!PreviewRenderer.hasContent(image)) error("rendered a blank image")
            }.exceptionOrNull()?.let { "${preview.name} (${preview.id}): ${it.cause ?: it}" }
        }
        if (failures.isNotEmpty()) fail("${failures.size} previews failed:\n" + failures.joinToString("\n"))
    }
}
