package com.gusak.lab.radarnebo.console

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.gusak.lab.radarnebo.App
import com.gusak.lab.radarnebo.data.AppSettings

/** How the console shows the client app: not at all, in a pane on the right, or over the whole console below the top bar. */
enum class ClientView(val label: String, val glyph: String) {
    Hidden("Hidden", "▢"),
    Embedded("Embedded", "◨"),
    Fullscreen("Fullscreen", "■");

    val next get() = entries[(ordinal + 1) % entries.size]
}

/**
 * The client's settings pointed at this console's server on [port], with [key] when the server wants one. Applied on
 * every start, so a new port or a saved address from an earlier run can't leave the client calling the wrong server.
 */
fun AppSettings.pointedAt(port: Int, key: String?) = copy(serverUrl = "http://localhost:$port", accessKey = key.orEmpty())

/** Width of the client pane in [ClientView.Embedded]: about a phone's. */
val EmbeddedClientWidth = 440.dp

/** One button for all three [ClientView]s: shows the current one, a click moves to the next. */
@Composable
fun ClientViewToggle(view: ClientView, onChange: (ClientView) -> Unit) {
    OutlinedButton(onClick = { onChange(view.next) }) { Text("${view.glyph}  Client: ${view.label}") }
}

/**
 * The shared client app, talking to this console's server (see Main.kt for its Koin container). Its view model
 * lives in a store of its own, cleared when the pane leaves the screen, so a hidden client stops polling the server.
 */
@Composable
fun ClientPane(modifier: Modifier = Modifier) {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    Box(modifier) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { App() }
    }
}
