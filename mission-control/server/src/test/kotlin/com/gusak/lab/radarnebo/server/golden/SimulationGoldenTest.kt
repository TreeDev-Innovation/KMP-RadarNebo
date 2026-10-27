package com.gusak.lab.radarnebo.server.golden

import com.gusak.lab.radarnebo.geo.GeoPoint
import com.gusak.lab.radarnebo.server.application.FetchResult
import com.gusak.lab.radarnebo.server.application.SituationReport
import com.gusak.lab.radarnebo.server.config.SimulationConfig
import com.gusak.lab.radarnebo.server.domain.alert.AirAlert
import com.gusak.lab.radarnebo.server.domain.tracking.TargetObservation
import com.gusak.lab.radarnebo.server.golden.Golden.assertDigest
import com.gusak.lab.radarnebo.server.golden.Golden.assertDigests
import com.gusak.lab.radarnebo.server.golden.Golden.coord
import com.gusak.lab.radarnebo.server.infrastructure.simulation.AirSituationSimulator
import com.gusak.lab.radarnebo.server.infrastructure.simulation.AttackPlan
import com.gusak.lab.radarnebo.server.infrastructure.simulation.CombinedStrikePlanner
import com.gusak.lab.radarnebo.server.infrastructure.simulation.FrontlinePlanner
import com.gusak.lab.radarnebo.server.infrastructure.simulation.SimulatedAlertsSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Pins the simulation: for a few seeds and start times, the planned attacks and the reported alerts and
 * observations over a window are the same as before. Plans for a given seed are part of the contract.
 */
class SimulationGoldenTest {
    private class Case(val seed: Long, val startMs: Long)

    private val cases = listOf(
        Case(SimulationConfig.DEFAULT_SEED, 1_800_000_000_000L),
        Case(7L, 1_790_000_123_000L),
        Case(123_456_789L, 1_810_000_000_000L),
    )

    @Test
    fun plansForASeedAreUnchanged() {
        val expected = listOf(
            "f99b0648405f3695b05d7d4e81bb47cb5a7626a9ac2cdcd1d6a15cc9214cdd32",
            "cd64c7446b799218bf72a9eadb39aa3623deb4d2aeb592496173b778bc6d1f40",
            "bd197d430513b4818a9ad2c50384f8a0fa1b80b6c396dd706eff390fd67225d3",
        )
        assertDigests(cases.mapIndexed { i, case ->
            val config = SimulationConfig(seed = case.seed)
            val period = config.strikePeriod.inWholeMilliseconds
            val strikes = CombinedStrikePlanner(case.seed)
            val frontline = FrontlinePlanner(case.seed)
            val firstStrike = case.startMs / period
            val firstFrontline = case.startMs / frontline.slotMs
            val text = buildString {
                (firstStrike until firstStrike + 3).forEach { slot -> appendPlan("strike $slot", strikes.plan(slot, slot * period)) }
                (firstFrontline until firstFrontline + 12).forEach { slot -> appendPlan("frontline $slot", frontline.plan(slot)) }
            }
            Triple("plans-seed-${case.seed}", expected[i], text)
        })
    }

    @Test
    fun theSimulatedFeedIsUnchanged() {
        val expectedAlerts = listOf(
            "0e1f37c79499b4de6b3a64903b0e592ee7af18952d1f0aed69da4335e0a07122",
            "d97f3182b4727abc0209f0723c7686e9d0bc90e2de66d679698f31fdb361060c",
            "ebe8d75acf73fb4399591e0885eb291b03e487713bb16e09da1df0dcf47e549b",
        )
        val expectedObservations = listOf(
            "f1ce62e9ec865fd345f8f25dd3963c4b3f78258320551b6e9a03af34c2eec85e",
            "37d64b5f956bd804566d210aaa0d652b085748703bd331a8ab8dad542e810d5c",
            "b556e04ac972a8d828f33d7a9b3b362f3e7a4bcc00f34ddad18aa6a958376a9a",
        )
        assertDigests(cases.flatMapIndexed { i, case ->
            val simulator = AirSituationSimulator(SimulationConfig(seed = case.seed), firstAlertId = 1)
            // Eight hours, one report every two minutes: always spans a strike start (strikes are six hours apart).
            val reports = (0 until 8 * 30).map { simulator.reportAt(case.startMs + it * 120_000L) }
            val ids = CanonicalIds()
            val alerts = reports.joinToString("") { ids.alertsText(it) }
            listOf(
                Triple("feed-alerts-seed-${case.seed}", expectedAlerts[i], alerts),
                Triple("feed-observations-seed-${case.seed}", expectedObservations[i], reports.joinToString("") { ids.observationsText(it) }),
            )
        })
    }

