package com.gusak.lab.radarnebo.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.MapMode
import com.gusak.lab.radarnebo.data.Threat
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.tracking.DroneTracker
import com.gusak.lab.radarnebo.ui.MapScreen
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.UiState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Renders the map headlessly with a drone track moving between reports, to check the desktop UI draws. */
class MapSnapshotTest {

    private fun alert(id: Long, title: String, oblastUid: Int, type: String = "hromada") = Alert(
        id = id, locationTitle = title, locationType = type, locationUid = id.toString(),
        locationOblastUid = oblastUid, alertLevel = "red",
        threats = listOf(Threat("drones", "red")),
    )

    @Test
    fun rendersMapWithInterpolatedTrack() {
        val now = Clock.System.now().toEpochMilliseconds()
        val tracker = DroneTracker()
        val route = listOf(alert(1, "м. Шостка", 20), alert(2, "м. Конотоп", 20))
        // Reported over Shostka 20 minutes ago, then over Konotop 5 minutes ago: now extrapolated onward.
        route.forEachIndexed { i, a ->
            tracker.update(ThreatMapper.toMarkers(listOf(a), dronesOnly = true), now - (20 - i * 15) * 60_000L)
        }
        val alerts = listOf(route.last(), alert(10, "Харківська область", 22, type = "oblast"))
        val threats = ThreatMapper.toMarkers(alerts, dronesOnly = true)
        val favorites = listOf(FavoriteLocation("1", "Kyiv", 50.4501, 30.5234), FavoriteLocation("2", "Kharkiv", 49.9935, 36.2304))
        val state = UiState(
            settings = AppSettings(serverUrl = "http://localhost:8787"),
            favorites = favorites + FavoriteLocation("3", "Bakhmach", 51.18, 32.83),
            threats = threats,
            mapThreats = threats,
            proximity = ThreatMapper.proximity(favorites + FavoriteLocation("3", "Bakhmach", 51.18, 32.83), threats, 50),
            alertedOblasts = mapOf(20 to false, 22 to true),
            tracks = tracker.snapshot(),
            lastUpdated = Clock.System.now(),
        )

        val out = File(System.getProperty("snapshot.dir") ?: "build/snapshots").apply { mkdirs() }
        ImageComposeScene(width = 1100, height = 820, density = Density(1.5f)) {
            RadarTheme { MapScreen(state, onModeChange = {}, onAddFavorite = { _, _ -> }) }
        }.use { scene ->
            scene.render(0)
            val image = scene.render(2_000_000_000L)
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "map-static.png").writeBytes(png)
            assertTrue(png.size > 10_000)
        }

        ImageComposeScene(width = 1100, height = 820, density = Density(1.5f)) {
            RadarTheme { MapScreen(state.copy(settings = state.settings.copy(mapMode = MapMode.Cinematic)), onModeChange = {}, onAddFavorite = { _, _ -> }) }
        }.use { scene ->
            // Step the frame clock so the cinematic camera completes its fly-to.
            var t = 0L
            var image = scene.render(t)
            repeat(200) {
                t += 25_000_000L
                image = scene.render(t)
            }
            File(out, "map-cinematic.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
