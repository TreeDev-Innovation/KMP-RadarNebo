package com.gusak.lab.radarnebo.server.infrastructure.simulation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlightTest {
    private fun flightFrom(originUk: String): Flight {
        val site = StrikeGeography.droneSites.first { it.originUk == originUk }
        return FlightFactory(Random(0)).flight(
            id = "T1",
            munition = Munition.Shahed,
            waypoints = listOf(site.point, StrikeGeography.point("Kharkiv")),
            departAt = 0,
            origin = site,
            target = "Kharkiv",
        )
    }

    @Test
    fun warningsUseZiBeforeASibilantAndAConsonant() {
        val message = flightFrom("Смоленської області").message
        assertTrue("зі Смоленської" in message, message)
    }

    @Test
    fun warningsUseZOtherwise() {
        val message = flightFrom("Курської області").message
        assertTrue("з Курської" in message, message)
    }

    @Test
    fun theTargetOblastIsTheTargetSettlements() {
        assertEquals(SimulatedLocations.settlement("Kharkiv").city.oblastUid, flightFrom("Курської області").targetOblastUid)
    }
}
