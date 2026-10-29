package com.gusak.lab.radarnebo.console

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.gusak.lab.radarnebo.data.ServerDefaults
import com.gusak.lab.radarnebo.data.SettingsRepository
import com.gusak.lab.radarnebo.di.desktopModule
import com.gusak.lab.radarnebo.di.initKoin
import com.gusak.lab.radarnebo.server.SingleInstance
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.applyAppIdentity
import com.gusak.lab.radarnebo.ui.map.LocalPinchZoom
import com.gusak.lab.radarnebo.ui.rememberWindowPinch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.swing.JOptionPane
import kotlin.system.exitProcess

/** `./gradlew :mission-control:ui:run`: the server in a window, started right away. */
fun main() {
    val icon = applyAppIdentity("RadarNebo Server", "radarnebo-server.png")
    // Before anything logs, so the Logs tab has the server's whole output.
    val logs = LogCapture().apply { install() }
    val config = try {
        consoleConfig()
    } catch (e: IllegalArgumentException) {
        // No window exists yet, so an out-of-range value in .env would otherwise end the console without a word.
        JOptionPane.showMessageDialog(null, e.message, "RadarNebo Server", JOptionPane.ERROR_MESSAGE)
        exitProcess(1)
    }
    val server = ConsoleServer(config)
    // The embedded client app: its own settings file, so it starts out pointed at this server rather than whatever
    // the desktop app was last set to.
    val clientSettings = initKoin(
        desktopModule(
            settingsFile = SingleInstance.defaultDir.resolve("mission-control-client.properties"),
            serverDefaults = ServerDefaults("http://localhost:${server.defaultPort}"),
        ),
    ).koin.get<SettingsRepository>()
    // One server per machine: a console started later takes over from this one.
    SingleInstance.acquire {
        runBlocking { server.stop() }
        exitProcess(0)
    }
    application {
        val windowState = rememberWindowState(width = 1320.dp, height = 900.dp)
        val scope = rememberCoroutineScope()
        Window(
            // Not runBlocking: a start or stop in progress resumes on the UI thread, so blocking that thread while
            // waiting for the server's lock would deadlock. A second close just waits for the lock; stop is a no-op then.
            onCloseRequest = {
                scope.launch {
                    server.stop()
                    exitApplication()
                }
            },
            title = "RadarNebo · Server",
            icon = icon,
            state = windowState,
        ) {
            LaunchedEffect(Unit) { server.start(server.defaultPort) }
            LaunchedEffect(Unit) {
                server.state.filterIsInstance<ServerState.Running>().collect { running ->
                    clientSettings.update { it.pointedAt(running.port, server.clientKey) }
                }
            }
            // Pinches over the window zoom the embedded client's map; nothing else in the console listens for them.
            CompositionLocalProvider(LocalPinchZoom provides rememberWindowPinch(window.rootPane)) {
                RadarTheme { ConsoleScreen(server, logs) }
            }
        }
    }
}
