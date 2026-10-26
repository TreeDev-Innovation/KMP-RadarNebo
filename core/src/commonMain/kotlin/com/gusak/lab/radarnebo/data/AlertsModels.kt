package com.gusak.lab.radarnebo.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Response of `GET /v1/alerts/active.json` from https://devs.alerts.in.ua. Properties are declared in
 * alerts.in.ua's key order, and those it leaves out when empty are left out here too, so the server's
 * output reads like the original.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ActiveAlertsResponse(
    val alerts: List<Alert> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val meta: AlertsMeta? = null,
)

@Serializable
data class AlertsMeta(
    /** When the data last changed, as `2026/10/26 08:38:08 +0000`. */
    @SerialName("last_updated_at") val lastUpdatedAt: String,
    val type: String = "full",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Alert(
    val id: Long,
    @SerialName("location_title") val locationTitle: String = "",
    @SerialName("location_type") val locationType: String = "unknown",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("alert_type") val alertType: String = "air_raid",
    @SerialName("location_oblast") val locationOblast: String = "",
    @SerialName("location_uid") val locationUid: String = "",
    val notes: String? = null,
    val country: String? = null,
    @SerialName("alert_level") val alertLevel: String? = null,
    @SerialName("location_title_en") val locationTitleEn: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val threats: List<Threat> = emptyList(),
    @SerialName("location_raion") @EncodeDefault(EncodeDefault.Mode.NEVER) val locationRaion: String? = null,
    /**
     * A number in alerts.in.ua; older RadarNebo servers sent it as a string, which still decodes. Despite the
     * name, alerts.in.ua puts the raion's or hromada's own UID here; [com.gusak.lab.radarnebo.domain.ThreatMapper.oblastUid]
     * finds the oblast.
     */
    @SerialName("location_oblast_uid") @Serializable(with = LenientIntSerializer::class) val locationOblastUid: Int? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Threat(
    @SerialName("threat_type") val threatType: String,
    val level: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("source_message") @EncodeDefault(EncodeDefault.Mode.NEVER) val sourceMessage: String? = null,
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

/** Writes an Int and reads one from a JSON number or a numeric string. */
internal object LenientIntSerializer : KSerializer<Int?> {
    override val descriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }

    override fun deserialize(decoder: Decoder): Int? {
        if (decoder !is JsonDecoder) return decoder.decodeInt()
        val element = decoder.decodeJsonElement() as? JsonPrimitive ?: return null
        return element.intOrNull ?: element.content.trim().toIntOrNull()
    }
}
