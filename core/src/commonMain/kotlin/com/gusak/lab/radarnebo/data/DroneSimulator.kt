package com.gusak.lab.radarnebo.data

import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.geo.City
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.tracking.DroneTracker
import kotlin.random.Random
import kotlin.time.Instant

/**
 * Real-time simulation of drone attacks, used when no API token is configured (app demo mode and
 * the proxy's demo mode). The UI always labels it as demo data; it never reflects the real situation.
 *
 * Each group flies a fixed route of settlements at a constant, realistic speed. As it reaches a
 * settlement, that hromada gets a drone alert timestamped with the arrival time, the way real alerts
 * follow a group across the country. The previous settlement's alert stays up for a short overlap.
 * The alert text names the drone type, as real Ukrainian alert messages often do. Flights repeat
 * with a pause and different groups are offset, so some are always airborne.
 *
 * The state is a pure function of wall-clock time, so the app and the proxy agree, and history can
 * be replayed ([warmUp]) to give tracks a past the moment the app or proxy starts.
 */
object DroneSimulator {
    private const val OVERLAP_MS = 2 * 60_000L
    private const val PAUSE_MS = 15 * 60_000L

    const val SHAHED_MESSAGE = "Рух ударних БпЛА типу «Шахед» (Shahed-136 / Герань-2)"
    const val GERBERA_MESSAGE = "БпЛА-імітатори типу «Гербера»"

    private class Flight(val cities: List<City>, val speedKmh: Double, val phaseMs: Long, val message: String) {
        /** Cumulative distance from the first settlement to each settlement, km. */
        val legs: List<Double> = cities.zipWithNext { a, b -> GeoMath.distanceKm(a.point, b.point) }
            .runningFold(0.0) { acc, d -> acc + d }
        val flightMs: Long = (legs.last() / speedKmh * 3_600_000).toLong()
        val cycleMs: Long = flightMs + PAUSE_MS
    }

    private fun flight(route: List<String>, speedKmh: Double, phaseMinutes: Int, message: String) = Flight(
        cities = route.map { name -> Regions.cities.first { it.name == name } },
        speedKmh = speedKmh,
        phaseMs = phaseMinutes * 60_000L,
        message = message,
    )

    private val flights = listOf(
        flight(listOf("Hlukhiv", "Shostka", "Konotop", "Bakhmach", "Nizhyn", "Brovary", "Kyiv", "Irpin"), 175.0, 0, SHAHED_MESSAGE),
        flight(listOf("Kupiansk", "Chuhuiv", "Kharkiv", "Bohodukhiv", "Okhtyrka", "Lebedyn", "Romny", "Lubny"), 165.0, 40, SHAHED_MESSAGE),
        flight(listOf("Nikopol", "Kryvyi Rih", "Zhovti Vody", "Oleksandriia", "Znamianka", "Smila", "Cherkasy"), 150.0, 75, GERBERA_MESSAGE),
        flight(listOf("Mykolaiv", "Voznesensk", "Pervomaisk", "Uman", "Haisyn", "Ladyzhyn", "Vinnytsia"), 180.0, 20, SHAHED_MESSAGE),
        flight(listOf("Izium", "Balakliia", "Lozova", "Samar", "Dnipro", "Kamianske", "Horishni Plavni", "Kremenchuk"), 170.0, 100, SHAHED_MESSAGE),
    )

    // Background noise, kept in regions far from the routes so it doesn't get linked to a flying group.
    private val raions = listOf(
        Triple(18, "Ізмаїльський район", "Izmail Raion"),
        Triple(27, "Львівський район", "Lviv Raion"),
        Triple(11, "Ужгородський район", "Uzhhorod Raion"),
        Triple(5, "Рівненський район", "Rivne Raion"),
    )

    /** Active alerts at wall-clock time [nowMs]. */
    fun alertsAt(nowMs: Long): List<Alert> =
        flights.flatMapIndexed { index, flight -> flightAlerts(index, flight, nowMs) } + backgroundAlerts(nowMs)

