package com.gusak.lab.radarnebo.server.contract

import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract.toDomain
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
}
