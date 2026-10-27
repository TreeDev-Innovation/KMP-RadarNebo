package com.gusak.lab.radarnebo.server.application

import com.gusak.lab.radarnebo.server.Fixtures
import com.gusak.lab.radarnebo.server.Fixtures.alert
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.alert.AlertArchive
import com.gusak.lab.radarnebo.server.domain.alert.LocationDirectory
import com.gusak.lab.radarnebo.server.domain.tracking.ObservationTracking
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryAlertArchive
import com.gusak.lab.radarnebo.server.infrastructure.persistence.InMemoryTrackArchive
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

class SituationServiceTest {
    private val t0 = Fixtures.t0
    private val archive = FlakyAlertArchive()
    private val situation = SituationService(
        tracking = ObservationTracking(),
        alertArchive = archive,
        trackArchive = InMemoryTrackArchive(),
        locations = LocationDirectory(),
        simulated = false,
    )

    private fun report(at: Instant, vararg alerts: AirAlert) = SituationReport(at.toEpochMilliseconds(), alerts.toList())

    @Test
    fun aFailedArchiveWriteIsRepairedByTheNextPublish() = runTest {
        val brovary = alert(1, Fixtures.brovary)
        val kharkiv = alert(2, Fixtures.kharkiv, startedAt = t0 + 1.minutes)
        situation.publish(report(t0, brovary))

        archive.failNextSave = true
        assertFailsWith<IOException> { situation.publish(report(t0 + 1.minutes, kharkiv)) }

        situation.publish(report(t0 + 2.minutes, kharkiv))
        assertNull(archive.history(Fixtures.kharkiv.uid, t0, limit = 10).single().finishedAt, "the opened alert is archived")
        assertNotNull(archive.history(Fixtures.brovary.uid, t0, limit = 10).single().finishedAt, "the finished alert is archived as finished")
        assertEquals(listOf(kharkiv), archive.unfinished())
    }
}
