package com.gusak.lab.radarnebo.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.TrackPredictor
import com.gusak.lab.radarnebo.tracking.TrackSample
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.ui.MapScreen
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.UiState
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Renders tracked demo drones, the targets panel and a selected target's timeline. */
@OptIn(ExperimentalComposeUiApi::class)
class TargetsSnapshotTest {

    private fun state(): UiState {
        val end = Clock.System.now().toEpochMilliseconds()
        val threats = emptyList<com.gusak.lab.radarnebo.domain.ThreatMarker>()
        val favorites = listOf(FavoriteLocation("1", "Home", 50.45, 30.60))
        return UiState(
            settings = AppSettings(themeMode = ThemeMode.Dark),
            favorites = favorites,
            threats = threats,
            mapThreats = threats,
            proximity = ThreatMapper.proximity(favorites, threats, 50),
            tracks = listOf(southbound(end), westbound(end)),
            lastUpdated = Clock.System.now(),
        )
    }

    /** A Shahed group the server tracks from the Chernihiv border towards Kyiv, reported every few minutes. */
    private fun southbound(now: Long) = DroneTrack(
        id = "S1-D07", level = ThreatLevel.Red, oblastUid = 25, active = true,
        firstSeen = now - 40 * 60_000L, lastSeen = now, markerIds = emptyList(),
        samples = listOf(
            TrackSample(now - 40 * 60_000L, 51.68, 33.91, "Hlukhiv", 20, "Ударні БпЛА типу «Шахед»"),
            TrackSample(now - 20 * 60_000L, 51.24, 33.20, "Konotop", 20, "Ударні БпЛА типу «Шахед»"),
            TrackSample(now - 60_000L, 51.05, 32.10, "Bakhmach", 25, "Ударні БпЛА типу «Шахед»"),
        ),
    )

    /** A group east of Kyiv flying west at ~150 km/h: always has a predicted next settlement. */
    private fun westbound(now: Long) = DroneTrack(
        id = "trk-99", level = ThreatLevel.Red, oblastUid = 14, active = true,
        firstSeen = now - 10 * 60_000L, lastSeen = now, markerIds = listOf("west"),
        samples = listOf(
            TrackSample(now - 10 * 60_000L, 50.45, 31.40, "Yahotyn approach", 14),
            TrackSample(now - 60_000L, 50.45, 31.05, "Boryspil Raion", 14),
        ),
    )

    private fun save(image: Image, name: String) {
        val out = File(System.getProperty("snapshot.dir") ?: "build/snapshots").apply { mkdirs() }
        File(out, name).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private fun run(width: Int, height: Int, firstRow: Offset, name: String) {
        val state = state()
        assertTrue(state.tracks.count { it.samples.size >= 2 } >= 2, "demo produced moving tracks")
        val now = Clock.System.now().toEpochMilliseconds()
        val prediction = TrackPredictor.predict(state.tracks.first { it.id == "trk-99" }, now)
        assertTrue(prediction != null && prediction.distanceKm <= 50.0, "westbound group has a predicted destination")
        ImageComposeScene(width, height, Density(1f)) {
            RadarTheme(ThemeMode.Dark) { MapScreen(state, onModeChange = {}, onAddFavorite = { _, _ -> }, bottomInset = 88f.let { androidx.compose.ui.unit.Dp(it) }) }
        }.use { scene ->
            var t = 0L
            fun frames(n: Int): Image {
                var image = scene.render(t)
                repeat(n) { t += 16_000_000L; image = scene.render(t) }
                return image
            }
            fun click(at: Offset) {
                scene.sendPointerEvent(PointerEventType.Press, at)
                scene.sendPointerEvent(PointerEventType.Release, at)
            }
            save(frames(10), "targets-$name-map.png")
            click(Offset(width - 36f, 12f + 48f + 8f + 24f)) // targets button under the camera toggle
            save(frames(30), "targets-$name-list.png")
            click(firstRow)
            save(frames(60), "targets-$name-detail.png")
        }
    }

    @Test
    fun wideLayout() = run(1200, 800, firstRow = Offset(1200 - 12f - 190f, 128f + 12f + 130f), name = "wide")

    @Test
    fun phoneLayout() = run(420, 880, firstRow = Offset(210f, 880 - 88 - 12 - 440 + 150f), name = "phone")
}
