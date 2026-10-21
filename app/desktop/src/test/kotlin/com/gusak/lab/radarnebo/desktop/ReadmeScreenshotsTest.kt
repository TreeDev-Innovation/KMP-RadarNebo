package com.gusak.lab.radarnebo.desktop

import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes the README's screenshots to `docs/screenshots` from the app's previews. Skipped unless run with
 * `./gradlew :app:desktop:test --tests '*ReadmeScreenshotsTest*' -PupdateReadmeScreenshots`.
 */
class ReadmeScreenshotsTest {
    /** Screenshot file name to the preview function it shows. */
    private val shots = linkedMapOf(
        "app-phone" to "ScreenPreviewsKt-AppPhoneDarkPreview",
        "app-phone-light" to "ScreenPreviewsKt-AppPhoneLightPreview",
        "map-overview" to "MapPreviewsKt-MapOverviewDarkPreview",
        "map-overview-tablet" to "MapPreviewsKt-MapOverviewTabletPreview",
        "target-selected" to "MapPreviewsKt-MapShahedSelectedPreview",
        "missile-selected-tablet" to "MapPreviewsKt-MapMissileSelectedTabletPreview",
        "targets-list" to "TargetPreviewsKt-TargetsListEveryStatePreview",
        "target-states-map" to "MapPreviewsKt-TargetsEveryStatePreview",
        "cinematic" to "MapPreviewsKt-CinematicThreatPreview",
        "cinematic-tablet" to "MapPreviewsKt-CinematicThreatTabletPreview",
        "cities-zoomed" to "MapPreviewsKt-CitiesKyivPreview",
        "cities-dots" to "MapPreviewsKt-CitiesDotsPreview",
        "every-threat-type" to "MapPreviewsKt-MapEveryThreatTypePreview",
        "target-icons" to "TargetGlyphsKt-TargetIconsDarkPreview",
        "threat-card" to "MapPreviewsKt-ThreatCardPreview",
        "add-favorite" to "MapPreviewsKt-AddFavoritePinPreview",
        "warning-radius" to "MapPreviewsKt-RadiusControlPreview",
        "favorites" to "ScreenPreviewsKt-FavoritesWithWarningsPreview",
        "settings" to "SettingsScreenKt-SettingsScreenDarkPreview",
        "target-types-legend" to "SettingsScreenKt-SettingsTargetTypesPreview",
    )

    @Test
    fun writeReadmeScreenshots() {
        val dir = System.getProperty("readme.screenshots.dir")
        assumeTrue("run with -PupdateReadmeScreenshots", dir != null)
        val out = File(dir!!).apply { mkdirs() }
        val previews = PreviewRenderer.discover().associateBy { it.id }
        shots.forEach { (file, id) ->
            val preview = previews[id] ?: error("no preview $id")
            val image = PreviewRenderer.render(preview, density = 1.5f)
            assertTrue(PreviewRenderer.hasContent(image), "$id rendered blank")
            File(out, "$file.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