    @Test
    fun theSimulatedSourceReplaysTheSameHistoryAndIds() = runTest {
        val now = 1_800_000_000_000L
        val source = SimulatedAlertsSource(SimulationConfig(), clock = { now })
        val history = source.history(now).toList()
        val live = (source.fetch(null) as FetchResult.Fresh).report
        val ids = CanonicalIds()
        val text = (history + live).joinToString("") { ids.alertsText(it) + ids.observationsText(it) }
        assertDigest("source-default", "d811f8a872a4a03824f9145e77ab22526636e4733d0bf5780e573b60f29f89f1", "${history.size} reports\n$text")
    }

    private fun StringBuilder.appendPlan(name: String, plan: AttackPlan) {
        append("# ").append(name).append('\n')
        plan.flights.forEach { f ->
            append("F ").append(f.id).append('|').append(f.munition).append('|').append(f.departAt).append('|').append(f.detectedAt)
                .append('|').append(f.endAt).append('|').append(f.path.arriveAt).append('|').append(f.outcome).append('|').append(f.count)
                .append('|').append(f.targetOblastUid).append('|').append(f.level).append('|').append(coord(f.path.speedKmh))
                .append('|').append(f.message).append('|').append(f.targetName)
                .append('|').append(f.path.waypoints.joinToString(";") { point(it) }).append('\n')
        }
        plan.areas.forEach { a ->
            append("A ").append(a.type).append('|').append(a.oblastUids.joinToString(",")).append('|').append(a.from).append('|')
                .append(a.until).append('|').append(a.level).append('|').append(a.message).append('\n')
        }
    }

    /**
     * Renders reports with alert ids made independent of hash order. Which alert of those opened in the same
     * tick gets which id follows the iteration order of a `HashMap<AlertLocation, ...>`, and [AlertLocation]'s
     * hash includes an enum's identity hash, so it differs between JVM runs (as does the order of the alerts
     * and of an observation's marker ids). The set of ids opened in each tick is stable, so the alerts opened
     * in a tick are given those ids in location-UID order, and everything is listed in a stable order.
     */
    private class CanonicalIds {
        private val canonical = HashMap<Long, Long>()

        fun alertsText(report: SituationReport): String = buildString {
            val new = report.alerts.filter { it.id !in canonical }
            new.sortedBy { it.location.uid }.zip(new.map { it.id }.sorted()).forEach { (alert, id) -> canonical[alert.id] = id }
            append("@").append(report.observedAt).append('\n')
            report.alerts.sortedBy { it.location.uid }.forEach { append(alert(it)).append('\n') }
        }

        fun observationsText(report: SituationReport): String = buildString {
            append("@").append(report.observedAt).append('\n')
            report.observations.forEach { append(observation(it)).append('\n') }
        }

        private fun alert(a: AirAlert): String =
            "${canonical.getValue(a.id)}|${a.location.uid}|${a.type}|${a.level}|${a.startedAt}|${a.updatedAt}|${a.finishedAt}|" +
                a.threats.joinToString(";") { "${it.type}:${it.level}:${it.startedAt}:${it.sourceMessage}" }

        private fun observation(o: TargetObservation): String {
            val markers = o.alertMarkerIds.map { marker ->
                val (id, type) = marker.split(':', limit = 2)
                "${canonical.getValue(id.toLong())}:$type"
            }.sorted()
            return "${o.targetId}|${o.type}|${o.observedAt}|${point(o.position)}|${o.level}|${o.outcome}|${markers.joinToString(",")}|${o.message}"
        }
    }

    private companion object {
        fun point(p: GeoPoint) = "${coord(p.lat)},${coord(p.lon)}"
    }
}
