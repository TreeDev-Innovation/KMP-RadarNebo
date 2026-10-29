package com.gusak.lab.radarnebo.domain

import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.tracking.SamplePrecision
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
    /** How precisely [point] places the threat; null when not known (taken as a raion). */
    val precision: SamplePrecision? = null,
    /** How far from [point] the threat may be, in km, when the area's size is known; else [precision] decides. */
    val errorKm: Double? = null,
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
            val location = locateWithPrecision(alert) ?: return@flatMap emptyList()
            val point = location.point
            val oblastUid = oblastUid(alert)
            alert.threats
                .filter { !dronesOnly || it.threatType == ThreatType.Drones.apiValue }
                .map { threat ->
                    ThreatMarker(
                        id = markerId(alert.id, threat.threatType),
                        type = ThreatType.from(threat.threatType),
                        level = ThreatLevel.from(threat.level ?: alert.alertLevel),
                        title = alert.locationTitleEn?.takeIf { it.isNotBlank() } ?: alert.locationTitle,
                        oblastUid = oblastUid,
                        oblastName = oblastUid?.let { Regions.oblast(it)?.nameEn } ?: alert.locationOblast,
                        locationType = alert.locationType,
                        point = point,
                        startedAt = parseInstant(threat.startedAt ?: alert.startedAt),
                        message = threat.sourceMessage ?: alert.notes?.takeIf { it.isNotBlank() },
                        precision = location.precision,
                        errorKm = location.errorKm,
                    )
                }
        }
        .sortedWith(compareBy<ThreatMarker> { it.level }.thenByDescending { it.startedAt })

    /** The id of the marker for one threat of an alert; the server's simulator uses it to hide markers its targets stand in for. */
    fun markerId(alertId: Long, threatType: String): String = "$alertId:$threatType"

    /** Limits how many markers the map renders. Input is priority sorted, so red and newest threats win. */
    fun capForMap(markers: List<ThreatMarker>, max: Int): List<ThreatMarker> = markers.take(max)

    /**
     * The alert's oblast. alerts.in.ua's `location_oblast_uid` repeats the raion's or hromada's own UID rather than
     * the oblast's, so the oblast name decides; the UIDs count only when they are an oblast's.
     */
    fun oblastUid(alert: Alert): Int? = Regions.oblastByTitle(alert.locationOblast)?.uid
        ?: listOfNotNull(alert.locationOblastUid, alert.locationUid.toIntOrNull().takeIf { alert.locationType == "oblast" })
            .firstOrNull { Regions.oblast(it) != null }

    /** Where to show [alert]; see [locateWithPrecision]. */
    fun locate(alert: Alert): GeoPoint? = locateWithPrecision(alert)?.point

    /** Where an alert is placed, how precisely, and the one-sigma error when the area's size is known. */
    data class MappedLocation(val point: GeoPoint, val precision: SamplePrecision, val errorKm: Double? = null)

    /**
     * Where to show [alert], and how precisely that places it. Oblasts sit at their centre, named cities and
     * hromadas at the city, raions at their centre; any other place without coordinates at a stable pseudo-random
     * spot in its oblast, which says no more than the oblast does.
     */
    fun locateWithPrecision(alert: Alert): MappedLocation? {
        val oblastUid = oblastUid(alert)
        if (oblastUid == Regions.KYIV_CITY_UID || oblastUid == Regions.SEVASTOPOL_UID) {
            return Regions.center(oblastUid)?.let { MappedLocation(it, SamplePrecision.Hromada) }
        }
        if (alert.locationType == "oblast") return oblastUid?.let(Regions::center)?.let { MappedLocation(it, SamplePrecision.Oblast) }
        // Raion names often share a city's stem ("Харківський район"), so only cities/hromadas snap to a city.
        if (alert.locationType == "city" || alert.locationType == "hromada") {
            Regions.cityForTitle(alert.locationTitle, oblastUid)?.let {
                return MappedLocation(it.point, if (alert.locationType == "city") SamplePrecision.City else SamplePrecision.Hromada)
            }
        }
        // Bounding-box radii overstate an area's size; half of one is a fair one-sigma error.
        if (alert.locationType == "hromada") {
            Regions.hromadaForTitle(alert.locationTitle, oblastUid)
                ?.let { return MappedLocation(it.point, SamplePrecision.Hromada, it.radiusKm / 2) }
        }
        if (alert.locationType == "raion") {
            Regions.raionForTitle(alert.locationTitle, oblastUid)
                ?.takeIf { oblastUid == null || it.oblastUid == oblastUid }
                ?.let { return MappedLocation(it.point, SamplePrecision.Raion, it.radiusKm / 2) }
        }
        return oblastUid
            ?.let { GeoMath.scatterInOblast(it, alert.locationUid.ifBlank { alert.id.toString() }) }
            ?.let { MappedLocation(it, SamplePrecision.Oblast) }
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
