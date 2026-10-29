package com.gusak.lab.radarnebo.server.infrastructure.simulation

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertRegistryTest {
    private val odesa = SimulatedLocations.region(SimulatedLocations.ODESA_UID)
    private val mykolaiv = SimulatedLocations.region(SimulatedLocations.MYKOLAIV_UID)

    private fun demand(type: ThreatType, level: ThreatLevel = ThreatLevel.Red) = ThreatDemand(type, level, "test")

    @Test
    fun alertsAreNumberedFromTheFirstIdInTheOrderTheyOpen() {
        val registry = AlertRegistry(firstId = 500)
        registry.update(mapOf(odesa to listOf(demand(ThreatType.Drones))), t = 0)
        val alerts = registry.update(
            mapOf(odesa to listOf(demand(ThreatType.Drones)), mykolaiv to listOf(demand(ThreatType.Drones))),
            t = MINUTE_MS,
        )
        assertEquals(mapOf(odesa.uid to 500L, mykolaiv.uid to 501L), alerts.associate { it.location.uid to it.id })
        assertEquals(500L, registry.alertId(odesa.uid))
    }

    @Test
    fun aThreatOnlyEscalates() {
        val registry = AlertRegistry(firstId = 1)
        registry.update(mapOf(odesa to listOf(demand(ThreatType.Drones, ThreatLevel.Red))), t = 0)
        val alert = registry.update(mapOf(odesa to listOf(demand(ThreatType.Drones, ThreatLevel.Yellow))), t = MINUTE_MS).single()
        assertEquals(ThreatLevel.Red, alert.threats.single().level)
        assertEquals(ThreatLevel.Red, alert.level)
    }

    @Test
    fun threatsClearAWhileAfterTheirLastDemandAndTheAlertWithTheLast() {
        val registry = AlertRegistry(firstId = 1)
        registry.update(mapOf(odesa to listOf(demand(ThreatType.Drones), demand(ThreatType.CruiseMissiles))), t = 0)
        fun threatsAt(t: Long) = registry.update(emptyMap(), t).flatMap { alert -> alert.threats.map { it.type } }.toSet()

        assertEquals(setOf(ThreatType.Drones, ThreatType.CruiseMissiles), threatsAt(2 * MINUTE_MS))
        assertEquals(setOf(ThreatType.Drones), threatsAt(2 * MINUTE_MS + 1), "cruise missiles clear after 2 minutes")
        assertEquals(setOf(ThreatType.Drones), threatsAt(3 * MINUTE_MS), "drones still stand 3 minutes on")
        assertTrue(threatsAt(3 * MINUTE_MS + 1).isEmpty(), "drones clear after 3 minutes, and the alert with them")
        assertNull(registry.alertId(odesa.uid))
    }
}
