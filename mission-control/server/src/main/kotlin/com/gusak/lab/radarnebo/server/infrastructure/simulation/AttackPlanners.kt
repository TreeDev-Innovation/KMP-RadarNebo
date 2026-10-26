package com.gusak.lab.radarnebo.server.infrastructure.simulation

import com.gusak.lab.radarnebo.data.ThreatLevel
import com.gusak.lab.radarnebo.data.ThreatType
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.GeoPoint
import kotlin.random.Random

private const val MINUTE = 60_000L

private fun Long.plusMinutes(minutes: Double) = this + (minutes * MINUTE).toLong()

private fun <T> List<T>.weighted(random: Random, weight: (T) -> Int): T {
    var roll = random.nextInt(sumOf(weight))
    return first { roll -= weight(it); roll < 0 }
}

private fun settlementOblast(name: String) = SimulatedLocations.settlement(name).city.oblastUid

/** The [n] sites nearest [target] within [maxKm], or the nearest one if none is that close. */
private fun nearestSites(sites: List<LaunchSite>, target: GeoPoint, maxKm: Double, n: Int): List<LaunchSite> {
    val sorted = sites.sortedBy { GeoMath.distanceKm(it.point, target) }
    return sorted.filter { GeoMath.distanceKm(it.point, target) <= maxKm }.take(n).ifEmpty { sorted.take(1) }
}

/**
 * A massive combined strike as Russia flies them, compressed to a few hours. Minutes from the start:
 *
 * - 0-120: waves of 22-36 Shahed groups, mixed with Gerbera decoys and a few jet Shaheds, from launch sites in
 *   Russia and occupied Crimea and Donetsk. They take 2-5 hours to their objectives, doglegging on the way.
 * - 110-170: Tu-95MS bombers reach their launch lines (strategic aviation alert over the whole country until
 *   the missiles reach Ukraine) and launch 10-30 Kh-101 cruise missiles, which enter through the north, east or south-east and wind to their
 *   objectives. Ships in the Black Sea launch Kalibrs through the southern coast.
 * - 165: a MiG-31K takes off (nationwide alert); it usually launches Kinzhals 15-20 minutes later.
 * - 200-215: Iskander-M and KN-23 ballistic missiles at the objectives within their range.
 *
 * Every flight's fate is decided when it is planned, with real-world interception rates (most Shaheds
 * and cruise missiles are shot down, ballistic missiles much less often).
 */
internal class CombinedStrikePlanner(private val seed: Long) {

