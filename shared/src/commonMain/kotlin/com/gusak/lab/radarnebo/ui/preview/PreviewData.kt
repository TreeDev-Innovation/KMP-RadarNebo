package com.gusak.lab.radarnebo.ui.preview

import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.DroneSimulator
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.MapMode
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.tracking.DroneTracker
import com.gusak.lab.radarnebo.ui.UiState
import kotlin.time.Clock

/** Sample data for Compose previews: the drone simulation at the current time, with history. */
internal object PreviewData {
    val favorites = listOf(
        FavoriteLocation("1", "Home", 50.4501, 30.5234),
        FavoriteLocation("2", "Kharkiv", 49.9935, 36.2304),
        FavoriteLocation("3", "Parents", 49.5883, 34.5514),
    )

    fun state(theme: ThemeMode = ThemeMode.Dark, mapMode: MapMode = MapMode.Static): UiState {
        val now = Clock.System.now().toEpochMilliseconds()
        val tracker = DroneTracker()
        DroneSimulator.warmUp(tracker, now)
        val alerts = DroneSimulator.alertsAt(now)
        tracker.update(ThreatMapper.toMarkers(alerts, dronesOnly = true), now)
        val threats = ThreatMapper.toMarkers(alerts, dronesOnly = true)
        return UiState(
            settings = AppSettings(themeMode = theme, mapMode = mapMode),
            favorites = favorites,
            threats = threats,
            mapThreats = threats,
            proximity = ThreatMapper.proximity(favorites, threats, 50),
            alertedOblasts = alerts.mapNotNull { a -> a.locationOblastUid?.toIntOrNull()?.let { it to (a.locationType == "oblast") } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, wide) -> wide.any { it } },
            tracks = tracker.snapshot(),
            lastUpdated = Clock.System.now(),
        )
    }

    /** The most interesting track to preview: active, with the longest history. */
    fun busiestTrackId(state: UiState): String? =
        state.tracks.filter { it.active }.maxByOrNull { it.samples.size }?.id
}
