package com.gusak.lab.radarnebo.console

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.gusak.lab.radarnebo.data.ServerDefaults
import com.gusak.lab.radarnebo.di.desktopModule
import com.gusak.lab.radarnebo.di.initKoin
import com.gusak.lab.radarnebo.server.SingleInstance
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.applyAppIdentity
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/** `./gradlew :mission-control:ui:run`: the server in a window, started right away. */
fun main() {
    val icon = applyAppIdentity("RadarNebo Server", "radarnebo-server.png")
    // Before anything logs, so the Logs tab has the server's whole output.
    val logs = LogCapture().apply { install() }
    val server = ConsoleServer(consoleConfig())
    // The embedded client app: its own settings file, so it starts out pointed at this server rather than whatever
    // the desktop app was last set to.
    initKoin(
        desktopModule(
            settingsFile = File(System.getProperty("user.home"), ".radarnebo/mission-control-client.properties"),
            serverDefaults = ServerDefaults("http://localhost:${server.defaultPort}"),
        ),
    )
    // One server per machine: a console started later takes over from this one.
    SingleInstance.acquire {
        runBlocking { server.stop() }
        exitProcess(0)
    }
    application {
        val windowState = rememberWindowState(width = 1320.dp, height = 900.dp)
        Window(
            onCloseRequest = {
                runBlocking { server.stop() }
                exitApplication()
            },
            title = "RadarNebo · Server",
            icon = icon,
            state = windowState,
        ) {
            LaunchedEffect(Unit) { server.start(server.defaultPort) }
            RadarTheme { ConsoleScreen(server, logs) }
        }
    }
}
