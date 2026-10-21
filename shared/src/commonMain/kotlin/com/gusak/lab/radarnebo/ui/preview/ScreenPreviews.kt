package com.gusak.lab.radarnebo.ui.preview

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.gusak.lab.radarnebo.App
import com.gusak.lab.radarnebo.data.AlertsApi
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.InMemoryStorage
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.di.isolatedKoin
import com.gusak.lab.radarnebo.ui.FavoritesScreen
import com.gusak.lab.radarnebo.ui.RadarTheme
import com.gusak.lab.radarnebo.ui.SettingsScreen
import kotlinx.serialization.builtins.ListSerializer
import org.koin.compose.KoinIsolatedContext

/**
 * The whole app as it starts (demo data), with a Koin container and ViewModel store of its own, so
 * previews never touch real settings and work in the IDE, which has no app-wide Koin.
 */
@Composable
internal fun AppPreviewHost(theme: ThemeMode, favorites: List<FavoriteLocation> = PreviewData.favorites) {
    val koin = remember(theme) {
        val storage = InMemoryStorage().apply {
            putString("theme_mode", theme.name)
            putString("favorites", AlertsApi.json.encodeToString(ListSerializer(FavoriteLocation.serializer()), favorites))
        }
        isolatedKoin(storage)
    }
    val owner = remember(theme) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(koin, owner) {
        onDispose {
            owner.viewModelStore.clear()
            koin.close()
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        KoinIsolatedContext(koin) { App() }
    }
}

@Preview(name = "App · phone · dark", widthDp = 420, heightDp = 880)
@Composable
private fun AppPhoneDarkPreview() = AppPreviewHost(ThemeMode.Dark)

@Preview(name = "App · phone · light", widthDp = 420, heightDp = 880)
@Composable
private fun AppPhoneLightPreview() = AppPreviewHost(ThemeMode.Light)

@Preview(name = "App · tablet", widthDp = 1100, heightDp = 760)
@Composable
private fun AppTabletPreview() = AppPreviewHost(ThemeMode.Dark)

@Preview(name = "Favorites · threats near favorites", widthDp = 420, heightDp = 860)
@Composable
private fun FavoritesWithWarningsPreview() {
    RadarTheme(ThemeMode.Dark) { Surface { FavoritesScreen(PreviewData.scenario(), onAdd = { _, _ -> }, onRemove = {}) } }
}

@Preview(name = "Favorites · all clear · light", widthDp = 420, heightDp = 860)
@Composable
private fun FavoritesClearPreview() {
    RadarTheme(ThemeMode.Light) { Surface { FavoritesScreen(PreviewData.quiet(ThemeMode.Light), onAdd = { _, _ -> }, onRemove = {}) } }
}

@Preview(name = "Settings · every section", widthDp = 420, heightDp = 2300)
@Composable
private fun SettingsFullPreview() {
    RadarTheme(ThemeMode.Dark) { Surface { SettingsScreen(PreviewData.scenario().settings, onChange = {}) } }
}

@Preview(name = "Settings · every section · light", widthDp = 420, heightDp = 2300)
@Composable
private fun SettingsFullLightPreview() {
    RadarTheme(ThemeMode.Light) { Surface { SettingsScreen(PreviewData.scenario(ThemeMode.Light).settings, onChange = {}) } }
}
