package com.gusak.lab.radarnebo.di

import com.gusak.lab.radarnebo.FileStorage
import com.gusak.lab.radarnebo.data.KeyValueStorage
import com.gusak.lab.radarnebo.data.ServerDefaults
import com.gusak.lab.radarnebo.ui.map.MapTiles
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.Logger
import org.koin.dsl.module
import java.io.File

/**
 * Desktop storage: a properties file, `~/.radarnebo/settings.properties` unless [settingsFile] is given; the server on
 * this machine, unless [serverDefaults] says otherwise. With [httpLogger], requests to the server and its responses are
 * logged there.
 */
fun desktopModule(
    settingsFile: File? = null,
    httpLogger: Logger? = null,
    serverDefaults: ServerDefaults = ServerDefaults(),
) = module {
    single<KeyValueStorage> { settingsFile?.let(::FileStorage) ?: FileStorage() }
    single { serverDefaults }
    single { MapTiles(HttpClient()) }
    if (httpLogger != null) single<Logger> { httpLogger }
}
