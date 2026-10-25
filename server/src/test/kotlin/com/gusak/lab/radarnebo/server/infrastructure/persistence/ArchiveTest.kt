package com.gusak.lab.radarnebo.server.infrastructure.persistence

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.server.Fixtures
import com.gusak.lab.radarnebo.server.Fixtures.alert
import com.gusak.lab.radarnebo.server.application.SituationReport
import com.gusak.lab.radarnebo.server.application.SituationService
import com.gusak.lab.radarnebo.server.domain.alert.AlertArchive
import com.gusak.lab.radarnebo.server.domain.alert.LocationDirectory
import com.gusak.lab.radarnebo.server.domain.tracking.ObservationTracking
import com.gusak.lab.radarnebo.server.domain.tracking.TrackArchive
import com.gusak.lab.radarnebo.server.domain.tracking.TrackQuery
import com.gusak.lab.radarnebo.tracking.DroneTrack
import com.gusak.lab.radarnebo.tracking.TrackOutcome
import com.gusak.lab.radarnebo.tracking.TrackSample
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The SQLite archive, checked against the in-memory one that tests elsewhere rely on. */
class ArchiveTest {
    private val dir: File = Files.createTempDirectory("radarnebo-archive").toFile()
    private val db = ArchiveDatabase("jdbc:sqlite:${File(dir, "nested/archive.db").path}")
    private val t0 = Fixtures.t0

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun track(id: String, firstSeen: Long, lastSeen: Long, type: ThreatType = ThreatType.Drones) = DroneTrack(
        id = id, type = type, level = ThreatLevel.Red, oblastUid = 14, active = false, firstSeen = firstSeen, lastSeen = lastSeen,
        markerIds = emptyList(), outcome = TrackOutcome.Intercepted,
        samples = listOf(TrackSample(firstSeen, 50.5, 30.9, "Brovary", 14, "Шахед"), TrackSample(lastSeen, 50.45, 30.6, "Kyiv", 31, "Шахед")),
    )

    private suspend fun checkAlerts(archive: AlertArchive) {
        val brovary = alert(1, Fixtures.brovary)
        val kharkiv = alert(2, Fixtures.kharkiv, startedAt = t0 + 5.minutes)
        archive.save(listOf(brovary, kharkiv))
        assertEquals(setOf(1L, 2L), archive.unfinished().map { it.id }.toSet())

        archive.save(listOf(brovary.finish(t0 + 1.hours)))
        assertEquals(listOf(2L), archive.unfinished().map { it.id })
        val history = archive.history("14", since = t0, limit = 10)
        assertEquals(listOf(brovary.finish(t0 + 1.hours)), history, "an oblast's history includes its hromadas, round-tripped intact")
        assertEquals(listOf(1L), archive.history("1100", since = t0, limit = 10).map { it.id })
        assertTrue(archive.history("1100", since = t0 + 2.hours, limit = 10).isEmpty(), "finished before the period")
        assertEquals(listOf(2L), archive.history("1293", since = t0 + 2.days, limit = 10).map { it.id }, "active alerts are in every period")

        assertEquals(1, archive.prune(t0 + 2.hours))
        assertTrue(archive.history("14", since = t0, limit = 10).isEmpty())
    }

    private suspend fun checkTracks(archive: TrackArchive) {
        val t = t0.toEpochMilliseconds()
        archive.save(listOf(track("a", t, t + 3_600_000), track("b", t + 7_200_000, t + 9_000_000), track("m", t, t + 600_000, ThreatType.CruiseMissiles)))
        assertEquals(listOf("b", "a", "m"), archive.history(TrackQuery(t, t + 9_000_000)).map { it.id }, "newest first")
        assertEquals(listOf("a", "m"), archive.history(TrackQuery(t + 60_000, t + 120_000)).map { it.id }, "tracks overlapping the window")
        assertEquals(listOf("m"), archive.history(TrackQuery(t, t + 9_000_000, ThreatType.CruiseMissiles)).map { it.id })
        assertEquals(track("a", t, t + 3_600_000), archive.history(TrackQuery(t, t + 60_000, ThreatType.Drones)).single())
        assertEquals(2, archive.prune(t + 4_000_000))
        assertEquals(listOf("b"), archive.history(TrackQuery(t, t + 9_000_000)).map { it.id })
    }

    @Test
    fun sqliteArchiveKeepsAlerts() = runTest { checkAlerts(ExposedAlertArchive(db, "test")) }

    @Test
    fun inMemoryArchiveKeepsAlerts() = runTest { checkAlerts(InMemoryAlertArchive()) }

    @Test
    fun sqliteArchiveKeepsTracks() = runTest { checkTracks(ExposedTrackArchive(db, "test")) }

    @Test
    fun inMemoryArchiveKeepsTracks() = runTest { checkTracks(InMemoryTrackArchive()) }

    @Test
    fun dataSourcesNeverSeeEachOthersRecords() = runTest {
        ExposedAlertArchive(db, "Simulation").save(listOf(alert(1, Fixtures.brovary)))
        assertTrue(ExposedAlertArchive(db, "AlertsInUa").unfinished().isEmpty())
    }

    @Test
    fun alertsLeftOpenByAStoppedServerAreFinishedOnTheFirstReportAfterARestart() = runTest {
        val archive = ExposedAlertArchive(db, "test")
        fun service() = SituationService(ObservationTracking(), archive, ExposedTrackArchive(db, "test"), LocationDirectory(), simulated = false)
        val first = service()
        first.publish(SituationReport(t0.toEpochMilliseconds(), listOf(alert(1, Fixtures.brovary), alert(2, Fixtures.kharkiv))))

        // The server stops; when it is back, Brovary's alert is over.
        val restarted = service()
        val back = t0 + 30.minutes
        restarted.publish(SituationReport(back.toEpochMilliseconds(), listOf(alert(2, Fixtures.kharkiv))))
        assertEquals(listOf(2L), archive.unfinished().map { it.id })
        assertEquals(back, archive.history("1100", t0, 10).single().finishedAt)
        assertEquals(listOf(1L), restarted.alertHistory("14", t0, 10).map { it.id })
    }
}
