package com.gusak.lab.radarnebo.di

import com.gusak.lab.radarnebo.data.AlertsApi
import com.gusak.lab.radarnebo.data.AlertsRepository
import com.gusak.lab.radarnebo.data.FavoritesRepository
import com.gusak.lab.radarnebo.data.InMemoryStorage
import com.gusak.lab.radarnebo.data.KeyValueStorage
import com.gusak.lab.radarnebo.data.SettingsRepository
import com.gusak.lab.radarnebo.domain.usecase.AddFavoriteUseCase
import com.gusak.lab.radarnebo.domain.usecase.BuildThreatPictureUseCase
import com.gusak.lab.radarnebo.domain.usecase.RefreshAlertsUseCase
import com.gusak.lab.radarnebo.domain.usecase.RemoveFavoriteUseCase
import com.gusak.lab.radarnebo.domain.usecase.UpdateSettingsUseCase
import com.gusak.lab.radarnebo.ui.AppViewModel
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.koinApplication
import org.koin.dsl.module

// Modules are built by functions, not held in vals: Koin keeps singleton instances inside the module
// object, so two containers (an app and a preview, or two tests) sharing one would share repositories.

/** Network client and repositories. They hold state (caches, the on-device tracker), so one each. */
fun dataModule() = module {
    single { AlertsApi() }
    singleOf(::SettingsRepository)
    singleOf(::FavoritesRepository)
    single { AlertsRepository(get()) }
}

/** Use cases are stateless; a new one per injection. */
fun domainModule() = module {
    factoryOf(::RefreshAlertsUseCase)
    factoryOf(::BuildThreatPictureUseCase)
    factoryOf(::UpdateSettingsUseCase)
    factory { AddFavoriteUseCase(get()) }
    factoryOf(::RemoveFavoriteUseCase)
}

fun viewModelModule() = module {
    viewModelOf(::AppViewModel)
}

/**
 * Everything the app needs. [platformModule] provides what only the platform can: the
 * [KeyValueStorage] (SharedPreferences, a properties file, NSUserDefaults).
 */
fun appModules(platformModule: Module): List<Module> = listOf(platformModule, dataModule(), domainModule(), viewModelModule())

/** Starts the app-wide Koin container. Call once, from the platform entry point. */
fun initKoin(platformModule: Module, config: KoinAppDeclaration = {}): KoinApplication = startKoin {
    config()
    modules(appModules(platformModule))
}

/** Keeps everything in memory; for previews and tests. */
fun inMemoryStorageModule(storage: KeyValueStorage = InMemoryStorage()) = module {
    single<KeyValueStorage> { storage }
}

/**
 * A container of its own, not the app-wide one: previews and tests use it with
 * `KoinIsolatedContext` so they never touch real storage or each other.
 */
fun isolatedKoin(storage: KeyValueStorage = InMemoryStorage()): KoinApplication =
    koinApplication { modules(appModules(inMemoryStorageModule(storage))) }
