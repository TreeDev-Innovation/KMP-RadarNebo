package com.gusak.lab.radarnebo

import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.Threat
import com.gusak.lab.radarnebo.domain.ThreatMapper
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreatMapperMarkerIdTest {
    /** The server's simulator hides markers by [ThreatMapper.markerId], so it must be the id toMarkers gives. */
    @Test
    fun toMarkersUsesMarkerId() {
        val alert = Alert(
            id = 7, locationTitle = "Харківська область", locationType = "oblast",
            locationUid = "22", locationOblast = "Харківська область", locationOblastUid = 22,
            threats = listOf(Threat(threatType = "drones", level = "red")),
        )
        val marker = ThreatMapper.toMarkers(listOf(alert), dronesOnly = false).single()
        assertEquals(ThreatMapper.markerId(7, "drones"), marker.id)
        assertEquals("7:drones", marker.id)
    }
}
