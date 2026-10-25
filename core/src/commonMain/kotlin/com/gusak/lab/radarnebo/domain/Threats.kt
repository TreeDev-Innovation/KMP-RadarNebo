package com.gusak.lab.radarnebo.domain

import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import kotlin.time.Instant

/** A single threat placed on the map. One API alert can carry several threats. */
data class ThreatMarker(
    val id: String,
    val type: ThreatType,
    val level: ThreatLevel,
    val title: String,
    val oblastUid: Int?,
    val oblastName: String,
    val locationType: String,
    val point: GeoPoint,
    val startedAt: Instant?,
    val message: String?,
) {
    /** Oblast-wide threats cover the whole region rather than a specific spot. */
    val isRegionWide get() = locationType == "oblast"
}

data class ProximityHit(
    val favorite: FavoriteLocation,
    val threat: ThreatMarker,
    val distanceKm: Double,
    /** True when the favorite lies inside an oblast that has an oblast-wide threat. */
    val insideRegion: Boolean,
)

object ThreatMapper {

    fun toMarkers(alerts: List<Alert>, dronesOnly: Boolean): List<ThreatMarker> = alerts
        .flatMap { alert ->
            val point = locate(alert) ?: return@flatMap emptyList()
            val oblastUid = oblastUid(alert)
            alert.threats
                .filter { !dronesOnly || it.threatType == ThreatType.Drones.apiValue }
                .map { threat ->
                    ThreatMarker(
                        id = "${alert.id}:${threat.threatType}",
                        type = ThreatType.from(threat.threatType),
                        level = ThreatLevel.from(threat.level ?: alert.alertLevel),
                        title = alert.locationTitleEn?.takeIf { it.isNotBlank() } ?: alert.locationTitle,
                        oblastUid = oblastUid,
                        oblastName = oblastUid?.let { Regions.oblast(it)?.nameEn } ?: alert.locationOblast,
                        locationType = alert.locationType,
                        point = point,
                        startedAt = parseInstant(threat.startedAt ?: alert.startedAt),
                        message = threat.sourceMessage ?: alert.notes?.takeIf { it.isNotBlank() },
                    )
                }
        }
        .sortedWith(compareBy<ThreatMarker> { it.level }.thenByDescending { it.startedAt })

    /** Limits how many markers the map renders. Input is priority sorted, so red and newest threats win. */
    fun capForMap(markers: List<ThreatMarker>, max: Int): List<ThreatMarker> = markers.take(max)

    /**
     * The alert's oblast. alerts.in.ua's `location_oblast_uid` repeats the raion's or hromada's own UID rather than
     * the oblast's, so the oblast name decides; the UIDs count only when they are an oblast's.
     */
    fun oblastUid(alert: Alert): Int? = Regions.oblastByTitle(alert.locationOblast)?.uid
        ?: listOfNotNull(alert.locationOblastUid, alert.locationUid.toIntOrNull().takeIf { alert.locationType == "oblast" })
            .firstOrNull { Regions.oblast(it) != null }

    fun locate(alert: Alert): GeoPoint? {
        val oblastUid = oblastUid(alert)
        if (alert.locationType == "oblast" || oblastUid == Regions.KYIV_CITY_UID || oblastUid == Regions.SEVASTOPOL_UID) {
            return oblastUid?.let(Regions::center)
        }
        // Raion names often share a city's stem ("Харківський район"), so only cities/hromadas snap to a city.
        if (alert.locationType == "city" || alert.locationType == "hromada") {
            Regions.cityForTitle(alert.locationTitle, oblastUid)?.let { return it.point }
        }
        return oblastUid?.let { GeoMath.scatterInOblast(it, alert.locationUid.ifBlank { alert.id.toString() }) }
    }

    fun proximity(
        favorites: List<FavoriteLocation>,
        markers: List<ThreatMarker>,
        radiusKm: Int,
    ): List<ProximityHit> = favorites.mapNotNull { favorite ->
        val favoriteOblast = Regions.oblastAt(favorite.point)
        markers
            .map { marker ->
                val inside = marker.isRegionWide && marker.oblastUid != null && marker.oblastUid == favoriteOblast
                ProximityHit(favorite, marker, GeoMath.distanceKm(favorite.point, marker.point), inside)
            }
            .filter { it.insideRegion || it.distanceKm <= radiusKm }
            .minWithOrNull(compareBy<ProximityHit> { it.threat.level }.thenBy { it.distanceKm })
    }.sortedWith(compareBy<ProximityHit> { it.threat.level }.thenBy { it.distanceKm })

    private fun parseInstant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
