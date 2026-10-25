package com.gusak.lab.radarnebo.server.infrastructure.persistence

import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AlertArchive
import com.gusak.lab.radarnebo.server.domain.tracking.TrackArchive
import com.gusak.lab.radarnebo.server.domain.tracking.TrackQuery
import com.gusak.lab.radarnebo.tracking.DroneTrack
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/** [AlertArchive] in memory, for tests and for running without a database. */
class InMemoryAlertArchive : AlertArchive {
    private val alerts = ConcurrentHashMap<Long, AirAlert>()

    override suspend fun save(alerts: Collection<AirAlert>) = alerts.forEach { this.alerts[it.id] = it }

    override suspend fun unfinished(): List<AirAlert> = alerts.values.filter { it.isActive }

    override suspend fun history(uid: String, since: Instant, limit: Int): List<AirAlert> = alerts.values
        .filter { it.concerns(uid) && it.activeSince(since) }
        .sortedByDescending { it.startedAt }
        .take(limit)

    override suspend fun prune(before: Instant): Int {
        val old = alerts.values.filter { alert -> alert.finishedAt?.let { it < before } == true }
        old.forEach { alerts.remove(it.id) }
        return old.size
    }
}

/** [TrackArchive] in memory, for tests and for running without a database. */
class InMemoryTrackArchive : TrackArchive {
    private val tracks = ConcurrentHashMap<String, DroneTrack>()

    override suspend fun save(tracks: Collection<DroneTrack>) = tracks.forEach { this.tracks[it.id] = it }

    override suspend fun history(query: TrackQuery): List<DroneTrack> = tracks.values
        .filter(query::overlaps)
        .sortedByDescending { it.lastSeen }
        .take(query.limit)

    override suspend fun prune(beforeMs: Long): Int {
        val old = tracks.values.filter { it.lastSeen < beforeMs }
        old.forEach { tracks.remove(it.id) }
        return old.size
    }
}