    /**
     * Replays the last [minutes] of the simulation into [tracker], one snapshot per minute, so tracks
     * start with a history instead of a single point.
     */
    fun warmUp(tracker: DroneTracker, nowMs: Long, minutes: Int = 90) {
        for (m in minutes downTo 1) {
            val t = nowMs - m * 60_000L
            tracker.update(ThreatMapper.toMarkers(alertsAt(t), dronesOnly = true), t)
        }
    }

    private fun flightAlerts(index: Int, flight: Flight, nowMs: Long): List<Alert> {
        val shifted = nowMs + flight.phaseMs
        val cycle = shifted / flight.cycleMs
        val elapsed = shifted % flight.cycleMs
        val cycleStart = nowMs - elapsed
        if (elapsed > flight.flightMs + OVERLAP_MS) return emptyList()

        val flown = minOf(elapsed, flight.flightMs) * flight.speedKmh / 3_600_000
        val reached = flight.legs.indexOfLast { it <= flown }
        return (0..reached).mapNotNull { i ->
            val arrival = cycleStart + (flight.legs[i] / flight.speedKmh * 3_600_000).toLong()
            val departed = if (i < reached) cycleStart + (flight.legs[i + 1] / flight.speedKmh * 3_600_000).toLong() else null
            val ended = if (i == reached && elapsed > flight.flightMs) cycleStart + flight.flightMs else departed
            if (ended != null && nowMs - ended > OVERLAP_MS) return@mapNotNull null
            cityAlert(
                id = 800_000L + index * 10_000L + (cycle % 100) * 100 + i,
                city = flight.cities[i],
                startedAt = Instant.fromEpochMilliseconds(arrival).toString(),
                message = flight.message,
            )
        }
    }

    private fun cityAlert(id: Long, city: City, startedAt: String, message: String): Alert {
        val oblast = Regions.oblast(city.oblastUid)!!
        return Alert(
            id = id,
            locationTitle = "${city.keywords.first()} (територіальна громада)",
            locationTitleEn = city.name,
            locationType = "hromada",
            startedAt = startedAt,
            alertType = "air_raid",
            locationUid = "sim-${city.name}",
            locationOblast = oblast.nameUk,
            locationOblastUid = city.oblastUid.toString(),
            alertLevel = "red",
            threats = listOf(Threat("drones", "red", startedAt, message)),
        )
    }

    private fun backgroundAlerts(nowMs: Long): List<Alert> {
        // Re-rolled every 20 minutes so the noise doesn't flicker.
        val window = nowMs / (20 * 60_000L)
        val random = Random(window)
        val picked = raions.shuffled(random).take(random.nextInt(1, 3))
        val raionAlerts = picked.mapIndexed { index, (oblastUid, title, titleEn) ->
            val red = random.nextInt(3) != 0
            val started = Instant.fromEpochMilliseconds(window * 20 * 60_000L - random.nextInt(0, 60) * 60_000L).toString()
            Alert(
                id = 900_000L + (window % 100) * 10 + index,
                locationTitle = title,
                locationTitleEn = titleEn,
                locationType = "raion",
                startedAt = started,
                locationUid = "sim-$titleEn",
                locationOblast = Regions.oblast(oblastUid)!!.nameUk,
                locationOblastUid = oblastUid.toString(),
                alertLevel = if (red) "red" else "yellow",
                threats = listOf(
                    Threat(
                        threatType = if (random.nextInt(4) == 0) "guided_aerial_bombs" else "drones",
                        level = if (red) "red" else "yellow",
                        startedAt = started,
                        sourceMessage = if (red) "Загроза ударних БпЛА" else "Дронова загроза (жовтий рівень)",
                    )
                ),
            )
        }
        val sumy = Regions.oblast(20)!!
        val started = Instant.fromEpochMilliseconds(nowMs - 40 * 60_000L).toString()
        val oblastWide = Alert(
            id = 950_000L,
            locationTitle = sumy.nameUk,
            locationTitleEn = sumy.nameEn,
            locationType = "oblast",
            startedAt = started,
            locationUid = sumy.uid.toString(),
            locationOblast = sumy.nameUk,
            locationOblastUid = sumy.uid.toString(),
            alertLevel = "red",
            threats = listOf(Threat("drones", "red", started, "Групи ударних БпЛА")),
        )
        return raionAlerts + oblastWide
    }
}
