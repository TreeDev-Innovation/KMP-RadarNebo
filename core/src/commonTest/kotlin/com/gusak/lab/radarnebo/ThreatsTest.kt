package com.gusak.lab.radarnebo

import com.gusak.lab.radarnebo.data.RadarNeboApi
import com.gusak.lab.radarnebo.data.ServerEndpoint
import com.gusak.lab.radarnebo.data.ActiveAlertsResponse
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.FetchResult
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThreatsTest {

    private val sample = """
        {"alerts":[
          {"id":10,"location_title":"Харківська область","location_title_en":"Kharkiv Oblast","location_type":"oblast",
           "started_at":"2026-10-19T16:45:39.000Z","finished_at":null,"updated_at":"2026-10-19T16:50:00.000Z",
           "alert_type":"air_raid","location_uid":"22","location_oblast":"Харківська область","location_oblast_uid":"22",
           "notes":"","alert_level":"red",
           "threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T16:45:39.681Z","source_message":"Ударні БпЛА"}]},
          {"id":11,"location_title":"Броварський район","location_type":"raion","alert_type":"air_raid",
           "location_uid":"1100","location_oblast":"Київська область","location_oblast_uid":"14","alert_level":"yellow",
           "threats":[{"threat_type":"drones","level":"yellow"},{"threat_type":"ballistic_missiles","level":"red"}]},
          {"id":12,"location_title":"Львівська область","location_type":"oblast","alert_type":"air_raid",
           "location_uid":"27","location_oblast":"Львівська область","location_oblast_uid":"27","unknown_field":1}
        ]}
    """.trimIndent()

    private fun alerts() = RadarNeboApi.json.decodeFromString(ActiveAlertsResponse.serializer(), sample).alerts

    @Test
    fun parsesAlertsAndFiltersDrones() {
        val drones = ThreatMapper.toMarkers(alerts(), dronesOnly = true)
        assertEquals(2, drones.size)
        assertTrue(drones.all { it.type == ThreatType.Drones })
        assertEquals(ThreatLevel.Red, drones.first().level, "red threats sort first")
        assertEquals(3, ThreatMapper.toMarkers(alerts(), dronesOnly = false).size)
    }

    @Test
    fun capsMapMarkers() {
        val all = ThreatMapper.toMarkers(alerts(), dronesOnly = false)
        assertEquals(1, ThreatMapper.capForMap(all, 1).size)
    }

    @Test
    fun raionThreatIsPlacedInsideItsOblast() {
        val marker = ThreatMapper.toMarkers(alerts(), dronesOnly = true).first { it.oblastUid == 14 }
        assertEquals(14, Regions.oblastAt(marker.point))
    }

    @Test
    fun oblastLookupFindsMajorCities() {
        assertEquals(22, Regions.oblastAt(GeoPoint(49.9935, 36.2304)))
        assertEquals(27, Regions.oblastAt(GeoPoint(49.8397, 24.0297)))
        assertEquals(Regions.KYIV_CITY_UID, Regions.oblastAt(GeoPoint(50.45, 30.52)))
        assertEquals(18, Regions.oblastAt(GeoPoint(46.4825, 30.7233)))
    }

    @Test
    fun detectsThreatsNearFavorites() {
        val markers = ThreatMapper.toMarkers(alerts(), dronesOnly = true)
        val kharkiv = FavoriteLocation("1", "Kharkiv", 49.9935, 36.2304)
        val lviv = FavoriteLocation("2", "Lviv", 49.8397, 24.0297)
        val hits = ThreatMapper.proximity(listOf(kharkiv, lviv), markers, radiusKm = 30)
        assertEquals(listOf("Kharkiv"), hits.map { it.favorite.name })
        assertTrue(hits.single().insideRegion, "oblast-wide threat covers the city")
    }

    @Test
    fun haversineDistance() {
        val d = GeoMath.distanceKm(GeoPoint(50.4501, 30.5234), GeoPoint(49.8397, 24.0297))
        assertTrue(d in 460.0..480.0, "Kyiv-Lviv is ~468 km, got $d")
    }

    @Test
    fun apiSendsBearerTokenAndHandlesStatuses() = runTest {
        var authHeader: String? = null
        var call = 0
        val engine = MockEngine { request ->
            authHeader = request.headers[HttpHeaders.Authorization]
            when (call++) {
                0 -> respond(sample, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                1 -> respond("", HttpStatusCode.NotModified)
                else -> respond("""{"message":"x"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val api = RadarNeboApi(HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(RadarNeboApi.json) }
        })
        val first = api.activeAlerts(ServerEndpoint(RadarNeboApi.DEFAULT_SERVER_URL, "secret"))
        assertIs<FetchResult.Fresh>(first)
        assertEquals(3, first.alerts.size)
        assertEquals("Bearer secret", authHeader)
        assertIs<FetchResult.NotModified>(api.activeAlerts(ServerEndpoint(RadarNeboApi.DEFAULT_SERVER_URL, "secret")))
        assertIs<FetchResult.Failure>(api.activeAlerts(ServerEndpoint(RadarNeboApi.DEFAULT_SERVER_URL, "secret")))
    }
}

class LocateTest {
    @Test
    fun hromadaSnapsToCityButRaionDoesNot() {
        val hromada = com.gusak.lab.radarnebo.data.Alert(
            id = 1, locationTitle = "м. Харків та Харківська територіальна громада", locationType = "hromada",
            locationUid = "1293", locationOblastUid = 22,
        )
        val raion = hromada.copy(id = 2, locationTitle = "Харківський район", locationType = "raion", locationUid = "1290")
        assertEquals(GeoPoint(49.9935, 36.2304), ThreatMapper.locate(hromada))
        val raionPoint = ThreatMapper.locate(raion)!!
        assertTrue(raionPoint != GeoPoint(49.9935, 36.2304))
        assertEquals(22, Regions.oblastAt(raionPoint))
    }

    @Test
    fun theOblastComesFromItsNameBecauseAlertsInUaRepeatsTheLocationUid() {
        // As alerts.in.ua sends it: location_oblast_uid is the raion's own UID, a number.
        val response = RadarNeboApi.json.decodeFromString(ActiveAlertsResponse.serializer(), """
            {"alerts":[{"id":1,"location_title":"Нікопольський район","location_type":"raion","alert_type":"air_raid",
              "location_uid":"48","location_oblast":"Дніпропетровська область","location_oblast_uid":48,
              "threats":[{"threat_type":"drones","level":"yellow"}]}]}
        """.trimIndent())
        val alert = response.alerts.single()
        assertEquals(48, alert.locationOblastUid)
        assertEquals(9, ThreatMapper.oblastUid(alert))
        assertEquals(9, Regions.oblastAt(ThreatMapper.locate(alert)!!))
        assertEquals(9, ThreatMapper.toMarkers(response.alerts, dronesOnly = false).single().oblastUid)
    }
}
