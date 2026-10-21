package com.gusak.lab.radarnebo.desktop

import com.gusak.lab.radarnebo.data.AlertsApi
import com.gusak.lab.radarnebo.data.AlertsRepository
import com.gusak.lab.radarnebo.data.AlertsUpdate
import com.gusak.lab.radarnebo.data.FavoritesRepository
import com.gusak.lab.radarnebo.data.InMemoryStorage
import com.gusak.lab.radarnebo.data.KeyValueStorage
import com.gusak.lab.radarnebo.data.SettingsRepository
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.di.appModules
import com.gusak.lab.radarnebo.di.inMemoryStorageModule
import com.gusak.lab.radarnebo.di.isolatedKoin
import com.gusak.lab.radarnebo.domain.usecase.AddFavoriteUseCase
import com.gusak.lab.radarnebo.domain.usecase.BuildThreatPictureUseCase
import com.gusak.lab.radarnebo.domain.usecase.RefreshAlertsUseCase
import com.gusak.lab.radarnebo.domain.usecase.RemoveFavoriteUseCase
import com.gusak.lab.radarnebo.domain.usecase.UpdateSettingsUseCase
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.ui.AppViewModel
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
    private val koin: KoinApplication = isolatedKoin(storage)

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
        assertSame(k.get<AlertsApi>(), k.get<AlertsApi>())
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
        // Demo mode (no token): the refresh uses the simulation, never the network.
        val update = runBlocking { k.get<RefreshAlertsUseCase>()() }
        assertTrue(update is AlertsUpdate.Updated && update.snapshot.alerts.isNotEmpty())

        val favorite = k.get<AddFavoriteUseCase>()("  ", GeoPoint(50.4501, 30.5234))
        assertEquals("Point 50.45, 30.52", favorite.name)
        assertTrue("Point 50.45" in storage.getString("favorites")!!, "saved to the injected storage")
        k.get<RemoveFavoriteUseCase>()(favorite.id)
        assertTrue(k.get<FavoritesRepository>().favorites.value.isEmpty())

        val update2 = k.get<UpdateSettingsUseCase>()
        assertEquals(false, update2 { it.copy(proximityRadiusKm = 80) })
        assertEquals("80", storage.getString("proximity_radius_km"))
        assertEquals(true, update2 { it.copy(apiToken = "token") }, "a new token changes the data source")
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
        assertTrue(all.tracks.size >= dronesOnly.tracks.size)
        assertTrue(all.threats.size >= dronesOnly.threats.size)
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
}
