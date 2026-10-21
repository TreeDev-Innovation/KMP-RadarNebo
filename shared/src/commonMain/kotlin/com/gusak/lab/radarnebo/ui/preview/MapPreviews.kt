package com.gusak.lab.radarnebo.ui.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.MapMode
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.tracking.Place
import com.gusak.lab.radarnebo.tracking.TrackPredictor
import com.gusak.lab.radarnebo.ui.MapScreen
import com.gusak.lab.radarnebo.ui.MapScreenStart
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.UiState
import com.gusak.lab.radarnebo.ui.map.MapCamera
import com.gusak.lab.radarnebo.ui.map.MapSelection
import com.gusak.lab.radarnebo.ui.map.UkraineMap
import com.gusak.lab.radarnebo.ui.preview.PreviewData.Targets
import com.gusak.lab.radarnebo.ui.preview.PreviewData.camera
import com.gusak.lab.radarnebo.ui.preview.PreviewData.city

// Previews of the map in every mode and state, built from PreviewData.scenario() so each one always
// shows what its name says. Phone previews are 420 x 860 dp, tablet ones 1100 x 760 dp.

@Composable
private fun Screen(
    state: UiState,
    theme: ThemeMode = state.settings.themeMode,
    start: MapScreenStart = MapScreenStart(),
) {
    RadarTheme(theme) { MapScreen(state, onModeChange = {}, onAddFavorite = { _, _ -> }, start = start) }
}

/** The map alone, without the screen's buttons and cards: for close-ups of what it draws. */
@Composable
private fun MapOnly(state: UiState, camera: MapCamera, selectedTrackId: String? = null, pendingFavorite: GeoPoint? = null) {
    RadarTheme(state.settings.themeMode) {
        UkraineMap(
            camera = camera,
            threats = state.mapThreats,
            alertedOblasts = state.alertedOblasts,
            favorites = state.favorites,
            alertedFavoriteIds = state.proximity.map { it.favorite.id }.toSet(),
            proximityRadiusKm = state.settings.proximityRadiusKm,
            modifier = Modifier.fillMaxSize(),
            selectedTrackId = selectedTrackId,
            predictionPlaces = state.favorites.map { Place(it.name, it.point, isFavorite = true) } +
                TrackPredictor.cityPlaces,
            tracks = state.tracks,
            clockOffsetMs = state.clockOffsetMs,
            pendingFavorite = pendingFavorite,
        )
    }
}

/** Around Kyiv, Chernihiv and Poltava oblasts, where the scenario's targets fly. */
private val targetsArea = camera(GeoPoint(50.45, 31.6), 3.2f)

// --- Map screen ---------------------------------------------------------------------------------------

@Preview(name = "Map · overview · dark", widthDp = 420, heightDp = 860)
@Composable
private fun MapOverviewDarkPreview() = Screen(PreviewData.scenario())

@Preview(name = "Map · overview · light", widthDp = 420, heightDp = 860)
@Composable
private fun MapOverviewLightPreview() = Screen(PreviewData.scenario(ThemeMode.Light))

@Preview(name = "Map · overview · tablet", widthDp = 1100, heightDp = 760)
@Composable
private fun MapOverviewTabletPreview() = Screen(PreviewData.scenario())

@Preview(name = "Map · drones only", widthDp = 420, heightDp = 860)
@Composable
private fun MapDronesOnlyPreview() = Screen(PreviewData.scenario(dronesOnly = true))

@Preview(name = "Map · quiet, no alerts", widthDp = 420, heightDp = 860)
@Composable
private fun MapQuietPreview() = Screen(PreviewData.quiet())

@Preview(name = "Map · connection error", widthDp = 420, heightDp = 860)
@Composable
private fun MapErrorPreview() = Screen(PreviewData.quiet().copy(error = "Upstream rate limit hit (429)", lastUpdated = null))

@Preview(name = "Map · every threat type", widthDp = 420, heightDp = 860)
@Composable
private fun MapEveryThreatTypePreview() = Screen(PreviewData.scenario(), start = MapScreenStart(camera = camera(GeoPoint(49.4, 32.6), 1.9f)))

// --- Cities -------------------------------------------------------------------------------------------

@Preview(name = "Cities · zoomed out, dots", widthDp = 420, heightDp = 860)
@Composable
private fun CitiesDotsPreview() = Screen(PreviewData.quiet(favorites = emptyList()))

@Preview(name = "Cities · Kyiv, names", widthDp = 420, heightDp = 860)
@Composable
private fun CitiesKyivPreview() = Screen(PreviewData.quiet(favorites = emptyList()), start = MapScreenStart(camera = camera(city("Kyiv"), 5f)))

@Preview(name = "Cities · Donbas, dense names", widthDp = 420, heightDp = 860)
@Composable
private fun CitiesDonbasPreview() =
    Screen(PreviewData.quiet(favorites = emptyList()), start = MapScreenStart(camera = camera(GeoPoint(48.3, 38.2), 4.5f)))

@Preview(name = "Cities · Lviv, names · light", widthDp = 420, heightDp = 860)
@Composable
private fun CitiesLvivLightPreview() =
    Screen(PreviewData.quiet(ThemeMode.Light, favorites = emptyList()), start = MapScreenStart(camera = camera(city("Lviv"), 4f)))

@Preview(name = "Cities · names clear of a favorite", widthDp = 420, heightDp = 860)
@Composable
private fun CitiesWithFavoritePreview() = Screen(
    PreviewData.quiet(favorites = listOf(FavoriteLocation("b", "Bakhmach", 51.18, 32.83))),
    start = MapScreenStart(camera = camera(GeoPoint(51.1, 32.9), 5f)),
)

