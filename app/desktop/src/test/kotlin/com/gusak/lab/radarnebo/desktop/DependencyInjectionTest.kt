package com.gusak.lab.radarnebo.desktop

import com.gusak.lab.radarnebo.data.TargetFilter
import com.gusak.lab.radarnebo.tracking.TargetKind
import com.gusak.lab.radarnebo.data.AlertsRepository
import com.gusak.lab.radarnebo.data.AlertsUpdate
import com.gusak.lab.radarnebo.data.FavoritesRepository
import com.gusak.lab.radarnebo.data.InMemoryStorage
import com.gusak.lab.radarnebo.data.KeyValueStorage
import com.gusak.lab.radarnebo.data.RadarNeboApi
import com.gusak.lab.radarnebo.data.SettingsRepository
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.di.appModules
import com.gusak.lab.radarnebo.di.inMemoryStorageModule
import com.gusak.lab.radarnebo.di.isolatedKoin
import com.gusak.lab.radarnebo.domain.usecase.AddFavoriteUseCase
import com.gusak.lab.radarnebo.domain.usecase.BuildThreatPictureUseCase
import com.gusak.lab.radarnebo.domain.usecase.RefreshAlertsUseCase
import com.gusak.lab.radarnebo.domain.usecase.RemoveFavoriteUseCase
import com.gusak.lab.radarnebo.domain.usecase.SettingsUpdate
import com.gusak.lab.radarnebo.domain.usecase.UpdateSettingsUseCase
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.ui.AppViewModel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.verify.verify
import org.koin.core.annotation.KoinExperimentalAPI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DependencyInjectionTest {
    private val storage = InMemoryStorage()
    private val requested = mutableListOf<String>()

    /** Stands in for the RadarNebo server: a drone alert near Kyiv, a tracked drone group and a cruise missile. */
    private val server = MockEngine { request ->
        requested += request.url.encodedPath
        val body = when (request.url.encodedPath) {
            RadarNeboApi.ACTIVE_ALERTS_PATH -> ALERTS
            RadarNeboApi.TRACKS_PATH -> TRACKS
            RadarNeboApi.HEALTH_PATH -> """{"status":"degraded","source":"AlertsInUa","lastError":"Upstream returned 500","extra":1}"""
            else -> error("unexpected ${request.url}")
        }
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private val koin: KoinApplication = koinApplication {
        allowOverride(true)
        modules(
            appModules(inMemoryStorageModule(storage)) + module {
                single { RadarNeboApi(RadarNeboApi.client(server)) }
            }
        )
    }

    @AfterTest
    fun close() = koin.close()

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun everyDefinitionHasItsDependencies() {
        // Static check of every constructor's parameters across all modules together.
        module { includes(appModules(inMemoryStorageModule())) }.verify()
    }

    @Test
    fun repositoriesAreSingletonsAndUseCasesAreFresh() {
        val k = koin.koin
        assertSame(k.get<SettingsRepository>(), k.get<SettingsRepository>())
        assertSame(k.get<FavoritesRepository>(), k.get<FavoritesRepository>())
        assertSame(k.get<AlertsRepository>(), k.get<AlertsRepository>())
        assertSame(k.get<RadarNeboApi>(), k.get<RadarNeboApi>())
        assertSame(storage, k.get<KeyValueStorage>())
        assertNotSame(k.get<AddFavoriteUseCase>(), k.get<AddFavoriteUseCase>())
        listOf(
            k.get<RefreshAlertsUseCase>(), k.get<BuildThreatPictureUseCase>(), k.get<UpdateSettingsUseCase>(),
            k.get<RemoveFavoriteUseCase>(), k.get<AppViewModel>(),
        )
    }

    @Test
    fun useCasesWorkThroughTheInjectedRepositories() {
        val k = koin.koin
        // The app only talks to its server: alerts, the server's tracks, then how the server is doing.
        val update = runBlocking { k.get<RefreshAlertsUseCase>()() }
        assertTrue(update is AlertsUpdate.Updated && update.snapshot.alerts.isNotEmpty() && update.snapshot.simulated)
        assertEquals(listOf(RadarNeboApi.ACTIVE_ALERTS_PATH, RadarNeboApi.TRACKS_PATH, RadarNeboApi.HEALTH_PATH), requested)
        val health = update.snapshot.health!!
        assertTrue(!health.isOk && health.lastError == "Upstream returned 500", "a failing data source shows as degraded")
        assertEquals(RadarNeboApi.DEFAULT_SERVER_URL, k.get<SettingsRepository>().settings.value.serverUrl)

        val favorite = k.get<AddFavoriteUseCase>()("  ", GeoPoint(50.4501, 30.5234))
        assertEquals("Point 50.45, 30.52", favorite.name)
        assertTrue("Point 50.45" in storage.getString("favorites")!!, "saved to the injected storage")
        k.get<RemoveFavoriteUseCase>()(favorite.id)
        assertTrue(k.get<FavoritesRepository>().favorites.value.isEmpty())

        val update2 = k.get<UpdateSettingsUseCase>()
        assertEquals(SettingsUpdate(sourceChanged = false, trackingChanged = false), update2 { it.copy(proximityRadiusKm = 80) })
        assertEquals("80", storage.getString("proximity_radius_km"))
        assertEquals(SettingsUpdate(sourceChanged = false, trackingChanged = true), update2 { it.copy(smartMapping = false) })
        assertEquals("false", storage.getString("smart_mapping"))
        assertTrue(update2 { it.copy(accessKey = "key") }.sourceChanged, "a new access key changes the data source")
        assertTrue(update2 { it.copy(serverUrl = "https://radar.example") }.sourceChanged)
        assertEquals("https://radar.example", storage.getString("server_url"))
    }

    @Test
    fun threatPictureFiltersTracksBySettings() {
        val k = koin.koin
        val snapshot = (runBlocking { k.get<RefreshAlertsUseCase>()() } as AlertsUpdate.Updated).snapshot
        val build = k.get<BuildThreatPictureUseCase>()
        val settings = k.get<SettingsRepository>().settings.value
        val dronesOnly = build(snapshot.alerts, snapshot.tracks, settings.copy(dronesOnly = true), emptyList())
        val all = build(snapshot.alerts, snapshot.tracks, settings.copy(dronesOnly = false), emptyList())
        assertTrue(dronesOnly.tracks.all { it.type == ThreatType.Drones })
        assertTrue(all.tracks.size > dronesOnly.tracks.size, "the cruise missile shows only with every threat class")
        assertTrue(all.threats.size >= dronesOnly.threats.size)

        val missiles = all.tracks.count { it.type != ThreatType.Drones }
        val noProbable = build(snapshot.alerts, snapshot.tracks, settings.copy(dronesOnly = false, targetFilter = TargetFilter(hideProbable = true)), emptyList())
        assertTrue(noProbable.tracks.isEmpty(), "the fixture names no types, so every target is probable")
        assertEquals(all.tracks, noProbable.unfilteredTracks, "the list still sees what the filter hides")
        val noDrones = build(snapshot.alerts, snapshot.tracks, settings.copy(dronesOnly = false, targetFilter = TargetFilter(hiddenKinds = setOf(TargetKind.AttackDrone))), emptyList())
        assertEquals(missiles, noDrones.tracks.size)
        assertEquals(all.threats, noDrones.threats, "alerts are never filtered away")
    }

    @Test
    fun theTargetFilterIsSaved() {
        val storage = InMemoryStorage()
        SettingsRepository(storage).update { it.copy(targetFilter = TargetFilter(setOf(TargetKind.DecoyDrone, TargetKind.GuidedBomb), hideProbable = true)) }
        assertEquals(TargetFilter(setOf(TargetKind.DecoyDrone, TargetKind.GuidedBomb), hideProbable = true), SettingsRepository(storage).settings.value.targetFilter)
    }

    @Test
    fun separateContainersDoNotShareState() {
        val other = koinApplication { modules(appModules(inMemoryStorageModule())) }
        try {
            koin.koin.get<FavoritesRepository>().add(com.gusak.lab.radarnebo.data.FavoriteLocation("x", "X", 50.0, 30.0))
            assertTrue(other.koin.get<FavoritesRepository>().favorites.value.isEmpty())
        } finally {
            other.close()
        }
    }

    @Test
    fun anOldDirectConnectionIsMigratedWithoutKeepingTheToken() {
        val old = InMemoryStorage().apply {
            putString("api_token", "alerts-in-ua-secret")
            putString("api_base_url", "https://api.alerts.in.ua")
        }
        val settings = SettingsRepository(old, com.gusak.lab.radarnebo.data.ServerDefaults("http://10.0.2.2:8080"))
        assertEquals("http://10.0.2.2:8080", settings.settings.value.serverUrl)
        assertEquals("", settings.settings.value.accessKey, "an alerts.in.ua token is not a server key")
        settings.update { it.copy(pollSeconds = 60) }
        assertEquals(null, old.getString("api_token"), "the token is removed from the device")

        val proxy = InMemoryStorage().apply {
            putString("api_token", "client-key")
            putString("api_base_url", "http://radar.local:8080")
        }
        val migrated = SettingsRepository(proxy).settings.value
        assertEquals("http://radar.local:8080" to "client-key", migrated.serverUrl to migrated.accessKey)
    }

    private companion object {
        const val ALERTS = """{"alerts":[{"id":1,"location_title":"Броварська територіальна громада","location_type":"hromada",
            "location_uid":"1100","location_oblast":"Київська область","location_oblast_uid":"14","alert_level":"red",
            "started_at":"2026-10-19T10:00:00.000Z","threats":[{"threat_type":"drones","level":"red","started_at":"2026-10-19T10:00:00.000Z"}]}]}"""

        const val TRACKS = """{"serverTime":1800000000000,"pollIntervalMs":10000,"lastUpstreamFetch":1800000000000,"simulated":true,"tracks":[
            {"id":"S1-D01","type":"Drones","level":"Red","oblastUid":14,"active":true,"firstSeen":1799999000000,"lastSeen":1800000000000,
             "markerIds":["1:drones"],"samples":[{"t":1799999000000,"lat":50.6,"lon":31.2,"title":"Nizhyn"},{"t":1800000000000,"lat":50.51,"lon":30.79,"title":"Brovary"}]},
            {"id":"S1-KH01","type":"CruiseMissiles","level":"Red","oblastUid":10,"active":true,"firstSeen":1799999500000,"lastSeen":1800000000000,
             "markerIds":[],"samples":[{"t":1799999500000,"lat":49.9,"lon":28.6,"title":"Berdychiv"},{"t":1800000000000,"lat":50.25,"lon":28.67,"title":"Zhytomyr"}]}]}"""
    }
}
