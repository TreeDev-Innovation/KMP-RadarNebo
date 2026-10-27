package com.gusak.lab.radarnebo.server.domain.tracking

import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.tracking.DroneTrack
import kotlin.time.Duration.Companion.days

/** Tracks of finished flights. */
interface TrackArchive {
    /** Inserts or replaces [tracks] by id. */
    suspend fun save(tracks: Collection<DroneTrack>)

    /** Tracks that were in the air at some point in [query]'s window, newest first. */
    suspend fun history(query: TrackQuery): List<DroneTrack>

    /** Deletes tracks last seen before [beforeMs]; returns how many. */
    suspend fun prune(beforeMs: Long): Int
}

data class TrackQuery(
    val fromMs: Long,
    val toMs: Long,
    val type: ThreatType? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(fromMs <= toMs) { "'from' must not be after 'to'" }
        require(limit in 1..MAX_LIMIT) { "'limit' must be between 1 and $MAX_LIMIT" }
        // Bounds the rows one call can read and decode; `in` also rejects a difference that overflowed.
        require(toMs - fromMs in 0..MAX_WINDOW.inWholeMilliseconds) { "the 'from'..'to' window must be at most 7 days" }
    }

    fun overlaps(track: DroneTrack) = track.firstSeen <= toMs && track.lastSeen >= fromMs && (type == null || track.type == type)

    companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 200
        val MAX_WINDOW = 7.days
    }
}
