package com.gusak.lab.radarnebo.server.contract

import com.gusak.lab.radarnebo.data.ActiveAlertsResponse
import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.AlertsMeta
import com.gusak.lab.radarnebo.data.Threat
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AlertLocation
import com.gusak.lab.radarnebo.server.domain.alert.AlertType
import com.gusak.lab.radarnebo.server.domain.alert.LocationType
import com.gusak.lab.radarnebo.server.domain.alert.ThreatSignal
import kotlinx.serialization.json.Json
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * The alerts.in.ua JSON format (https://devs.alerts.in.ua), which the server both reads from upstream and
 * serves to its own clients, and how it maps to the domain model.
 */
object AlertsInUaContract {
    /** Lenient reading, and writing that keeps `null`s as alerts.in.ua does (`"finished_at": null`). */
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = true
    }

    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** alerts.in.ua timestamps: ISO-8601 UTC with milliseconds, `2022-04-04T16:45:39.000Z`. */
    fun format(instant: Instant): String = timestamp.format(instant.toJavaInstant())

    fun parse(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun decodeActive(raw: String): List<Alert> = json.decodeFromString(ActiveAlertsResponse.serializer(), raw).alerts

    private val metaTimestamp = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss Z").withZone(ZoneOffset.UTC)

    /** With [changedAt], adds alerts.in.ua's `meta` block, saying when the data last changed. */
    fun encode(alerts: List<AirAlert>, changedAt: Instant? = null): String = json.encodeToString(
        ActiveAlertsResponse.serializer(),
        ActiveAlertsResponse(
            alerts = alerts.map { it.toDto() },
            meta = changedAt?.let { AlertsMeta(lastUpdatedAt = metaTimestamp.format(it.toJavaInstant())) },
        ),
    )

    fun AirAlert.toDto(): Alert = Alert(
        id = id,
        locationTitle = location.title,
        locationTitleEn = location.titleEn,
        locationType = location.type.apiValue,
        startedAt = format(startedAt),
        finishedAt = finishedAt?.let(::format),
        updatedAt = (updatedAt ?: startedAt).let(::format),
        alertType = type.apiValue,
        locationUid = location.uid,
        locationOblast = location.oblastTitle,
        locationOblastUid = location.sourceOblastUid ?: location.oblastUid,
        locationRaion = location.raionTitle,
        notes = notes,
        country = country,
        alertLevel = level?.apiValue,
        threats = threats.map { threat ->
            Threat(
                threatType = threat.type.apiValue,
                level = threat.level.apiValue,
                startedAt = format(threat.startedAt),
                sourceMessage = threat.sourceMessage,
            )
        },
    )

    /** [receivedAt] stands in for timestamps the source left out. */
    fun Alert.toDomain(receivedAt: Instant): AirAlert {
        val started = parse(startedAt) ?: receivedAt
        val level = alertLevel?.let(ThreatLevel::from)
        val oblastUid = ThreatMapper.oblastUid(this)
        return AirAlert(
            id = id,
            location = AlertLocation(
                uid = locationUid.ifBlank { "alert-$id" },
                title = locationTitle,
                titleEn = locationTitleEn,
                type = LocationType.from(locationType),
                oblastUid = oblastUid,
                oblastTitle = locationOblast,
                raionTitle = locationRaion,
                sourceOblastUid = locationOblastUid.takeIf { it != oblastUid },
            ),
            type = AlertType.from(alertType),
            level = level,
            startedAt = started,
            updatedAt = parse(updatedAt),
            finishedAt = parse(finishedAt),
            notes = notes,
            country = country,
            threats = threats.map { threat ->
                ThreatSignal(
                    type = ThreatType.from(threat.threatType),
                    level = ThreatLevel.from(threat.level ?: alertLevel),
                    startedAt = parse(threat.startedAt) ?: started,
                    sourceMessage = threat.sourceMessage,
                )
            },
        )
    }

    private val ThreatLevel.apiValue get() = name.lowercase()
}
