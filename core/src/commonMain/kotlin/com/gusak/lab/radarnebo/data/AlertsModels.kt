package com.gusak.lab.radarnebo.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of `GET /v1/alerts/active.json` from https://devs.alerts.in.ua. */
@Serializable
data class ActiveAlertsResponse(
    val alerts: List<Alert> = emptyList(),
)

@Serializable
data class Alert(
    val id: Long,
    @SerialName("location_title") val locationTitle: String = "",
    @SerialName("location_title_en") val locationTitleEn: String? = null,
    @SerialName("location_type") val locationType: String = "unknown",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("alert_type") val alertType: String = "air_raid",
    @SerialName("location_uid") val locationUid: String = "",
    @SerialName("location_oblast") val locationOblast: String = "",
    @SerialName("location_oblast_uid") val locationOblastUid: String? = null,
    @SerialName("location_raion") val locationRaion: String? = null,
    val notes: String? = null,
    @SerialName("alert_level") val alertLevel: String? = null,
    val threats: List<Threat> = emptyList(),
)

@Serializable
data class Threat(
    @SerialName("threat_type") val threatType: String,
    val level: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("source_message") val sourceMessage: String? = null,
)

enum class ThreatType(val apiValue: String, val label: String) {
    Drones("drones", "Drones (Shahed/UAV)"),
    BallisticMissiles("ballistic_missiles", "Ballistic missiles"),
    CruiseMissiles("cruise_missiles", "Cruise missiles"),
    UnspecifiedMissiles("unspecified_missiles", "Missiles"),
    GuidedBombs("guided_aerial_bombs", "Guided aerial bombs"),
    TacticalAviation("tactic_aircraft_activity", "Tactical aviation"),
    StrategicAviation("strategic_aircraft_activity", "Strategic aviation"),
    Mig31k("mig31k_departure", "MiG-31K departure"),
    AirDefense("air_defense", "Air defense active"),
    Unknown("unknown", "Unknown threat");

    companion object {
        fun from(value: String): ThreatType = entries.firstOrNull { it.apiValue == value } ?: Unknown
    }
}

@Serializable
enum class ThreatLevel { Red, Yellow;
    companion object {
        fun from(value: String?): ThreatLevel = if (value.equals("yellow", ignoreCase = true)) Yellow else Red
    }
}
