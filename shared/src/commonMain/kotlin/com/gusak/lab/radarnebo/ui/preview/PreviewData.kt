package com.gusak.lab.radarnebo.ui.preview

import com.gusak.lab.radarnebo.data.AppSettings
import com.gusak.lab.radarnebo.data.DroneSimulator
import com.gusak.lab.radarnebo.data.FavoriteLocation
import com.gusak.lab.radarnebo.data.MapMode
import com.gusak.lab.radarnebo.data.ThemeMode
import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.domain.ThreatMarker
import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.DroneTracker
import com.gusak.lab.radarnebo.tracking.TrackSample
import com.gusak.lab.radarnebo.ui.UiState
import com.gusak.lab.radarnebo.ui.map.MapCamera
import com.gusak.lab.radarnebo.ui.map.MapProjection
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Sample data for Compose previews and README screenshots.
 *
 * [state] runs the attack simulation at the current time, so it looks like the live app but what is
 * on screen changes from minute to minute. [scenario] is hand-placed relative to the current time
 * and always shows the same targets, one per state, for previews that must show a specific thing.
 */
internal object PreviewData {
    val favorites = listOf(
        FavoriteLocation("1", "Home", 50.4501, 30.5234),
        FavoriteLocation("2", "Kharkiv", 49.9935, 36.2304),
        FavoriteLocation("3", "Parents", 49.5883, 34.5514),
    )

