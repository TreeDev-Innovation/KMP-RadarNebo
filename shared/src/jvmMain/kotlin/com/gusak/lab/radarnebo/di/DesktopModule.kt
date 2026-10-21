package com.gusak.lab.radarnebo.di

import com.gusak.lab.radarnebo.FileStorage
import com.gusak.lab.radarnebo.data.KeyValueStorage
import org.koin.dsl.module
import java.io.File

/** Desktop storage: a properties file, `~/.radarnebo/settings.properties` unless [settingsFile] is given. */
fun desktopModule(settingsFile: File? = null) = module {
    single<KeyValueStorage> { settingsFile?.let(::FileStorage) ?: FileStorage() }
}
