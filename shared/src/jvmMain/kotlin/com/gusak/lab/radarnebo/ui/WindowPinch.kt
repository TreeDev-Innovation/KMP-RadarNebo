package com.gusak.lab.radarnebo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.swing.JComponent

/**
 * Trackpad pinch-to-zoom factors for gestures over [component] (a window's root pane), for
 * [com.gusak.lab.radarnebo.ui.map.LocalPinchZoom]: Compose Desktop doesn't deliver pinches itself.
 * macOS only, and the JVM needs `--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED`.
 */
@Composable
fun rememberWindowPinch(component: JComponent): Flow<Float> {
    val pinch = remember { MutableSharedFlow<Float>(extraBufferCapacity = 64) }
    DisposableEffect(component) {
        MacTrackpad.installPinch(component) { magnification ->
            pinch.tryEmit((1.0 + magnification).toFloat().coerceIn(0.5f, 2f))
        }
        onDispose { }
    }
    return pinch
}
