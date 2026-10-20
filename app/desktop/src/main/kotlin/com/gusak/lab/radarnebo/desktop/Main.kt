package com.gusak.lab.radarnebo.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.gusak.lab.radarnebo.App
import com.gusak.lab.radarnebo.FileStorage
import com.gusak.lab.radarnebo.ui.map.LocalPinchZoom
import kotlinx.coroutines.flow.MutableSharedFlow

fun main() {
    // Compose Desktop jumps a whole step per mouse-wheel notch unless smooth scrolling is enabled;
    // this animates wheel scrolls in lists (Drones, Favorites, Settings). Must be set before any UI.
    System.setProperty("compose.scrolling.smooth.enabled", "true")
    // -Dradarnebo.settings=/path/file.properties overrides the default ~/.radarnebo/settings.properties.
    val storage = System.getProperty("radarnebo.settings")?.let { FileStorage(java.io.File(it)) } ?: FileStorage()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "RadarNebo",
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
                App(storage)
            }
        }
    }
}
