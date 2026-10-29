package com.gusak.lab.radarnebo.server.contract

import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.Threat
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract.toDomain
import com.gusak.lab.radarnebo.server.domain.alert.AlertType
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

class AlertsInUaContractTest {
    /** A real alerts.in.ua response (2026-10-26): an oblast, a raion, hromadas and a city, with and without threats. */
    private val upstream = javaClass.getResource("/alerts-in-ua-active.json")!!.readText()

    @Test
    fun clientsGetExactlyWhatAlertsInUaSent() {
        val updated = Regex("\"last_updated_at\":\"([^\"]+)\"").find(upstream)!!.groupValues[1]
        val changedAt = ZonedDateTime.parse(updated, DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss Z")).toInstant().toKotlinInstant()
        val received = Instant.parse("2026-10-26T08:38:12Z")

        val served = AlertsInUaContract.encode(AlertsInUaContract.decodeActive(upstream).map { it.toDomain(received) }, changedAt)

        // Byte for byte, key order included; only the disclaimer (an ad for their Python package) is dropped.
        val expected = upstream.replace(Regex(",\"disclaimer\":\"[^\"]*\""), "")
        assertEquals(expected, served)
    }

    @Test
    fun aMissingOrGarbledStartTimeFallsBackToWhenTheAlertWasReceived() {
        val received = Instant.parse("2026-10-26T08:38:12Z")
        assertEquals(received, Alert(id = 1, startedAt = null).toDomain(received).startedAt)
        assertEquals(received, Alert(id = 1, startedAt = "yesterday").toDomain(received).startedAt)
    }

    @Test
    fun aThreatWithoutItsOwnLevelInheritsTheAlertLevel() {
        val alert = Alert(
            id = 1,
            alertLevel = "yellow",
            threats = listOf(Threat(threatType = "drones"), Threat(threatType = "cruise_missiles", level = "red")),
        ).toDomain(Instant.parse("2026-10-26T08:38:12Z"))
        assertEquals(listOf(ThreatLevel.Yellow, ThreatLevel.Red), alert.threats.map { it.level })
    }

    @Test
    fun anAlertWithoutALocationUidGetsOneOfItsOwn() {
        val alert = Alert(id = 42, locationUid = " ").toDomain(Instant.parse("2026-10-26T08:38:12Z"))
        assertEquals("alert-42", alert.location.uid)
    }

    @Test
    fun anUndocumentedAlertTypeIsReadAsAnAirRaid() {
        val alert = Alert(id = 1, alertType = "something_new").toDomain(Instant.parse("2026-10-26T08:38:12Z"))
        assertEquals(AlertType.AirRaid, alert.type)
    }
}
