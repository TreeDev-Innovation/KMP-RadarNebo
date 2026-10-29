package com.gusak.lab.radarnebo.domain.usecase

import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ProximityHit
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.TrackInterpolator
import kotlin.time.Clock

/** What the screens show, derived from the raw alerts and tracks under the current settings. */
data class ThreatPicture(
    /** All threats after the drones-only filter, priority sorted. */
    val threats: List<ThreatMarker>,
    /** Threats actually drawn on the map, capped by [AppSettings.maxMapMarkers]. */
    val mapThreats: List<ThreatMarker>,
    val proximity: List<ProximityHit>,
    /** Oblast UID to whether the alert covers the whole oblast (true) or only part of it (false). */
    val alertedOblasts: Map<Int, Boolean>,
    /**
     * Tracks the settings show: active or seen within the timeline window, only drones while "drones only" is on,
     * and only kinds the target filter allows.
     */
    val tracks: List<DroneTrack>,
    /** [tracks] before the target filter, for the Targets list to count and offer what is hidden. */
    val unfilteredTracks: List<DroneTrack> = tracks,
)

/** Turns alerts and tracks into markers, warnings for favorites and oblast shading. Pure, no state. */
class BuildThreatPictureUseCase {
    operator fun invoke(
        alerts: List<Alert>,
        tracks: List<DroneTrack>,
        settings: AppSettings,
        favorites: List<FavoriteLocation>,
        /** Server time, for the speeds that tell drone types apart. */
        now: Long = Clock.System.now().toEpochMilliseconds(),
    ): ThreatPicture {
        val threats = ThreatMapper.toMarkers(alerts, settings.dronesOnly)
        val since = now - settings.targetWindowMinutes * 60_000L
        val shownTracks = tracks
            // The server's verdict is as of its last poll; a target can go stale before the next one.
            .map { if (it.active && !TrackInterpolator.isActive(it, now)) it.copy(active = false) else it }
            .filter { (it.active || it.lastSeen >= since) && (!settings.dronesOnly || it.type == ThreatType.Drones) }
        return ThreatPicture(
            threats = threats,
            mapThreats = ThreatMapper.capForMap(threats, settings.maxMapMarkers),
            proximity = ThreatMapper.proximity(favorites, threats, settings.proximityRadiusKm),
            alertedOblasts = alertedOblasts(alerts),
            tracks = shownTracks.filter { settings.targetFilter.allows(it, now) },
            unfilteredTracks = shownTracks,
        )
    }

    private fun alertedOblasts(alerts: List<Alert>): Map<Int, Boolean> {
        val result = mutableMapOf<Int, Boolean>()
        alerts.filter { it.alertType == "air_raid" || it.threats.isNotEmpty() }.forEach { alert ->
            val uid = ThreatMapper.oblastUid(alert) ?: return@forEach
            result[uid] = (result[uid] ?: false) || alert.locationType == "oblast"
        }
        return result
    }
}