    fun plan(slot: Long, startsAt: Long): AttackPlan {
        val random = Random(seed * 1_000_003L + slot)
        val factory = FlightFactory(random)
        val objectives = List(3 + random.nextInt(3)) { StrikeGeography.objectives.weighted(random) { it.weight } }.distinct()
        fun id(kind: String, n: Int) = "S$slot-$kind${(n + 1).toString().padStart(2, '0')}"

        val flights = mutableListOf<Flight>()
        val areas = mutableListOf<AreaThreat>()

        // Drone waves: Shahed groups with Gerbera decoys among them, and a few jet Shaheds late in the wave.
        val droneGroups = List(22 + random.nextInt(15)) { if (random.nextDouble() < 0.22) Munition.Gerbera else Munition.Shahed } +
            List(1 + random.nextInt(3)) { Munition.JetShahed }
        droneGroups.forEachIndexed { i, munition ->
            val objective = if (random.nextDouble() < 0.8) objectives.random(random) else StrikeGeography.objectives.weighted(random) { it.weight }
            val target = factory.jitter(StrikeGeography.point(objective.settlement), 6.0)
            val site = nearestSites(StrikeGeography.droneSites, target, maxKm = 900.0, n = 3).random(random)
            val jet = munition == Munition.JetShahed
            flights += factory.flight(
                id = id("D", i),
                munition = munition,
                waypoints = factory.droneRoute(factory.jitter(site.point, 15.0), factory.gate(StrikeGeography.droneGates, site.point, target, 15.0), target),
                departAt = startsAt.plusMinutes(if (jet) random.nextDouble(90.0, 130.0) else random.nextDouble(0.0, 120.0)),
                origin = site,
                target = objective.settlement,
                targetOblastUid = settlementOblast(objective.settlement),
                count = if (jet) 1 + random.nextInt(2) else 1 + random.nextInt(5),
            )
        }

        // Kh-101 from Tu-95MS bombers.
        val bomberLine = StrikeGeography.bomberLaunchLines.random(random)
        val kh101Start = startsAt.plusMinutes(130.0 + random.nextDouble(0.0, 5.0))
        var launchAt = kh101Start
        repeat(10 + random.nextInt(21)) { i ->
            val objective = objectives.random(random)
            val target = factory.jitter(StrikeGeography.point(objective.settlement), 4.0)
            flights += factory.flight(
                id = id("KH", i),
                munition = Munition.Kh101,
                waypoints = factory.cruiseRoute(factory.jitter(bomberLine.point, 40.0), factory.gate(StrikeGeography.cruiseGates, bomberLine.point, target, 10.0), target),
                departAt = launchAt,
                origin = bomberLine,
                target = objective.settlement,
                targetOblastUid = settlementOblast(objective.settlement),
            )
            launchAt = launchAt.plusMinutes(random.nextDouble(0.2, 0.9))
        }
        // The whole country stays alerted from the bombers' approach until the missiles cross into Ukraine.
        val kh101Seen = flights.filter { it.munition == Munition.Kh101 }.maxOf { it.detectedAt }
        areas += AreaThreat(
            ThreatType.StrategicAviation, SimulatedLocations.freeRegionUids, kh101Start - 20 * MINUTE, maxOf(launchAt, kh101Seen) + 10 * MINUTE,
            "Активність стратегічної авіації: Ту-95МС на рубежах пуску крилатих ракет Х-101",
        )

        // Kalibr from the Black Sea.
        val carrier = StrikeGeography.kalibrCarriers.random(random)
        val kalibrStart = startsAt.plusMinutes(135.0 + random.nextDouble(0.0, 10.0))
        launchAt = kalibrStart
        repeat(3 + random.nextInt(8)) { i ->
            val objective = objectives.random(random)
            val target = factory.jitter(StrikeGeography.point(objective.settlement), 4.0)
            flights += factory.flight(
                id = id("KL", i),
                munition = Munition.Kalibr,
                waypoints = factory.cruiseRoute(factory.jitter(carrier.point, 25.0), factory.gate(StrikeGeography.kalibrGates, carrier.point, target, 10.0), target),
                departAt = launchAt,
                origin = carrier,
                target = objective.settlement,
                targetOblastUid = settlementOblast(objective.settlement),
            )
            launchAt = launchAt.plusMinutes(random.nextDouble(0.3, 1.2))
        }
        areas += AreaThreat(
            ThreatType.UnspecifiedMissiles, listOf(18, 17, 23, 12), kalibrStart - 25 * MINUTE, launchAt + 3 * MINUTE,
            "Загроза ракетного удару: у Чорному морі носії крилатих ракет «Калібр»", ThreatLevel.Yellow,
        )

        // MiG-31K take-off; most of the time it launches Kinzhals, sometimes it is only a threat.
        val takeOff = startsAt.plusMinutes(165.0 + random.nextDouble(0.0, 5.0))
        val kinzhals = if (random.nextDouble() < 0.7) 1 + random.nextInt(4) else 0
        val kinzhalLaunch = takeOff.plusMinutes(random.nextDouble(15.0, 22.0))
        repeat(kinzhals) { i ->
            val targetName = listOf("Kyiv", "Kyiv", "Kyiv", "Starokostiantyniv", "Lviv", "Ivano-Frankivsk", "Odesa").random(random)
            val target = factory.jitter(StrikeGeography.point(targetName), 3.0)
            val area = nearestSites(StrikeGeography.kinzhalLaunchAreas, target, maxKm = Double.MAX_VALUE, n = 1).single()
            flights += factory.flight(
                id = id("KZ", i),
                munition = Munition.Kinzhal,
                waypoints = listOf(factory.jitter(area.point, 30.0), target),
                departAt = kinzhalLaunch.plusMinutes(i * random.nextDouble(0.1, 0.5)),
                origin = area,
                target = targetName,
                targetOblastUid = settlementOblast(targetName),
            )
        }
        val kinzhalsDown = flights.filter { it.munition == Munition.Kinzhal }.maxOfOrNull { it.endAt } ?: takeOff.plusMinutes(40.0)
        areas += AreaThreat(
            ThreatType.Mig31k, SimulatedLocations.freeRegionUids, takeOff, kinzhalsDown + 2 * MINUTE,
            "Зліт МіГ-31К — загроза застосування ракет «Кинджал» по всій території України",
        )

        // Ballistic missiles at the objectives in range, or at the cities near the front.
        repeat(3 + random.nextInt(7)) { i ->
            val munition = if (random.nextDouble() < 0.2) Munition.Kn23 else Munition.IskanderM
            val reachKm = if (munition == Munition.Kn23) 700.0 else 500.0
            val candidates = (objectives.map { it.settlement } + BALLISTIC_FALLBACK_TARGETS).filter { name ->
                StrikeGeography.ballisticSites.any { GeoMath.distanceKm(it.point, StrikeGeography.point(name)) <= reachKm }
            }
            val targetName = candidates.random(random)
            val target = factory.jitter(StrikeGeography.point(targetName), 3.0)
            val site = nearestSites(StrikeGeography.ballisticSites, target, reachKm, n = 2).random(random)
            flights += factory.flight(
                id = id("BM", i),
                munition = munition,
                waypoints = listOf(factory.jitter(site.point, 20.0), target),
                departAt = startsAt.plusMinutes(200.0 + random.nextDouble(0.0, 15.0)),
                origin = site,
                target = targetName,
                targetOblastUid = settlementOblast(targetName),
            )
        }
        return AttackPlan(flights, areas)
    }


