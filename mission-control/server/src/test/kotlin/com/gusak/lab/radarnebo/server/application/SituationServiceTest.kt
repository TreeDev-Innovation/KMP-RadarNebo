package com.gusak.lab.radarnebo.server.application

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.server.Fixtures
import com.gusak.lab.radarnebo.server.Fixtures.alert
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AlertArchive
import com.gusak.lab.radarnebo.server.domain.alert.LocationDirectory
import com.gusak.lab.radarnebo.server.domain.tracking.ObservationTracking
import com.gusak.lab.radarnebo.server.domain.tracking.TargetObservation
import com.gusak.lab.radarnebo.server.domain.tracking.TargetTracking
import com.gusak.lab.radarnebo.server.domain.tracking.TrackArchive
import com.gusak.lab.radarnebo.server.domain.tracking.TrackQuery
import com.gusak.lab.radarnebo.server.domain.tracking.TrackingUpdate
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryAlertArchive
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryTrackArchive
import com.gusak.lab.radarnebo.tracking.DroneTrack
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** An archive whose next [save] fails once [failNextSave] is set, like a database that is briefly unavailable. */
private class FlakyAlertArchive(private val delegate: InMemoryAlertArchive = InMemoryAlertArchive()) : AlertArchive by delegate {
    var failNextSave = false

    override suspend fun save(alerts: Collection<AirAlert>) {
        if (failNextSave) {
            failNextSave = false
            throw IOException("archive unavailable")
        }
        delegate.save(alerts)
    }
}

/** The track counterpart of [FlakyAlertArchive]. */
private class FlakyTrackArchive(private val delegate: InMemoryTrackArchive = InMemoryTrackArchive()) : TrackArchive by delegate {
    var failNextSave = false

    override suspend fun save(tracks: Collection<DroneTrack>) {
        if (failNextSave) {
            failNextSave = false
            throw IOException("archive unavailable")
        }
        delegate.save(tracks)
    }
}

/** Serves no tracks and reports [track] as ended on update number [endOnCall], so archiving can be tested on its own. */
private class EndingTracking(private val endOnCall: Int, private val track: DroneTrack) : TargetTracking {
    private var calls = 0

    override fun update(alerts: List<AirAlert>, observations: List<TargetObservation>, nowMs: Long) =
        TrackingUpdate(tracks = emptyList(), ended = if (++calls == endOnCall) listOf(track) else emptyList())
}

class SituationServiceTest {
    private val t0 = Fixtures.t0
    private val archive = FlakyAlertArchive()
    private val trackArchive = FlakyTrackArchive()
    private val situation = newSituation(ObservationTracking())

    private val brovary = alert(1, Fixtures.brovary)
    private val kharkiv = alert(2, Fixtures.kharkiv, startedAt = t0 + 1.minutes)

    private val endedTrack = DroneTrack(
        id = "track-1",
        level = ThreatLevel.Red,
        oblastUid = Fixtures.brovary.oblastUid,
        active = false,
        firstSeen = t0.toEpochMilliseconds(),
        lastSeen = (t0 + 5.minutes).toEpochMilliseconds(),
        markerIds = emptyList(),
        samples = emptyList(),
    )

    private fun newSituation(tracking: TargetTracking) = SituationService(
        tracking = tracking,
        alertArchive = archive,
        trackArchive = trackArchive,
        locations = LocationDirectory(),
        simulated = false,
    )

    private fun report(at: Instant, vararg alerts: AirAlert) = SituationReport(at.toEpochMilliseconds(), alerts.toList())

    private suspend fun archivedTrackIds() = trackArchive.history(TrackQuery(0, Long.MAX_VALUE)).map { it.id }

    @Test
    fun aFailedArchiveWriteIsRepairedByTheNextPublish() = runTest {
        situation.publish(report(t0, brovary))

        archive.failNextSave = true
        assertFailsWith<IOException> { situation.publish(report(t0 + 1.minutes, kharkiv)) }

        situation.publish(report(t0 + 2.minutes, kharkiv))
        assertNull(archive.history(Fixtures.kharkiv.uid, t0, limit = 10).single().finishedAt, "the opened alert is archived")
        assertNotNull(archive.history(Fixtures.brovary.uid, t0, limit = 10).single().finishedAt, "the finished alert is archived as finished")
        assertEquals(listOf(kharkiv), archive.unfinished())
    }

    @Test
    fun aTrackThatEndsWhileTheAlertArchiveFailsIsArchivedByTheNextPublish() = runTest {
        val situation = newSituation(EndingTracking(endOnCall = 2, track = endedTrack))
        situation.publish(report(t0, brovary))

        archive.failNextSave = true
        assertFailsWith<IOException> { situation.publish(report(t0 + 1.minutes, kharkiv)) }

        situation.publish(report(t0 + 2.minutes, kharkiv))
        assertEquals(listOf(endedTrack.id), archivedTrackIds())
    }

    @Test
    fun aFailedTrackArchiveWriteIsRetriedOnTheNextConfirm() = runTest {
        val situation = newSituation(EndingTracking(endOnCall = 2, track = endedTrack))
        situation.publish(report(t0, brovary))

        trackArchive.failNextSave = true
        assertFailsWith<IOException> { situation.publish(report(t0 + 1.minutes, kharkiv)) }
        assertEquals(listOf(kharkiv), archive.unfinished(), "the alerts are archived before the tracks")

        situation.confirm((t0 + 2.minutes).toEpochMilliseconds())
        assertEquals(listOf(endedTrack.id), archivedTrackIds())
    }

    @Test
    fun tracksTheArchiveRefusesTwiceAreDroppedSoLaterWritesSucceed() = runTest {
        val situation = newSituation(EndingTracking(endOnCall = 2, track = endedTrack))
        situation.publish(report(t0, brovary))

        trackArchive.failNextSave = true
        assertFailsWith<IOException> { situation.publish(report(t0 + 1.minutes, kharkiv)) }
        trackArchive.failNextSave = true
        assertFailsWith<IOException> { situation.confirm((t0 + 2.minutes).toEpochMilliseconds()) }

        situation.confirm((t0 + 3.minutes).toEpochMilliseconds())
        assertTrue(archivedTrackIds().isEmpty(), "the twice-refused track is not retried again")
    }

    @Test
    fun alertsThatFinishDuringReplayAreNotArchived() = runTest {
        situation.replay(report(t0, brovary))
        situation.replay(report(t0 + 1.minutes))
        situation.publish(report(t0 + 2.minutes, kharkiv))

        assertTrue(archive.history(Fixtures.brovary.uid, t0, limit = 10).isEmpty())
        assertEquals(listOf(kharkiv), archive.unfinished())
    }

    @Test
    fun confirmMovesConfirmedAtKeepsTheVersionAndArchivesEndedTracks() = runTest {
        val situation = newSituation(EndingTracking(endOnCall = 2, track = endedTrack))
        situation.confirm(t0.toEpochMilliseconds())
        assertNull(situation.board, "nothing to confirm before the first report")

        situation.publish(report(t0, brovary))
        val version = situation.board!!.version

        situation.confirm((t0 + 10.minutes).toEpochMilliseconds())
        assertEquals(t0 + 10.minutes, situation.board!!.confirmedAt)
        assertEquals(version, situation.board!!.version)
        assertEquals(listOf(endedTrack.id), archivedTrackIds())
    }
}