// --- Target states on the map ---------------------------------------------------------------------------

@Preview(name = "Targets on map · every state", widthDp = 420, heightDp = 860)
@Composable
private fun TargetsEveryStatePreview() = MapOnly(PreviewData.scenario(), targetsArea)

@Preview(name = "Targets on map · every state · light", widthDp = 420, heightDp = 860)
@Composable
private fun TargetsEveryStateLightPreview() = MapOnly(PreviewData.scenario(ThemeMode.Light), targetsArea)

@Preview(name = "Targets on map · Shahed selected, others dimmed", widthDp = 420, heightDp = 860)
@Composable
private fun TargetSelectedOnMapPreview() = MapOnly(PreviewData.scenario(), targetsArea, selectedTrackId = Targets.SHAHED)

@Preview(name = "Targets on map · cruise missile selected", widthDp = 420, heightDp = 860)
@Composable
private fun MissileSelectedOnMapPreview() = MapOnly(PreviewData.scenario(), camera(GeoPoint(50.3, 29.6), 4f), selectedTrackId = Targets.MISSILE)

@Preview(name = "Map · Shahed selected", widthDp = 420, heightDp = 860)
@Composable
private fun MapShahedSelectedPreview() = Screen(PreviewData.scenario(), start = MapScreenStart(selectedTrackId = Targets.SHAHED))

@Preview(name = "Map · Shahed selected · tablet", widthDp = 1100, heightDp = 760)
@Composable
private fun MapShahedSelectedTabletPreview() = Screen(PreviewData.scenario(), start = MapScreenStart(selectedTrackId = Targets.SHAHED))

@Preview(name = "Map · cruise missile selected · tablet", widthDp = 1100, heightDp = 760)
@Composable
private fun MapMissileSelectedTabletPreview() = Screen(PreviewData.scenario(), start = MapScreenStart(selectedTrackId = Targets.MISSILE))

@Preview(name = "Map · targets list open", widthDp = 420, heightDp = 860)
@Composable
private fun MapTargetsListPreview() = Screen(PreviewData.scenario(), start = MapScreenStart(targetsOpen = true))

// --- Cards and controls ---------------------------------------------------------------------------------

@Preview(name = "Map · threat card · Kinzhal", widthDp = 420, heightDp = 860)
@Composable
private fun ThreatCardPreview() {
    val state = PreviewData.scenario()
    Screen(state, start = MapScreenStart(selection = MapSelection.Threat(state.threats.first { it.id.startsWith("ballistic") })))
}

@Preview(name = "Map · threat card · region-wide, yellow", widthDp = 420, heightDp = 860)
@Composable
private fun RegionWideThreatCardPreview() {
    val state = PreviewData.scenario(ThemeMode.Light)
    Screen(state, start = MapScreenStart(selection = MapSelection.Threat(state.threats.first { it.id.startsWith("drones-wide") })))
}

@Preview(name = "Map · favorite card · threat nearby", widthDp = 420, heightDp = 860)
@Composable
private fun FavoriteAlertCardPreview() {
    val state = PreviewData.scenario()
    Screen(state, start = MapScreenStart(selection = MapSelection.Favorite(state.favorites.first { it.id == "dacha" })))
}

@Preview(name = "Map · favorite card · clear", widthDp = 420, heightDp = 860)
@Composable
private fun FavoriteClearCardPreview() {
    val state = PreviewData.quiet()
    Screen(state, start = MapScreenStart(selection = MapSelection.Favorite(state.favorites.first())))
}

@Preview(name = "Map · long-press, add favorite", widthDp = 420, heightDp = 860)
@Composable
private fun AddFavoritePinPreview() = Screen(PreviewData.scenario(), start = MapScreenStart(pendingFavorite = GeoPoint(48.86, 30.36)))

@Preview(name = "Map · warning radius control", widthDp = 420, heightDp = 860)
@Composable
private fun RadiusControlPreview() = Screen(PreviewData.scenario(radiusKm = 100), start = MapScreenStart(radiusOpen = true))

@Preview(name = "Map · pending favorite pin, close-up", widthDp = 420, heightDp = 600)
@Composable
private fun PendingPinCloseUpPreview() =
    MapOnly(PreviewData.quiet(), camera(GeoPoint(48.86, 30.36), 4f), pendingFavorite = GeoPoint(48.86, 30.36))

// --- Cinematic mode -------------------------------------------------------------------------------------

@Preview(name = "Cinematic · threat near a favorite", widthDp = 420, heightDp = 860)
@Composable
private fun CinematicThreatPreview() = Screen(PreviewData.scenario(mapMode = MapMode.Cinematic))

@Preview(name = "Cinematic · threat near a favorite · tablet", widthDp = 1100, heightDp = 760)
@Composable
private fun CinematicThreatTabletPreview() = Screen(PreviewData.scenario(mapMode = MapMode.Cinematic))

@Preview(name = "Cinematic · patrolling a clear favorite", widthDp = 420, heightDp = 860)
@Composable
private fun CinematicPatrolPreview() = Screen(PreviewData.quiet(mapMode = MapMode.Cinematic))

@Preview(name = "Cinematic · overview, nothing to show", widthDp = 420, heightDp = 860)
@Composable
private fun CinematicOverviewPreview() = Screen(PreviewData.quiet(mapMode = MapMode.Cinematic, favorites = emptyList()))

@Preview(name = "Cinematic · light theme setting", widthDp = 420, heightDp = 860)
@Composable
private fun CinematicLightPreview() = Screen(PreviewData.scenario(ThemeMode.Light, mapMode = MapMode.Cinematic))
