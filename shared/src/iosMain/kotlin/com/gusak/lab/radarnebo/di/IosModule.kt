package com.gusak.lab.radarnebo.di

import com.gusak.lab.radarnebo.UserDefaultsStorage
import com.gusak.lab.radarnebo.data.KeyValueStorage
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/** iOS storage: NSUserDefaults. */
val iosModule = module {
    single<KeyValueStorage> { UserDefaultsStorage() }
}

/** Starts Koin on first use; the Swift app only creates view controllers, so there's no earlier hook. */
fun initKoinIfNeeded() {
    if (KoinPlatform.getKoinOrNull() == null) initKoin(iosModule)
}
