package com.gusak.lab.radarnebo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.remember
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
import com.gusak.lab.radarnebo.data.MapMode
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.ui.preview.AppPreviewHost
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.runtime.CompositionLocalProvider
import com.gusak.lab.radarnebo.ui.map.LocalMapTiles
import com.gusak.lab.radarnebo.ui.map.MapTiles
import com.gusak.lab.radarnebo.ui.AppViewModel
import com.gusak.lab.radarnebo.ui.FavoritesScreen
import com.gusak.lab.radarnebo.ui.GlassDialog
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
 * The app's root. Its [AppViewModel] comes from Koin, so the platform entry point must start Koin
 * first (`initKoin`), or wrap this in `KoinIsolatedContext` as previews and tests do.
 *
 * @param onThemeApplied called with the chosen mode and whether the resolved theme is dark, so the
 * platform host can match system chrome (status bar icons, window appearance).
 * @param onOpenRequests opens the request inspector (a window on desktop, an activity on Android); null hides
 * the buttons for it.
 */
@Composable
fun App(
    onThemeApplied: (mode: ThemeMode, dark: Boolean) -> Unit = { _, _ -> },
    onOpenRequests: (() -> Unit)? = null,
) {
    val vm = koinViewModel<AppViewModel>()
    // Provided by the platform modules only, so tests and previews never download map tiles.
    val mapTiles = getKoin().getOrNull<MapTiles>()
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Map) }

    RadarTheme(state.settings.themeMode, state.settings.colorPalette, onThemeApplied) {
        CompositionLocalProvider(LocalMapTiles provides mapTiles) {
            val backdrop = rememberGlassBackdrop()
            // Screens run edge to edge underneath the floating bar and pad their scrollable content instead.
            val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bottomInset = GlassNavBarDefaults.ContentInset + systemBottom
            // The cinematic map is a clean full-screen shot: the bar slides away and the map takes its space.
            val navBarVisible = !(tab == Tab.Map && state.settings.mapMode == MapMode.Cinematic)
            val mapBottomInset by animateDpAsState(if (navBarVisible) bottomInset else systemBottom)
            val topAndSides = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))

            Box(Modifier.fillMaxSize()) {
                // Surface (not a plain background) so screens inherit the theme's content colour.
                Surface(
                    Modifier.fillMaxSize().glassBackdropSource(backdrop),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // The map stays underneath: Favorites and Settings open as glass dialogs over it.
                    MapScreen(
                        state,
                        onModeChange = vm::setMapMode,
                        onAddFavorite = vm::addFavorite,
                        onRadiusChange = { km -> vm.updateSettings { it.copy(proximityRadiusKm = km) } },
                        onTargetFilterChange = { filter -> vm.updateSettings { it.copy(targetFilter = filter) } },
                        onOpenRequests = onOpenRequests,
                        onReconnect = vm::reconnect,
                        bottomInset = mapBottomInset,
                    )
                }

                val closeDialog = { tab = Tab.Map }
                GlassDialog(
                    visible = tab == Tab.Favorites,
                    title = "Favorite places",
                    subtitle = "Threats within ${state.settings.proximityRadiusKm} km, or region-wide threats in the same oblast, are flagged.",
                    onDismiss = closeDialog,
                    backdrop = backdrop,
                    modifier = topAndSides,
                    bottomInset = bottomInset,
                ) {
                    FavoritesScreen(state, onAdd = vm::addFavorite, onRemove = vm::removeFavorite)
                }
                GlassDialog(
                    visible = tab == Tab.Settings,
                    title = "Settings",
                    onDismiss = closeDialog,
                    backdrop = backdrop,
                    modifier = topAndSides,
                    bottomInset = bottomInset,
                ) {
                    SettingsScreen(state.settings, onChange = vm::updateSettings, onOpenRequests = onOpenRequests)
                }

                AnimatedVisibility(
                    visible = navBarVisible,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = GlassNavBarDefaults.Margin),
                ) {
                    GlassNavigationBar(
                        items = Tab.entries.map { item ->
                            GlassNavItem(item.label, item.glyph, badge = if (item == Tab.Favorites) state.proximity.size else 0)
                        },
                        selectedIndex = tab.ordinal,
                        onSelect = { tab = Tab.entries[it] },
                        backdrop = backdrop,
                    )
                }
            }
        }
    }
}

@Preview(name = "App · phone", widthDp = 420, heightDp = 880)
@Composable
private fun AppPreview() = AppPreviewHost(ThemeMode.Dark)

@Preview(name = "App · phone · cinematic", widthDp = 420, heightDp = 880)
@Composable
private fun AppCinematicPreview() = AppPreviewHost(ThemeMode.Dark, mapMode = MapMode.Cinematic, map = true)
