package com.gusak.lab.radarnebo.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.gusak.lab.radarnebo.App
import com.gusak.lab.radarnebo.data.SettingsRepository
import com.gusak.lab.radarnebo.debug.TrafficLog
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.applyAppIdentity
import com.gusak.lab.radarnebo.ui.debug.RequestInspectorScreen
import com.gusak.lab.radarnebo.di.desktopModule
import com.gusak.lab.radarnebo.di.initKoin
import com.gusak.lab.radarnebo.ui.map.LocalPinchZoom
import io.ktor.client.plugins.logging.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import java.io.File

fun main() {
    val icon = applyAppIdentity("RadarNebo", "radarnebo.png")
    // Compose Desktop jumps a whole step per mouse-wheel notch unless smooth scrolling is enabled;
    // this animates wheel scrolls in lists (Drones, Favorites, Settings). Must be set before any UI.
    System.setProperty("compose.scrolling.smooth.enabled", "true")
    // -Dradarnebo.settings=/path/file.properties overrides the default ~/.radarnebo/settings.properties.
    // LOG_HTTP=true logs every request to the server and its JSON response (see logback.xml).
    val httpLogger = if (System.getenv("LOG_HTTP").toBoolean()) {
        val log = LoggerFactory.getLogger("http")
        object : Logger {
            override fun log(message: String) = log.info(message)
        }
    } else {
        null
    }
    val koin = initKoin(desktopModule(System.getProperty("radarnebo.settings")?.let(::File), httpLogger)).koin
    val traffic = koin.get<TrafficLog>()
    val settings = koin.get<SettingsRepository>()
    application {
        var requestsOpen by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = ::exitApplication,
            title = "RadarNebo",
            icon = icon,
            state = rememberWindowState(width = 1100.dp, height = 820.dp),
        ) {
            val pinch = remember { MutableSharedFlow<Float>(extraBufferCapacity = 64) }
            DisposableEffect(window) {
                MacTrackpad.installPinch(window.rootPane) { magnification ->
                    pinch.tryEmit((1.0 + magnification).toFloat().coerceIn(0.5f, 2f))
                }
                onDispose { }
            }
            CompositionLocalProvider(LocalPinchZoom provides pinch) {
                App(onOpenRequests = { requestsOpen = true })
            }
        }
        if (requestsOpen) {
            Window(
                onCloseRequest = { requestsOpen = false },
                title = "RadarNebo · Requests",
                icon = icon,
                state = rememberWindowState(width = 1100.dp, height = 760.dp),
            ) {
                val exchanges by traffic.exchanges.collectAsState()
                val appSettings by settings.settings.collectAsState()
                RadarTheme(appSettings.themeMode, appSettings.colorPalette) {
                    RequestInspectorScreen(exchanges, onClear = traffic::clear)
                }
            }
        }
    }
}
