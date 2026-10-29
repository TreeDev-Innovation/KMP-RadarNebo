package com.gusak.lab.radarnebo.di

import com.gusak.lab.radarnebo.SharedPrefsStorage
import com.gusak.lab.radarnebo.data.KeyValueStorage
import com.gusak.lab.radarnebo.data.ServerDefaults
import com.gusak.lab.radarnebo.ui.map.MapTiles
import io.ktor.client.HttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Android storage: SharedPreferences. Needs `androidContext(...)` in the Koin setup. The server defaults to
 * the deployed one.
 */
val androidModule = module {
    single<KeyValueStorage> { SharedPrefsStorage(androidContext()) }
    single { ServerDefaults() }
    single { MapTiles(HttpClient()) }
}
