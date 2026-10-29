package com.gusak.lab.radarnebo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.gusak.lab.radarnebo.tracking.TargetKind
import kotlinx.serialization.builtins.ListSerializer

/** Minimal platform key-value storage (SharedPreferences on Android, NSUserDefaults on iOS). */
interface KeyValueStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class InMemoryStorage : KeyValueStorage {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

enum class MapMode { Static, Cinematic }

enum class ThemeMode { System, Light, Dark }

/** The app's colours; each has a light and a dark variant, picked by [ThemeMode]. */
enum class ColorPalette { Radar, Nord, Dracula, Solarized, Catppuccin, ObsidianPlum, SmokedCopper, BlueHonor }

/** What the map shows under the oblasts: nothing, a map (dark or light with the theme) or satellite imagery. */
enum class MapBackground { None, Streets, Satellite }

data class AppSettings(
    /** The RadarNebo server the app gets everything from. */
    val serverUrl: String = RadarNeboApi.DEFAULT_SERVER_URL,
    /** Only needed when the server requires client keys. */
    val accessKey: String = "",
    val mapMode: MapMode = MapMode.Static,
    val themeMode: ThemeMode = ThemeMode.System,
    val colorPalette: ColorPalette = ColorPalette.Radar,
    val maxMapMarkers: Int = DEFAULT_MAX_MARKERS,
    val proximityRadiusKm: Int = 50,
    val dronesOnly: Boolean = true,
    val pollSeconds: Int = 30,
    val targetFilter: TargetFilter = TargetFilter(),
    val mapBackground: MapBackground = MapBackground.Streets,
) {
    val endpoint get() = ServerEndpoint(serverUrl.trim(), accessKey.trim())

    companion object {
        const val DEFAULT_MAX_MARKERS = 60
        val MAX_MARKERS_RANGE = 5..300
        val RADIUS_RANGE = 5..200
        val POLL_RANGE = 10..300
    }
}

/**
 * Where the app looks for its server, and the key it sends, until the user sets them: client.properties at build time
 * (see [ClientConfig]), else the deployed server without a key, unless a platform overrides it.
 */
data class ServerDefaults(
    val url: String = ClientConfig.SERVER_URL.ifBlank { RadarNeboApi.DEFAULT_SERVER_URL },
    val accessKey: String = ClientConfig.ACCESS_KEY,
)

/** App settings, persisted in [KeyValueStorage] and observable as [settings]. */
class SettingsRepository(private val storage: KeyValueStorage, private val defaults: ServerDefaults = ServerDefaults()) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Applies [transform], saves the result and returns it. */
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val new = transform(_settings.value)
        if (new != _settings.value) {
            save(new)
            _settings.value = new
        }
        return new
    }

    private fun load(): AppSettings {
        // Earlier versions talked to alerts.in.ua directly, or to a proxy given as the base URL with its key as the token.
        val legacyProxyUrl = storage.getString(K_LEGACY_BASE_URL)?.takeIf { it.isNotBlank() && LEGACY_UPSTREAM_HOST !in it }
        val legacyKey = if (legacyProxyUrl != null) storage.getString(K_LEGACY_TOKEN) else null
        return AppSettings(
            serverUrl = storage.getString(K_SERVER_URL)?.takeIf { it.isNotBlank() } ?: legacyProxyUrl ?: defaults.url,
            accessKey = storage.getString(K_ACCESS_KEY)?.takeIf { it.isNotBlank() } ?: legacyKey ?: defaults.accessKey,
            mapMode = storage.getString(K_MODE)?.let { runCatching { MapMode.valueOf(it) }.getOrNull() } ?: MapMode.Static,
            themeMode = storage.getString(K_THEME)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            colorPalette = storage.getString(K_PALETTE)?.let { runCatching { ColorPalette.valueOf(it) }.getOrNull() } ?: ColorPalette.Radar,
            maxMapMarkers = storage.getString(K_MAX_MARKERS)?.toIntOrNull()
                ?.coerceIn(AppSettings.MAX_MARKERS_RANGE) ?: AppSettings.DEFAULT_MAX_MARKERS,
            proximityRadiusKm = storage.getString(K_RADIUS)?.toIntOrNull()?.coerceIn(AppSettings.RADIUS_RANGE) ?: 50,
            dronesOnly = storage.getString(K_DRONES_ONLY)?.toBooleanStrictOrNull() ?: true,
            pollSeconds = storage.getString(K_POLL)?.toIntOrNull()?.coerceIn(AppSettings.POLL_RANGE) ?: 30,
            targetFilter = TargetFilter(
                hiddenKinds = storage.getString(K_HIDDEN_KINDS).orEmpty().split(',')
                    .mapNotNull { name -> TargetKind.entries.firstOrNull { it.name == name } }.toSet(),
                hideProbable = storage.getString(K_HIDE_PROBABLE)?.toBooleanStrictOrNull() ?: false,
            ),
            mapBackground = storage.getString(K_MAP_BACKGROUND)?.let { runCatching { MapBackground.valueOf(it) }.getOrNull() }
                ?: MapBackground.Streets,
        )
    }

    private fun save(settings: AppSettings) {
        storage.putString(K_SERVER_URL, settings.serverUrl.trim())
        storage.putString(K_ACCESS_KEY, settings.accessKey.trim())
        storage.putString(K_MODE, settings.mapMode.name)
        storage.putString(K_THEME, settings.themeMode.name)
        storage.putString(K_PALETTE, settings.colorPalette.name)
        storage.putString(K_MAX_MARKERS, settings.maxMapMarkers.toString())
        storage.putString(K_RADIUS, settings.proximityRadiusKm.toString())
        storage.putString(K_DRONES_ONLY, settings.dronesOnly.toString())
        storage.putString(K_POLL, settings.pollSeconds.toString())
        storage.putString(K_HIDDEN_KINDS, settings.targetFilter.hiddenKinds.joinToString(",") { it.name })
        storage.putString(K_HIDE_PROBABLE, settings.targetFilter.hideProbable.toString())
        storage.putString(K_MAP_BACKGROUND, settings.mapBackground.name)
        // An alerts.in.ua token belongs on the server, never on the device.
        storage.putString(K_LEGACY_TOKEN, null)
        storage.putString(K_LEGACY_BASE_URL, null)
    }

    private companion object {
        const val K_SERVER_URL = "server_url"
        const val K_ACCESS_KEY = "server_access_key"
        const val K_LEGACY_TOKEN = "api_token"
        const val K_LEGACY_BASE_URL = "api_base_url"
        const val LEGACY_UPSTREAM_HOST = "alerts.in.ua"
        const val K_MODE = "map_mode"
        const val K_THEME = "theme_mode"
        const val K_PALETTE = "color_palette"
        const val K_MAX_MARKERS = "map_max_markers"
        const val K_RADIUS = "proximity_radius_km"
        const val K_DRONES_ONLY = "drones_only"
        const val K_POLL = "poll_seconds"
        const val K_HIDDEN_KINDS = "targets_hidden_kinds"
        const val K_HIDE_PROBABLE = "targets_hide_probable"
        const val K_MAP_BACKGROUND = "map_background"
    }
}

/** Favorite locations, persisted in [KeyValueStorage] and observable as [favorites]. */
class FavoritesRepository(private val storage: KeyValueStorage) {
    private val _favorites = MutableStateFlow(load())
    val favorites: StateFlow<List<FavoriteLocation>> = _favorites.asStateFlow()

    fun add(favorite: FavoriteLocation) = save(_favorites.value + favorite)

    fun remove(id: String) = save(_favorites.value.filterNot { it.id == id })

    private fun load(): List<FavoriteLocation> = storage.getString(K_FAVORITES)?.let {
        runCatching { RadarNeboApi.json.decodeFromString(serializer, it) }.getOrNull()
    } ?: emptyList()

    private fun save(favorites: List<FavoriteLocation>) {
        storage.putString(K_FAVORITES, RadarNeboApi.json.encodeToString(serializer, favorites))
        _favorites.value = favorites
    }

    private companion object {
        val serializer = ListSerializer(FavoriteLocation.serializer())
        const val K_FAVORITES = "favorites"
    }
}
