package com.gusak.lab.radarnebo.server.domain.alert

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import kotlin.time.Instant

/** Administrative level an alert is declared for, as alerts.in.ua reports it. */
enum class LocationType(val apiValue: String) {
    Oblast("oblast"),
    Raion("raion"),
    City("city"),
    Hromada("hromada"),
    Unknown("unknown");

    companion object {
        fun from(value: String?): LocationType = entries.firstOrNull { it.apiValue == value } ?: Unknown
    }
}

enum class AlertType(val apiValue: String) {
    AirRaid("air_raid"),
    ArtilleryShelling("artillery_shelling"),
    UrbanFights("urban_fights"),
    Chemical("chemical"),
    Nuclear("nuclear");

    companion object {
        fun from(value: String?): AlertType = entries.firstOrNull { it.apiValue == value } ?: AirRaid
    }
}

/**
 * A place alerts are declared for, identified by its alerts.in.ua UID. Oblasts, Kyiv city and Sevastopol
 * have UIDs 3-31; raions, hromadas and cities have larger ones.
 */
data class AlertLocation(
    val uid: String,
    val title: String,
    val titleEn: String?,
    val type: LocationType,
    val oblastUid: Int?,
    val oblastTitle: String,
    val raionTitle: String? = null,
) {
    /** True for an oblast, or a city that is a region of its own (Kyiv, Sevastopol). */
    val isRegion: Boolean get() = oblastUid != null && uid == oblastUid.toString()
}

/** One threat an alert warns about. An alert can carry several, e.g. drones and cruise missiles at once. */
data class ThreatSignal(
    val type: ThreatType,
    val level: ThreatLevel,
    val startedAt: Instant,
    /** The warning's original wording, which often names the weapon. */
    val sourceMessage: String?,
)

/**
 * An alert declared for one [location]: active from [startedAt] until [finishedAt] (null while active).
 * [id] is the record id and stays the same for the alert's whole life.
 */
data class AirAlert(
    val id: Long,
    val location: AlertLocation,
    val type: AlertType = AlertType.AirRaid,
    /** Null when the source does not grade the alert. */
    val level: ThreatLevel? = ThreatLevel.Red,
    val startedAt: Instant,
    val updatedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val notes: String? = null,
    val threats: List<ThreatSignal> = emptyList(),
) {
    val isActive: Boolean get() = finishedAt == null

    fun finish(at: Instant): AirAlert = if (isActive) copy(finishedAt = at, updatedAt = at) else this

    /** True when the alert is for the location [uid] or, if [uid] is a region, for any place in it. */
    fun concerns(uid: String): Boolean =
        location.uid == uid || (isRegionUid(uid) && location.oblastUid?.toString() == uid)

    /** True when the alert was active at some point at or after [since]. */
    fun activeSince(since: Instant): Boolean = finishedAt?.let { it >= since } ?: true

    companion object {
        /** Oblasts, Kyiv and Sevastopol: the UIDs whose history includes the places inside them. */
        fun isRegionUid(uid: String): Boolean = uid.toIntOrNull()?.let { it in REGION_UIDS } == true

        private val REGION_UIDS = 3..31
    }
}
