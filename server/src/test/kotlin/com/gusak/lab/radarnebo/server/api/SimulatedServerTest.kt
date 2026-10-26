package com.gusak.lab.radarnebo.server.api

import com.gusak.lab.radarnebo.data.ActiveAlertsResponse
import com.gusak.lab.radarnebo.data.RadarNeboApi
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.server.Archives
import com.gusak.lab.radarnebo.server.ServerComponents
import com.gusak.lab.radarnebo.server.config.ClientApiConfig
import com.gusak.lab.radarnebo.server.config.ServerConfig
import com.gusak.lab.radarnebo.server.config.SimulationConfig
import com.gusak.lab.radarnebo.server.infrastructure.simulation.SimulatedAlertsSource
import com.gusak.lab.radarnebo.server.radarServer
import com.gusak.lab.radarnebo.tracking.DroneTracksResponse
import com.gusak.lab.radarnebo.tracking.TrackOutcome
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The server on its simulation, as the app sees it: the same API as with alerts.in.ua behind it. */
class SimulatedServerTest {

    @Test
    fun theSimulationIsServedThroughTheSameApiWithTracksAndArchive() = testApplication {
        val simulation = SimulationConfig()
        val period = simulation.strikePeriod.inWholeMilliseconds
        // Start an hour before a strike, after the warm-up replay of the two hours before that.
        var now = 1_800_000_000_000L / period * period + period - 3_600_000L
        val config = ServerConfig(clients = ClientApiConfig(requestsPerMinute = 100_000, historyRequestsPerMinute = 100_000), simulation = simulation)
        val source = SimulatedAlertsSource(simulation, clock = { now })
        val server = ServerComponents.create(config, clock = { now }, source = source, archives = Archives.inMemory())
        application { radarServer(server, startPoller = false) }

        // Six hours of the feed, polled every minute, as clients see it.
        val threatTypes = mutableSetOf<ThreatType>()
        val trackTypes = mutableSetOf<ThreatType>()
        var mostTracks = 0
        repeat(6 * 60) {
            runBlocking { server.poller.pollOnce() }
            val alerts = RadarNeboApi.json.decodeFromString(ActiveAlertsResponse.serializer(), client.get(RadarNeboApi.ACTIVE_ALERTS_PATH).bodyAsText())
            alerts.alerts.flatMap { it.threats }.mapTo(threatTypes) { ThreatType.from(it.threatType) }
            val tracks = RadarNeboApi.json.decodeFromString(DroneTracksResponse.serializer(), client.get(RadarNeboApi.TRACKS_PATH).bodyAsText())
            assertTrue(tracks.simulated, "clients are told the data is simulated")
            tracks.tracks.mapTo(trackTypes) { it.type }
            mostTracks = maxOf(mostTracks, tracks.tracks.count { it.active })
            now += 60_000L
        }
        assertEquals(ThreatType.entries.toSet() - ThreatType.Unknown, threatTypes)
        assertEquals(setOf(ThreatType.Drones, ThreatType.CruiseMissiles, ThreatType.BallisticMissiles, ThreatType.GuidedBombs), trackTypes)
        assertTrue(mostTracks >= 20, "at the height of the strike $mostTracks targets are tracked")

        // Finished flights are in the archive, with how they ended.
        val archive = RadarNeboApi.json.decodeFromString(TrackHistoryResponse.serializer(), client.get("${RadarNeboApi.TRACK_ARCHIVE_PATH}?limit=1000").bodyAsText())
        assertTrue(archive.simulated)
        val outcomes = archive.tracks.filter { !it.active }.mapNotNull { it.outcome }.toSet()
        assertEquals(TrackOutcome.entries.toSet(), outcomes)
        // And so are finished alerts: Kyiv city is alerted during every strike.
        val kyiv = RadarNeboApi.json.decodeFromString(ActiveAlertsResponse.serializer(), client.get("/v1/regions/31/alerts/day_ago.json").bodyAsText())
        assertTrue(kyiv.alerts.any { it.finishedAt != null }, "Kyiv's finished alerts are archived")

        val health = client.get("/health").bodyAsText()
        assertTrue("\"simulated\":true" in health && "\"upstreamRequestsTotal\":0" in health && "\"source\":\"Simulation\"" in health, health)

        val page = client.get("/").bodyAsText()
        assertTrue("RadarNebo server" in page && "Simulation (not the real situation)" in page, "a status page for browsers")
    }
}
