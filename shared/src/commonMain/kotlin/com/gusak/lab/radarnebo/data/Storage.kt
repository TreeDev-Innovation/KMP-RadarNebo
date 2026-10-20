package com.gusak.lab.radarnebo.data

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

data class AppSettings(
    val apiToken: String = "",
    val baseUrl: String = AlertsApi.DEFAULT_BASE_URL,
    val mapMode: MapMode = MapMode.Static,
    val themeMode: ThemeMode = ThemeMode.System,
    val maxMapMarkers: Int = DEFAULT_MAX_MARKERS,
    val proximityRadiusKm: Int = 50,
    val dronesOnly: Boolean = true,
    val pollSeconds: Int = 30,
) {
    /** Talking to a RadarNebo proxy rather than alerts.in.ua directly; the proxy may not need a token. */
    val usesProxy get() = baseUrl.trimEnd('/') != AlertsApi.DEFAULT_BASE_URL
    val isDemo get() = apiToken.isBlank() && !usesProxy

    companion object {
        const val DEFAULT_MAX_MARKERS = 60
        val MAX_MARKERS_RANGE = 5..300
        val RADIUS_RANGE = 5..200
        val POLL_RANGE = 15..300
    }
}

class SettingsRepository(private val storage: KeyValueStorage) {

    fun loadSettings() = AppSettings(
        apiToken = storage.getString(K_TOKEN).orEmpty(),
        baseUrl = storage.getString(K_BASE_URL)?.takeIf { it.isNotBlank() } ?: AlertsApi.DEFAULT_BASE_URL,
        mapMode = storage.getString(K_MODE)?.let { runCatching { MapMode.valueOf(it) }.getOrNull() } ?: MapMode.Static,
        themeMode = storage.getString(K_THEME)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
        maxMapMarkers = storage.getString(K_MAX_MARKERS)?.toIntOrNull()
            ?.coerceIn(AppSettings.MAX_MARKERS_RANGE) ?: AppSettings.DEFAULT_MAX_MARKERS,
        proximityRadiusKm = storage.getString(K_RADIUS)?.toIntOrNull()?.coerceIn(AppSettings.RADIUS_RANGE) ?: 50,
        dronesOnly = storage.getString(K_DRONES_ONLY)?.toBooleanStrictOrNull() ?: true,
        pollSeconds = storage.getString(K_POLL)?.toIntOrNull()?.coerceIn(AppSettings.POLL_RANGE) ?: 30,
    )

    fun saveSettings(settings: AppSettings) {
        storage.putString(K_TOKEN, settings.apiToken.trim())
        storage.putString(K_BASE_URL, settings.baseUrl.trim())
        storage.putString(K_MODE, settings.mapMode.name)
        storage.putString(K_THEME, settings.themeMode.name)
        storage.putString(K_MAX_MARKERS, settings.maxMapMarkers.toString())
        storage.putString(K_RADIUS, settings.proximityRadiusKm.toString())
        storage.putString(K_DRONES_ONLY, settings.dronesOnly.toString())
        storage.putString(K_POLL, settings.pollSeconds.toString())
    }

    fun loadFavorites(): List<FavoriteLocation> = storage.getString(K_FAVORITES)?.let {
        runCatching { AlertsApi.json.decodeFromString(favoritesSerializer, it) }.getOrNull()
    } ?: emptyList()

    fun saveFavorites(favorites: List<FavoriteLocation>) {
        storage.putString(K_FAVORITES, AlertsApi.json.encodeToString(favoritesSerializer, favorites))
    }

    private companion object {
        val favoritesSerializer = ListSerializer(FavoriteLocation.serializer())
        const val K_TOKEN = "api_token"
        const val K_BASE_URL = "api_base_url"
        const val K_MODE = "map_mode"
        const val K_THEME = "theme_mode"
        const val K_MAX_MARKERS = "map_max_markers"
        const val K_RADIUS = "proximity_radius_km"
        const val K_DRONES_ONLY = "drones_only"
        const val K_POLL = "poll_seconds"
        const val K_FAVORITES = "favorites"
    }
}
