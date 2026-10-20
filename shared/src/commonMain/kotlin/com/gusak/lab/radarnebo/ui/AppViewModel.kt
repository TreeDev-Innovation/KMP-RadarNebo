package com.gusak.lab.radarnebo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.data.AlertsApi
import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.DroneSimulator
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.FetchResult
import com.gusak.lab.radarnebo.data.MapMode
import com.gusak.lab.radarnebo.data.SettingsRepository
import com.gusak.lab.radarnebo.domain.ProximityHit
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

data class UiState(
    val settings: AppSettings = AppSettings(),
    val favorites: List<FavoriteLocation> = emptyList(),
    /** All threats after the drones-only filter, priority sorted. */
    val threats: List<ThreatMarker> = emptyList(),
    /** Threats actually drawn on the map, capped by [AppSettings.maxMapMarkers]. */
    val mapThreats: List<ThreatMarker> = emptyList(),
    val proximity: List<ProximityHit> = emptyList(),
    /** Oblast UIDs with any active alert, used to shade the map. */
    val alertedOblasts: Map<Int, Boolean> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val lastUpdated: Instant? = null,
    /** Drone position history: from the RadarNebo proxy, or tracked on the device otherwise. */
    val tracks: List<DroneTrack> = emptyList(),
    /** Server clock minus local clock, applied before interpolating track positions. */
    val clockOffsetMs: Long = 0,
)

class AppViewModel(
    private val repository: SettingsRepository,
    private val api: AlertsApi = AlertsApi(),
) : ViewModel() {

    private val _state = MutableStateFlow(
        UiState(settings = repository.loadSettings(), favorites = repository.loadFavorites())
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var alerts: List<Alert> = emptyList()
    private var pollJob: Job? = null
    /** Builds tracks on the device when there is no RadarNebo proxy to provide them. */
    private var localTracker = DroneTracker()

    init {
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val backoff = fetchOnce()
                delay(_state.value.settings.pollSeconds * 1000L * backoff)
            }
        }
    }

    /** Returns a poll interval multiplier: back off after rate-limit or auth errors. */
    private suspend fun fetchOnce(): Int {
        val settings = _state.value.settings
        _state.update { it.copy(loading = true) }
        if (settings.isDemo) {
            val now = Clock.System.now().toEpochMilliseconds()
            if (_state.value.tracks.isEmpty()) DroneSimulator.warmUp(localTracker, now)
            alerts = DroneSimulator.alertsAt(now)
            _state.update { it.copy(loading = false, error = null, lastUpdated = Clock.System.now()) }
            recompute()
            updateTracks(settings)
            return 1
        }
        return when (val result = api.activeAlerts(settings.baseUrl, settings.apiToken)) {
            is FetchResult.Fresh -> {
                alerts = result.alerts
                _state.update { it.copy(loading = false, error = null, lastUpdated = Clock.System.now()) }
                recompute()
                updateTracks(settings)
                1
            }
            FetchResult.NotModified -> {
                _state.update { it.copy(loading = false, error = null, lastUpdated = Clock.System.now()) }
                updateTracks(settings)
                1
            }
            is FetchResult.Failure -> {
                _state.update { it.copy(loading = false, error = result.message) }
                4
            }
        }
    }

    /**
     * Tracks change even when alerts don't (last-seen times, extrapolation), so refresh them on every
     * poll: from the proxy when there is one, otherwise by tracking the alerts on the device.
     */
    private suspend fun updateTracks(settings: AppSettings) {
        if (settings.usesProxy) {
            val response = api.droneTracks(settings.baseUrl, settings.apiToken) ?: return
            val offset = response.serverTime - Clock.System.now().toEpochMilliseconds()
            _state.update { it.copy(tracks = response.tracks, clockOffsetMs = offset) }
        } else {
            localTracker.update(ThreatMapper.toMarkers(alerts, dronesOnly = true), Clock.System.now().toEpochMilliseconds())
            _state.update { it.copy(tracks = localTracker.snapshot(), clockOffsetMs = 0) }
        }
    }

    private fun recompute() {
        _state.update { s ->
            val threats = ThreatMapper.toMarkers(alerts, s.settings.dronesOnly)
            s.copy(
                threats = threats,
                mapThreats = ThreatMapper.capForMap(threats, s.settings.maxMapMarkers),
                proximity = ThreatMapper.proximity(s.favorites, threats, s.settings.proximityRadiusKm),
                alertedOblasts = alertedOblasts(alerts),
            )
        }
    }

    /** Oblast UID to whether the alert covers the whole oblast (true) or only part of it (false). */
    private fun alertedOblasts(alerts: List<Alert>): Map<Int, Boolean> {
        val result = mutableMapOf<Int, Boolean>()
        alerts.filter { it.alertType == "air_raid" || it.threats.isNotEmpty() }.forEach { alert ->
            val uid = alert.locationOblastUid?.toIntOrNull() ?: return@forEach
            result[uid] = (result[uid] ?: false) || alert.locationType == "oblast"
        }
        return result
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val old = _state.value.settings
        val new = transform(old)
        if (new == old) return
        repository.saveSettings(new)
        _state.update { it.copy(settings = new) }
        if (new.apiToken != old.apiToken || new.baseUrl != old.baseUrl) {
            alerts = emptyList()
            localTracker = DroneTracker()
            _state.update { it.copy(error = null, tracks = emptyList()) }
            startPolling()
        } else {
            recompute()
        }
    }

    fun setMapMode(mode: MapMode) = updateSettings { it.copy(mapMode = mode) }

    fun addFavorite(name: String, point: GeoPoint) {
        val trimmed = name.trim().ifBlank { "Point ${point.lat.format()}, ${point.lon.format()}" }
        val favorite = FavoriteLocation(
            id = "${Clock.System.now().toEpochMilliseconds()}-${trimmed.hashCode()}",
            name = trimmed,
            lat = point.lat,
            lon = point.lon,
        )
        saveFavorites(_state.value.favorites + favorite)
    }

    fun removeFavorite(id: String) = saveFavorites(_state.value.favorites.filterNot { it.id == id })

    private fun saveFavorites(favorites: List<FavoriteLocation>) {
        repository.saveFavorites(favorites)
        _state.update { it.copy(favorites = favorites) }
        recompute()
    }

    private fun Double.format() = ((this * 100).roundToInt() / 100.0).toString()
}