    /** With [dronesOnly] off, the state holds every threat class: missiles, bombs, aviation and air defense. */
    fun state(theme: ThemeMode = ThemeMode.Dark, mapMode: MapMode = MapMode.Static, dronesOnly: Boolean = true): UiState {
        val now = Clock.System.now().toEpochMilliseconds()
        val tracker = DroneTracker()
        DroneSimulator.warmUp(tracker, now)
        val alerts = DroneSimulator.alertsAt(now)
        tracker.update(ThreatMapper.toMarkers(alerts, dronesOnly = false), now)
        val threats = ThreatMapper.toMarkers(alerts, dronesOnly)
        return UiState(
            settings = AppSettings(themeMode = theme, mapMode = mapMode, dronesOnly = dronesOnly),
            favorites = favorites,
            threats = threats,
            mapThreats = threats,
            proximity = ThreatMapper.proximity(favorites, threats, 50),
            alertedOblasts = alerts.mapNotNull { a -> a.locationOblastUid?.toIntOrNull()?.let { it to (a.locationType == "oblast") } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, wide) -> wide.any { it } },
            tracks = tracker.snapshot().filter { !dronesOnly || it.type == ThreatType.Drones },
            lastUpdated = Clock.System.now(),
        )
    }

    /** The most interesting track to preview: active, with the longest history. */
    fun busiestTrackId(state: UiState): String? =
        state.tracks.filter { it.active }.maxByOrNull { it.samples.size }?.id

    // --- Hand-placed scenario ------------------------------------------------------------------------

    /** Ids of the scenario's targets, one per state worth previewing. */
    object Targets {
        /** Shahed group in flight: several reports, dead-reckoned past the last one. */
        const val SHAHED = "trk-11"
        /** Gerbera decoys: drawn hollow. */
        const val DECOY = "trk-12"
        /** Jet Shahed: fast, few reports. */
        const val JET = "trk-13"
        /** Reconnaissance drone with a single report: held in place, no heading yet. */
        const val RECON = "trk-14"
        /** Group whose alerts cleared a few minutes ago: faded, no longer moving. */
        const val CLEARED = "trk-15"
        /** Cruise missile closing on Kyiv. */
        const val MISSILE = "trk-16"
    }

    fun city(name: String): GeoPoint = Regions.cities.first { it.name == name }.point

    /**
     * Every target state and threat class at once, around Kyiv, Chernihiv and Poltava, with favorites
     * close enough to raise warnings. Times are relative to now, so the map treats it as live.
     */
    fun scenario(
        theme: ThemeMode = ThemeMode.Dark,
        mapMode: MapMode = MapMode.Static,
        dronesOnly: Boolean = false,
        favorites: List<FavoriteLocation> = scenarioFavorites,
        radiusKm: Int = 50,
    ): UiState {
        val now = Clock.System.now().toEpochMilliseconds()
        val tracks = listOf(
            track(Targets.SHAHED, now, ThreatType.Drones, DroneSimulator.SHAHED_MESSAGE, 41 to "Konotop", 31 to "Bakhmach", 7 to "Nizhyn"),
            track(Targets.DECOY, now, ThreatType.Drones, DroneSimulator.GERBERA_MESSAGE, 62 to "Lubny", 18 to "Pereiaslav", 5 to "Yahotyn"),
            track(Targets.JET, now, ThreatType.Drones, DroneSimulator.JET_SHAHED_MESSAGE, 20 to "Myrhorod", 6 to "Zolotonosha"),
            track(Targets.RECON, now, ThreatType.Drones, DroneSimulator.RECON_MESSAGE, 5 to "Koriukivka", level = ThreatLevel.Yellow),
            track(Targets.CLEARED, now, ThreatType.Drones, DroneSimulator.SHAHED_MESSAGE, 35 to "Fastiv", 24 to "Vasylkiv", active = false, clearedMinutesAgo = 3),
            track(Targets.MISSILE, now, ThreatType.CruiseMissiles, DroneSimulator.KH101_MESSAGE, 7 to "Berdychiv", 4 to "Zhytomyr"),
        )
        val trackMarkers = tracks.filter { it.active }.map { trackMarker(it, now) }
        val areaMarkers = listOf(
            area("ballistic", ThreatType.BallisticMissiles, Regions.KYIV_CITY_UID, "Швидкісна ціль — ракета «Кинджал» на Київ", now, minutesAgo = 2, city = true),
            area("air-defense", ThreatType.AirDefense, 14, "Працює ППО", now, at = city("Boryspil"), location = "hromada"),
            area("iskander", ThreatType.BallisticMissiles, 22, "Загроза балістичних ракет «Іскандер-М»", now),
            area("kab", ThreatType.GuidedBombs, 22, "Пуски КАБ (УМПК) з Су-34", now, at = city("Kupiansk"), location = "hromada"),
            area("tactical", ThreatType.TacticalAviation, 12, "Активність тактичної авіації (Су-34/Су-35)", now),
            area("strategic", ThreatType.StrategicAviation, 17, "Активність стратегічної авіації (Ту-95МС)", now),
            area("mig31k", ThreatType.Mig31k, 10, "Зліт МіГ-31К — загроза ракет «Кинджал»", now),
            area("missiles", ThreatType.UnspecifiedMissiles, 19, "Ракетна небезпека", now, level = ThreatLevel.Yellow),
            area("unknown", ThreatType.Unknown, 26, "Загроза", now, level = ThreatLevel.Yellow),
            area("drones-wide", ThreatType.Drones, 25, "Групи ударних БпЛА", now, level = ThreatLevel.Yellow),
        )
        val threats = (trackMarkers + areaMarkers)
            .filter { !dronesOnly || it.type == ThreatType.Drones }
            .sortedWith(compareBy<ThreatMarker> { it.level }.thenByDescending { it.startedAt })
        return UiState(
            settings = AppSettings(themeMode = theme, mapMode = mapMode, dronesOnly = dronesOnly, proximityRadiusKm = radiusKm),
            favorites = favorites,
            threats = threats,
            mapThreats = threats,
            proximity = ThreatMapper.proximity(favorites, threats, radiusKm),
            alertedOblasts = threats.mapNotNull { m -> m.oblastUid?.let { it to m.isRegionWide } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, wide) -> wide.any { it } },
            tracks = tracks.filter { !dronesOnly || it.type == ThreatType.Drones },
            lastUpdated = Instant.fromEpochMilliseconds(now - 20_000),
        )
    }

    val scenarioFavorites = listOf(
        FavoriteLocation("home", "Home", 50.4501, 30.5234),
        FavoriteLocation("dacha", "Dacha", 50.93, 31.62),
        FavoriteLocation("kharkiv", "Kharkiv", 49.9935, 36.2304),
    )

    /** A state with nothing happening: no alerts, no tracks. */
    fun quiet(theme: ThemeMode = ThemeMode.Dark, mapMode: MapMode = MapMode.Static, favorites: List<FavoriteLocation> = scenarioFavorites) =
        UiState(
            settings = AppSettings(themeMode = theme, mapMode = mapMode),
            favorites = favorites,
            lastUpdated = Clock.System.now(),
        )

    /** Camera looking at [center] at [zoom], e.g. close enough for city names (zoom 2.5 and more). */
    fun camera(center: GeoPoint, zoom: Float) = MapCamera(MapProjection.toWorld(center), zoom)

    private fun track(
        id: String,
        now: Long,
        type: ThreatType,
        message: String,
        vararg reports: Pair<Int, String>,
        active: Boolean = true,
        clearedMinutesAgo: Int = 0,
        level: ThreatLevel = ThreatLevel.Red,
    ): DroneTrack {
        val samples = reports.map { (minutesAgo, name) ->
            val city = Regions.cities.first { it.name == name }
            TrackSample(now - minutesAgo * 60_000L, city.point.lat, city.point.lon, name, city.oblastUid, message)
        }
        return DroneTrack(
            id = id,
            type = type,
            level = level,
            oblastUid = samples.last().oblastUid,
            active = active,
            firstSeen = samples.first().t,
            lastSeen = if (active) now else now - clearedMinutesAgo * 60_000L,
            // alerts.in.ua ids are numbers: derive a stable one from the track id.
            markerIds = if (active) listOf("8${id.substringAfter("trk-")}042:${type.apiValue}") else emptyList(),
            samples = samples,
        )
    }

    /** The alert behind an active track, so warnings and the threat list know about it. */
    private fun trackMarker(track: DroneTrack, now: Long): ThreatMarker {
        val last = track.samples.last()
        return ThreatMarker(
            id = track.markerIds.single(),
            type = track.type,
            level = track.level,
            title = last.title,
            oblastUid = last.oblastUid,
            oblastName = last.oblastUid?.let { Regions.oblast(it)?.nameEn }.orEmpty(),
            locationType = "hromada",
            point = last.point,
            startedAt = Instant.fromEpochMilliseconds(last.t),
            message = last.message,
        )
    }

    private fun area(
        id: String,
        type: ThreatType,
        oblastUid: Int,
        message: String,
        now: Long,
        minutesAgo: Int = 12,
        level: ThreatLevel = ThreatLevel.Red,
        at: GeoPoint? = null,
        location: String = "oblast",
        city: Boolean = false,
    ): ThreatMarker {
        val oblast = Regions.oblast(oblastUid)!!
        return ThreatMarker(
            id = "$id:${type.apiValue}",
            type = type,
            level = level,
            title = oblast.nameEn,
            oblastUid = oblastUid,
            oblastName = oblast.nameEn,
            locationType = if (city) "city" else location,
            point = at ?: Regions.center(oblastUid)!!,
            startedAt = Instant.fromEpochMilliseconds(now - minutesAgo * 60_000L),
            message = message,
        )
    }
}
