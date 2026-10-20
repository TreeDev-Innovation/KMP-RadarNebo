package com.gusak.lab.radarnebo

import com.gusak.lab.radarnebo.data.InMemoryStorage
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gusak.lab.radarnebo.data.KeyValueStorage
import com.gusak.lab.radarnebo.data.SettingsRepository
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.ui.AppViewModel
import com.gusak.lab.radarnebo.ui.FavoritesScreen
import com.gusak.lab.radarnebo.ui.GlassNavBarDefaults
import com.gusak.lab.radarnebo.ui.GlassNavItem
import com.gusak.lab.radarnebo.ui.GlassNavigationBar
import com.gusak.lab.radarnebo.ui.MapScreen
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.SettingsScreen
import com.gusak.lab.radarnebo.ui.glassBackdropSource
import com.gusak.lab.radarnebo.ui.rememberGlassBackdrop

private enum class Tab(val label: String, val glyph: String) {
    Map("Map", "◎"),
    Favorites("Favorites", "★"),
    Settings("Settings", "⚙"),
}

/**
 * @param onThemeApplied called with the chosen mode and whether the resolved theme is dark, so the
 * platform host can match system chrome (status bar icons, window appearance).
 */
@Composable
fun App(
    storage: KeyValueStorage,
    onThemeApplied: (mode: ThemeMode, dark: Boolean) -> Unit = { _, _ -> },
) {
    val vm = viewModel { AppViewModel(SettingsRepository(storage)) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Map) }

    RadarTheme(state.settings.themeMode, onThemeApplied) {
        val backdrop = rememberGlassBackdrop()
        // Screens run edge to edge underneath the floating bar and pad their scrollable content instead.
        val bottomInset = GlassNavBarDefaults.ContentInset +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val topAndSides = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))

        Box(Modifier.fillMaxSize()) {
            // Surface (not a plain background) so screens inherit the theme's content colour.
            Surface(
                Modifier.fillMaxSize().glassBackdropSource(backdrop),
                color = MaterialTheme.colorScheme.background,
            ) {
                when (tab) {
                    Tab.Map -> MapScreen(
                        state,
                        onModeChange = vm::setMapMode,
                        onAddFavorite = vm::addFavorite,
                        bottomInset = bottomInset,
                    )
                    Tab.Favorites -> Box(topAndSides) {
                        FavoritesScreen(state, onAdd = vm::addFavorite, onRemove = vm::removeFavorite, bottomInset = bottomInset)
                    }
                    Tab.Settings -> Box(topAndSides) {
                        SettingsScreen(state.settings, onChange = vm::updateSettings, bottomInset = bottomInset)
                    }
                }
            }

            GlassNavigationBar(
                items = Tab.entries.map { item ->
                    GlassNavItem(item.label, item.glyph, badge = if (item == Tab.Favorites) state.proximity.size else 0)
                },
                selectedIndex = tab.ordinal,
                onSelect = { tab = Tab.entries[it] },
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = GlassNavBarDefaults.Margin),
            )
        }
    }
}

@Preview(name = "App · phone", widthDp = 420, heightDp = 880)
@Composable
private fun AppPreview() {
    // Previews have no activity/window ViewModel store; provide one so App can create its ViewModel.
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        App(InMemoryStorage())
    }
}
