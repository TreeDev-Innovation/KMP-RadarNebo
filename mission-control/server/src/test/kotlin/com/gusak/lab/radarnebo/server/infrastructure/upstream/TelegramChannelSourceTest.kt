package com.gusak.lab.radarnebo.server.infrastructure.upstream

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.server.application.FetchResult
import com.gusak.lab.radarnebo.server.contract.AlertsInUaContract.toDto
import com.gusak.lab.radarnebo.server.domain.alert.LocationType
import com.gusak.lab.radarnebo.tracking.DroneTracker
import com.gusak.lab.radarnebo.tracking.TargetClassifier
import com.gusak.lab.radarnebo.tracking.TargetKind
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TelegramChannelSourceTest {
    private val at = Instant.parse("2026-10-29T20:52:46Z")

    private fun parse(text: String) = AirForceMessages.parse(1, text, at)

    @Test
    fun throughACityIsARedAlertThere() {
        val alert = assertNotNull(parse("🏍 Реактивний БпЛА через Бровари на схід Києва."))
        assertEquals("Brovary", alert.location.titleEn)
        assertEquals(ThreatLevel.Red, alert.level)
        assertEquals(ThreatType.Drones, alert.threats.single().type)
    }

    @Test
    fun headingForACityIsAYellowAlertThereInAnyCase() {
        assertEquals("Poltava", parse("🏍 Реактивний БпЛА курсом на Полтаву.")?.location?.titleEn)
        assertEquals("Dnipro", parse("🏍 Реактивний БпЛА в напрямку Дніпра зі сходу.")?.location?.titleEn)
        assertEquals("Kryvyi Rih", parse("🏍 Реактивний БпЛА на Кривий Ріг.")?.location?.titleEn)
        val kyiv = assertNotNull(parse("🚀Балістика в напрямку Києва!"))
        assertEquals("Kyiv", kyiv.location.titleEn)
        assertEquals(ThreatLevel.Yellow, kyiv.level)
        assertEquals(ThreatType.BallisticMissiles, kyiv.threats.single().type)
    }

    @Test
    fun anOblastIsAnOblastWideAlert() {
        val alert = assertNotNull(parse("🏍 Київщина:ще один реактивний БпЛА в напрямку Березані"))
        assertEquals(LocationType.Oblast, alert.location.type)
        assertEquals(14, alert.location.oblastUid)
        assertEquals(LocationType.Oblast, parse("Група ударних БпЛА на Сумщині")?.location?.type)
        val chernihiv = assertNotNull(parse("🚀Балістична ракета через Чернігівщину!"))
        assertEquals(LocationType.Oblast, chernihiv.location.type, "the oblast, not the city of Чернігів")
        assertEquals(25, chernihiv.location.oblastUid)
    }

    @Test
    fun messagesWithoutAWeaponOrAKnownPlaceAreSkipped() {
        assertNull(parse("🏍 Лівобережний масив."))
        assertNull(parse("Відбій загрози"))
    }

    @Test
    fun alertsLookLikeAlertsInUasToTheTracker() {
        val jet = assertNotNull(parse("🏍 Реактивний БпЛА курсом на Полтаву."))
        val marker = ThreatMapper.toMarkers(listOf(jet.toDto()), dronesOnly = false).single()
        assertEquals(TargetKind.JetDrone, TargetClassifier.classify(marker).kind, "the message names the weapon")
        assertTrue(kotlin.math.abs(marker.point.lat - 49.5883) < 0.01, "placed at Poltava")
    }

    @Test
    fun aRecordedChannelPageBecomesAlerts() {
        val html = javaClass.getResourceAsStream("/telegram-kpszsu-2026-10-29.html")!!.bufferedReader().readText()
        val alerts = TelegramChannelSource.parsePage(html, "kpszsu")
        println("kpszsu page: ${alerts.size} alerts: " + alerts.joinToString { "${it.location.titleEn}/${it.level}/${it.threats.single().type}" })
        assertTrue(alerts.size >= 8, "most of the page's warnings name a weapon and a place")
        val tracker = DroneTracker()
        tracker.update(ThreatMapper.toMarkers(alerts.map { it.toDto() }, dronesOnly = false), alerts.maxOf { it.startedAt.toEpochMilliseconds() })
        assertTrue(tracker.snapshot().any { it.type == ThreatType.Drones }, "the jet drones on the page are tracked")
    }

    /** Against the live channel; run with RADARNEBO_LIVE_TELEGRAM=1. */
    @Test
    fun theLiveChannelAnswers() = runBlocking {
        assumeTrue(System.getenv("RADARNEBO_LIVE_TELEGRAM") == "1")
        val source = TelegramChannelSource("kpszsu", activeFor = kotlin.time.Duration.parse("24h"))
        val report = assertIs<FetchResult.Fresh>(source.fetch(null)).report
        println("Live kpszsu: ${report.alerts.size} alerts in the last 24 h: " + report.alerts.takeLast(5).joinToString { "${it.startedAt} ${it.location.titleEn}/${it.level}" })
        source.close()

        // The whole server, with the live channel as its extra source (the primary here reports nothing).
        val empty = object : com.gusak.lab.radarnebo.server.application.AlertsDataSource {
            override val kind = com.gusak.lab.radarnebo.server.config.DataSourceKind.AlertsInUa
            override val description = "empty"
            override val pollInterval = kotlin.time.Duration.parse("15s")
            override val providesObservations = false
            override suspend fun fetch(lastModified: String?) = FetchResult.Fresh(com.gusak.lab.radarnebo.server.application.SituationReport(System.currentTimeMillis(), emptyList()))
        }
        val live = TelegramChannelSource("kpszsu", activeFor = kotlin.time.Duration.parse("6h"))
        val server = com.gusak.lab.radarnebo.server.ServerComponents.create(
            com.gusak.lab.radarnebo.server.config.ServerConfig(),
            archives = com.gusak.lab.radarnebo.server.Archives.inMemory(),
            source = empty,
            extraSources = listOf(live),
        )
        server.poller.pollOnce()
        val tracks = server.situation.tracks
        println("Live server: ${tracks.size} tracks from kpszsu: " + tracks.joinToString { "${it.id} ${it.kind} ${it.samples.joinToString(" → ") { s -> s.title }}" })
        live.close()
    }
}