    private companion object {
        val BALLISTIC_FALLBACK_TARGETS = listOf("Kharkiv", "Dnipro", "Odesa", "Zaporizhzhia", "Sumy", "Poltava", "Kyiv")
    }
}

/**
 * Front-line activity that never stops, planned per [slotMs] slot: guided bombs from Su-34s on front-line
 * and border towns (with tactical aviation alerts in their oblasts while the bombers work), Orlan
 * reconnaissance drones over the towns behind the front, small Shahed groups at cities near the border,
 * and the occasional ballistic missile.
 */
internal class FrontlinePlanner(private val seed: Long, val slotMs: Long = 10 * MINUTE) {

    fun plan(slot: Long): AttackPlan {
        val random = Random(seed * 7_368_787L + slot)
        val factory = FlightFactory(random)
        val startsAt = slot * slotMs
        val slotMinutes = slotMs / MINUTE.toDouble()
        val flights = mutableListOf<Flight>()
        var n = 0
        fun id(kind: String) = "F$slot-$kind${++n}"

        // Guided bombs: often a salvo on one town, plus single releases elsewhere.
        val bombs = mutableListOf<Pair<KabTarget, Long>>()
        if (random.nextDouble() < 0.6) {
            val target = StrikeGeography.kabTargets.weighted(random) { it.weight }
            var releaseAt = startsAt.plusMinutes(random.nextDouble(0.0, slotMinutes * 0.8))
            repeat(2 + random.nextInt(3)) {
                bombs += target to releaseAt
                releaseAt += random.nextLong(8_000, 30_000)
            }
        }
        repeat(random.nextInt(3)) {
            bombs += StrikeGeography.kabTargets.weighted(random) { it.weight } to startsAt.plusMinutes(random.nextDouble(0.0, slotMinutes))
        }
        bombs.forEach { (target, releaseAt) ->
            val town = StrikeGeography.point(target.settlement)
            val release = GeoMath.destination(
                town,
                random.nextInt(target.bearingDeg.first, target.bearingDeg.last + 1).toDouble(),
                random.nextInt(target.rangeKm.first, target.rangeKm.last + 1).toDouble(),
            )
            flights += factory.flight(
                id = id("KAB"),
                munition = Munition.Kab,
                waypoints = listOf(release, factory.jitter(town, 2.5)),
                departAt = releaseAt,
                origin = target.launchArea,
                target = target.settlement,
                targetOblastUid = settlementOblast(target.settlement),
            )
        }
        val areas = flights.groupBy { it.targetOblastUid!! }.map { (oblast, oblastBombs) ->
            AreaThreat(
                ThreatType.TacticalAviation, listOf(oblast),
                oblastBombs.minOf { it.departAt } - 5 * MINUTE, oblastBombs.maxOf { it.departAt } + 2 * MINUTE,
                "Активність тактичної авіації (Су-34/Су-35), пуски КАБ",
            )
        }

        // A reconnaissance drone over the towns behind a stretch of the front, and back.
        if (random.nextDouble() < 0.3) {
            val sector = StrikeGeography.reconSectors.random(random)
            val stops = sector.settlements.shuffled(random).take(1 + random.nextInt(2)).map { factory.jitter(StrikeGeography.point(it), 6.0) }
            flights += factory.flight(
                id = id("RC"),
                munition = Munition.Orlan,
                waypoints = listOf(sector.base) + stops + factory.jitter(sector.base, 10.0),
                departAt = startsAt.plusMinutes(random.nextDouble(0.0, slotMinutes)),
                origin = null,
                target = sector.settlements.first(),
                targetOblastUid = settlementOblast(sector.settlements.first()),
                level = ThreatLevel.Yellow,
            )
        }

        // A small Shahed group at a city near the border or the front.
        if (random.nextDouble() < 0.25) {
            val targetName = StrikeGeography.frontlineDroneTargets.random(random)
            val target = factory.jitter(StrikeGeography.point(targetName), 5.0)
            val site = nearestSites(StrikeGeography.droneSites, target, maxKm = 600.0, n = 2).random(random)
            flights += factory.flight(
                id = id("D"),
                munition = if (random.nextDouble() < 0.2) Munition.Gerbera else Munition.Shahed,
                waypoints = factory.droneRoute(factory.jitter(site.point, 15.0), factory.gate(StrikeGeography.droneGates, site.point, target, 15.0), target),
                departAt = startsAt.plusMinutes(random.nextDouble(0.0, slotMinutes)),
                origin = site,
                target = targetName,
                targetOblastUid = settlementOblast(targetName),
                count = 1 + random.nextInt(3),
            )
        }

        // Now and then a ballistic missile at a city near the border.
        if (random.nextDouble() < 0.05) {
            val targetName = listOf("Kharkiv", "Sumy", "Dnipro", "Odesa", "Zaporizhzhia", "Kramatorsk", "Mykolaiv", "Poltava").random(random)
            val target = factory.jitter(StrikeGeography.point(targetName), 3.0)
            val site = nearestSites(StrikeGeography.ballisticSites, target, maxKm = 500.0, n = 1).single()
            flights += factory.flight(
                id = id("BM"),
                munition = Munition.IskanderM,
                waypoints = listOf(factory.jitter(site.point, 20.0), target),
                departAt = startsAt.plusMinutes(random.nextDouble(0.0, slotMinutes)),
                origin = site,
                target = targetName,
                targetOblastUid = settlementOblast(targetName),
            )
        }
        return AttackPlan(flights, areas)
    }
}
