package com.gusak.lab.radarnebo.di

import com.gusak.lab.radarnebo.SharedPrefsStorage
import com.gusak.lab.radarnebo.data.KeyValueStorage
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Android storage: SharedPreferences. Needs `androidContext(...)` in the Koin setup. */
val androidModule = module {
    single<KeyValueStorage> { SharedPrefsStorage(androidContext()) }
}
