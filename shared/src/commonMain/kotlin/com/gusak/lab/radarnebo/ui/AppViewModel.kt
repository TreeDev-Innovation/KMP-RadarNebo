package com.gusak.lab.radarnebo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gusak.lab.radarnebo.data.AlertsSnapshot
import com.gusak.lab.radarnebo.data.AlertsUpdate
import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.FavoritesRepository
import com.gusak.lab.radarnebo.data.MapMode
import com.gusak.lab.radarnebo.data.ServerHealth
import com.gusak.lab.radarnebo.data.SettingsRepository
import com.gusak.lab.radarnebo.domain.ProximityHit
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.domain.usecase.AddFavoriteUseCase
import com.gusak.lab.radarnebo.domain.usecase.BuildThreatPictureUseCase
import com.gusak.lab.radarnebo.domain.usecase.RefreshAlertsUseCase
import com.gusak.lab.radarnebo.domain.usecase.RemoveFavoriteUseCase
import com.gusak.lab.radarnebo.domain.usecase.UpdateSettingsUseCase
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.tracking.DroneTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
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
    /** Target position history from the server (shown classes only); the app interpolates between reports. */
    val tracks: List<DroneTrack> = emptyList(),
    /** [tracks] including those the target filter hides. */
    val allTracks: List<DroneTrack> = tracks,
    /** Server clock minus local clock, applied before interpolating track positions. */
    val clockOffsetMs: Long = 0,
    /** The server's data is simulated, not the real situation; always shown as such. */
    val simulated: Boolean = false,
    /** The server's last health report; null while it can't be reached. */
    val serverHealth: ServerHealth? = null,
    /** When the app tries the server again after a failed poll; null while polls succeed. */
    val nextRetryAt: Instant? = null,
)

/**
 * Screen state for the whole app. Settings and favorites come from their repositories; alerts are
 * polled through [RefreshAlertsUseCase] and turned into what the screens show by [BuildThreatPictureUseCase].
 */
class AppViewModel(
    settingsRepository: SettingsRepository,
    favoritesRepository: FavoritesRepository,
    private val refreshAlerts: RefreshAlertsUseCase,
    private val buildThreatPicture: BuildThreatPictureUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase,
    private val addFavoriteUseCase: AddFavoriteUseCase,
    private val removeFavoriteUseCase: RemoveFavoriteUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(
        UiState(settings = settingsRepository.settings.value, favorites = favoritesRepository.favorites.value)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Latest successful poll; null until the first one and after switching data source. */
    private var snapshot: AlertsSnapshot? = null
    private var pollJob: Job? = null

    init {
        // The threat picture depends on settings (filters, radius) and favorites, so rebuild it when they change.
        combine(settingsRepository.settings, favoritesRepository.favorites, ::Pair)
            .onEach { (settings, favorites) ->
                _state.update { it.copy(settings = settings, favorites = favorites) }
                recompute()
            }
            .launchIn(viewModelScope)
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(fetchOnce())
            }
        }
    }

    /** Tries the server right away instead of waiting for the reconnect timer. */
    fun reconnect() = startPolling()

    /**
     * Returns how long to wait before the next poll: the poll interval, or after a failure the reconnect delay,
     * so a server started after the app is picked up within seconds. Rate-limit and auth errors back off instead.
     */
    private suspend fun fetchOnce(): Long {
        _state.update { it.copy(loading = true) }
        return when (val update = refreshAlerts()) {
            is AlertsUpdate.Updated -> {
                snapshot = update.snapshot
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        lastUpdated = Clock.System.now(),
                        clockOffsetMs = update.snapshot.clockOffsetMs,
                        simulated = update.snapshot.simulated,
                        serverHealth = update.snapshot.health,
                        nextRetryAt = null,
                    )
                }
                recompute()
                _state.value.settings.pollSeconds * 1000L
            }
            is AlertsUpdate.Failed -> {
                val waitMs = if (update.backOff) _state.value.settings.pollSeconds * 1000L * 4 else RECONNECT_MS
                _state.update {
                    it.copy(
                        loading = false,
                        error = update.message,
                        serverHealth = null,
                        nextRetryAt = Clock.System.now() + waitMs.milliseconds,
                    )
                }
                waitMs
            }
        }
    }

    private fun recompute() {
        _state.update { s ->
            val now = Clock.System.now().toEpochMilliseconds() + s.clockOffsetMs
            val picture = buildThreatPicture(snapshot?.alerts.orEmpty(), snapshot?.tracks.orEmpty(), s.settings, s.favorites, now)
            s.copy(
                threats = picture.threats,
                mapThreats = picture.mapThreats,
                proximity = picture.proximity,
                alertedOblasts = picture.alertedOblasts,
                tracks = picture.tracks,
                allTracks = picture.unfilteredTracks,
            )
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val update = updateSettingsUseCase(transform)
        if (update.sourceChanged) {
            snapshot = null
            _state.update { it.copy(error = null, tracks = emptyList(), allTracks = emptyList(), clockOffsetMs = 0, simulated = false, serverHealth = null, lastUpdated = null, nextRetryAt = null) }
            startPolling()
        } else if (update.trackingChanged) {
            // Markers re-place at once from the settings; the other tracks come with a poll right away.
            startPolling()
        }
    }

    fun setMapMode(mode: MapMode) = updateSettings { it.copy(mapMode = mode) }

    fun addFavorite(name: String, point: GeoPoint) {
        addFavoriteUseCase(name, point)
    }

    fun removeFavorite(id: String) = removeFavoriteUseCase(id)
}

/** How soon the app tries an unreachable server again. */
private const val RECONNECT_MS = 5_000L
