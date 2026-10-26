package com.gusak.lab.radarnebo.di

import com.gusak.lab.radarnebo.UserDefaultsStorage
import com.gusak.lab.radarnebo.data.KeyValueStorage
import com.gusak.lab.radarnebo.data.ServerDefaults
import com.gusak.lab.radarnebo.ui.map.MapTiles
import io.ktor.client.HttpClient
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/** iOS storage: NSUserDefaults; the server on this machine, which the simulator reaches as localhost. */
val iosModule = module {
    single<KeyValueStorage> { UserDefaultsStorage() }
    single { ServerDefaults() }
    single { MapTiles(HttpClient()) }
}

/** Starts Koin on first use; the Swift app only creates view controllers, so there's no earlier hook. */
fun initKoinIfNeeded() {
    if (KoinPlatform.getKoinOrNull() == null) initKoin(iosModule)
}
