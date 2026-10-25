package com.gusak.lab.radarnebo.server.infrastructure.persistence

import com.gusak.lab.radarnebo.data.Alert
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract.toDomain
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract.toDto
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AlertArchive
import com.gusak.lab.radarnebo.server.domain.tracking.TrackArchive
import com.gusak.lab.radarnebo.server.domain.tracking.TrackQuery
import com.gusak.lab.radarnebo.tracking.DroneTrack
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Instant

/** [AlertArchive] in [ArchiveDatabase], partitioned by data source ([partitionKey]). */
class ExposedAlertArchive(private val db: ArchiveDatabase, private val partitionKey: String) : AlertArchive {

    override suspend fun save(alerts: Collection<AirAlert>) {
        if (alerts.isEmpty()) return
        db.query {
            AlertsTable.batchUpsert(alerts) { alert ->
                this[AlertsTable.partition] = partitionKey
                this[AlertsTable.id] = alert.id
                this[AlertsTable.locationUid] = alert.location.uid
                this[AlertsTable.oblastUid] = alert.location.oblastUid
                this[AlertsTable.startedAt] = alert.startedAt.toEpochMilliseconds()
                this[AlertsTable.finishedAt] = alert.finishedAt?.toEpochMilliseconds()
                this[AlertsTable.payload] = AlertsInUaContract.json.encodeToString(Alert.serializer(), alert.toDto())
            }
        }
    }

    override suspend fun unfinished(): List<AirAlert> = db.query {
        AlertsTable.selectAll()
            .where { (AlertsTable.partition eq partitionKey) and AlertsTable.finishedAt.isNull() }
            .map(::toAlert)
    }

    override suspend fun history(uid: String, since: Instant, limit: Int): List<AirAlert> = db.query {
        val region = uid.toIntOrNull()?.takeIf { AirAlert.isRegionUid(uid) }
        val location = if (region != null) (AlertsTable.locationUid eq uid) or (AlertsTable.oblastUid eq region) else AlertsTable.locationUid eq uid
        AlertsTable.selectAll()
            .where {
                (AlertsTable.partition eq partitionKey) and location and
                    (AlertsTable.finishedAt.isNull() or (AlertsTable.finishedAt greaterEq since.toEpochMilliseconds()))
            }
            .orderBy(AlertsTable.startedAt, SortOrder.DESC)
            .limit(limit)
            .map(::toAlert)
    }

    override suspend fun prune(before: Instant): Int = db.query {
        AlertsTable.deleteWhere { (AlertsTable.partition eq partitionKey) and (AlertsTable.finishedAt less before.toEpochMilliseconds()) }
    }

    private fun toAlert(row: ResultRow): AirAlert {
        val dto = AlertsInUaContract.json.decodeFromString(Alert.serializer(), row[AlertsTable.payload])
        return dto.toDomain(Instant.fromEpochMilliseconds(row[AlertsTable.startedAt]))
    }
}

/** [TrackArchive] in [ArchiveDatabase], partitioned by data source ([partitionKey]). */
class ExposedTrackArchive(private val db: ArchiveDatabase, private val partitionKey: String) : TrackArchive {

    override suspend fun save(tracks: Collection<DroneTrack>) {
        if (tracks.isEmpty()) return
        db.query {
            TracksTable.batchUpsert(tracks) { track ->
                this[TracksTable.partition] = partitionKey
                this[TracksTable.id] = track.id
                this[TracksTable.type] = track.type.apiValue
                this[TracksTable.firstSeen] = track.firstSeen
                this[TracksTable.lastSeen] = track.lastSeen
                this[TracksTable.payload] = AlertsInUaContract.json.encodeToString(DroneTrack.serializer(), track)
            }
        }
    }

    override suspend fun history(query: TrackQuery): List<DroneTrack> = db.query {
        TracksTable.selectAll()
            .where {
                var condition = (TracksTable.partition eq partitionKey) and
                    (TracksTable.firstSeen lessEq query.toMs) and (TracksTable.lastSeen greaterEq query.fromMs)
                query.type?.let { condition = condition and (TracksTable.type eq it.apiValue) }
                condition
            }
            .orderBy(TracksTable.lastSeen, SortOrder.DESC)
            .limit(query.limit)
            .map { AlertsInUaContract.json.decodeFromString(DroneTrack.serializer(), it[TracksTable.payload]) }
    }

    override suspend fun prune(beforeMs: Long): Int = db.query {
        TracksTable.deleteWhere { (TracksTable.partition eq partitionKey) and (TracksTable.lastSeen less beforeMs) }
    }
}
