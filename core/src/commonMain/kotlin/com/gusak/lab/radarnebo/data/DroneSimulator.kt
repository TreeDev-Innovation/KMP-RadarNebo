package com.gusak.lab.radarnebo.data

import com.gusak.lab.radarnebo.domain.ThreatMapper
import com.gusak.lab.radarnebo.geo.City
import com.gusak.lab.radarnebo.geo.GeoMath
import com.gusak.lab.radarnebo.geo.Regions
import com.gusak.lab.radarnebo.tracking.DroneTracker
import kotlin.random.Random
import kotlin.time.Instant

/**
 * Real-time simulation of air attacks, used when no API token is configured (app demo mode and
 * the proxy's demo mode). The UI always labels it as demo data; it never reflects the real situation.
 *
 * It covers every threat class alerts.in.ua reports: drone groups (Shahed, jet Shahed, Gerbera
 * decoys, reconnaissance drones) and cruise missiles fly routes; ballistic missiles, Kinzhal launches
 * after a MiG-31K take-off, guided bombs, tactical and strategic aviation and air defence activity
 * come as recurring area alerts, the way the real feed reports them.
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
    private const val MISSILE_PAUSE_MS = 40 * 60_000L

    const val SHAHED_MESSAGE = "Рух ударних БпЛА типу «Шахед» (Shahed-136 / Герань-2)"
    const val GERBERA_MESSAGE = "БпЛА-імітатори типу «Гербера»"
    const val JET_SHAHED_MESSAGE = "Реактивні ударні БпЛА «Герань-3» (Shahed-238)"
    const val RECON_MESSAGE = "Розвідувальний БпЛА «Орлан-10» на лінії фронту"
    const val KH101_MESSAGE = "Крилаті ракети Х-101, пуски з Ту-95МС"
    const val KALIBR_MESSAGE = "Крилаті ракети «Калібр» з акваторії Чорного моря"

    private class Flight(
        val cities: List<City>,
        val speedKmh: Double,
        val phaseMs: Long,
        val message: String,
        val threatType: ThreatType,
        pauseMs: Long,
    ) {
        /** Cumulative distance from the first settlement to each settlement, km. */
        val legs: List<Double> = cities.zipWithNext { a, b -> GeoMath.distanceKm(a.point, b.point) }
            .runningFold(0.0) { acc, d -> acc + d }
        val flightMs: Long = (legs.last() / speedKmh * 3_600_000).toLong()
        val cycleMs: Long = flightMs + pauseMs
    }

    private fun flight(
        route: List<String>,
        speedKmh: Double,
        phaseMinutes: Int,
        message: String,
        threatType: ThreatType = ThreatType.Drones,
        pauseMs: Long = PAUSE_MS,
    ) = Flight(
        cities = route.map { name -> Regions.cities.first { it.name == name } },
        speedKmh = speedKmh,
        phaseMs = phaseMinutes * 60_000L,
        message = message,
        threatType = threatType,
        pauseMs = pauseMs,
    )

    private val flights = listOf(
        flight(listOf("Hlukhiv", "Shostka", "Konotop", "Bakhmach", "Nizhyn", "Brovary", "Kyiv", "Irpin"), 175.0, 0, SHAHED_MESSAGE),
        flight(listOf("Kupiansk", "Chuhuiv", "Kharkiv", "Bohodukhiv", "Okhtyrka", "Lebedyn", "Romny", "Lubny"), 165.0, 40, SHAHED_MESSAGE),
        flight(listOf("Nikopol", "Kryvyi Rih", "Zhovti Vody", "Oleksandriia", "Znamianka", "Smila", "Cherkasy"), 150.0, 75, GERBERA_MESSAGE),
        flight(listOf("Mykolaiv", "Voznesensk", "Pervomaisk", "Uman", "Haisyn", "Ladyzhyn", "Vinnytsia"), 180.0, 20, SHAHED_MESSAGE),
        flight(listOf("Izium", "Balakliia", "Lozova", "Samar", "Dnipro", "Kamianske", "Horishni Plavni", "Kremenchuk"), 170.0, 100, SHAHED_MESSAGE),
        flight(listOf("Sumy", "Trostianets", "Hadiach", "Myrhorod", "Zolotonosha", "Kaniv", "Obukhiv"), 450.0, 10, JET_SHAHED_MESSAGE),
        flight(listOf("Sloviansk", "Izium", "Balakliia", "Chuhuiv"), 110.0, 30, RECON_MESSAGE),
        // Cruise missiles: legs stay under the tracker's 300 km hop, with longer pauses between salvos.
        flight(
            listOf("Pavlohrad", "Dnipro", "Kremenchuk", "Cherkasy", "Bila Tserkva", "Fastiv", "Zhytomyr", "Rivne", "Lutsk"),
            780.0, 5, KH101_MESSAGE, ThreatType.CruiseMissiles, MISSILE_PAUSE_MS,
        ),
        flight(
            listOf("Odesa", "Podilsk", "Haisyn", "Vinnytsia", "Khmilnyk", "Khmelnytskyi", "Ternopil", "Lviv"),
            750.0, 35, KALIBR_MESSAGE, ThreatType.CruiseMissiles, MISSILE_PAUSE_MS,
        ),
    )

    /**
     * A recurring area alert: active for [durationMin] minutes every [periodMin] minutes. [oblastUid]
     * alone makes an oblast-wide alert; with [city] it is that settlement's alert.
     */
    private class AreaAlert(
        val type: ThreatType,
        val oblastUid: Int,
        val message: String,
        val periodMin: Int,
        val durationMin: Int,
        val phaseMin: Int,
        val city: String? = null,
        val yellow: Boolean = false,
    )

    private val areaAlerts = listOf(
        AreaAlert(ThreatType.BallisticMissiles, 22, "Загроза балістичних ракет «Іскандер-М» з Бєлгородської області", 25, 4, 0),
        AreaAlert(ThreatType.BallisticMissiles, 28, "Загроза балістичних ракет KN-23", 40, 5, 17),
        // A MiG-31K take-off raises a wide alert; the Kinzhal threat to Kyiv follows it.
        AreaAlert(ThreatType.Mig31k, 14, "Зліт МіГ-31К — загроза ракет «Кинджал»", 120, 15, 0),
        AreaAlert(ThreatType.Mig31k, 10, "Зліт МіГ-31К — загроза ракет «Кинджал»", 120, 15, 0),
        AreaAlert(ThreatType.Mig31k, 24, "Зліт МіГ-31К — загроза ракет «Кинджал»", 120, 15, 0),
        AreaAlert(ThreatType.BallisticMissiles, Regions.KYIV_CITY_UID, "Швидкісна ціль — ракета «Кинджал» на Київ", 120, 6, 112),
        AreaAlert(ThreatType.GuidedBombs, 22, "Пуски КАБ (УМПК) з Су-34", 18, 6, 0, city = "Kupiansk"),
        AreaAlert(ThreatType.GuidedBombs, 12, "Пуски КАБ на Запоріжжя", 22, 5, 9, city = "Zaporizhzhia"),
        AreaAlert(ThreatType.GuidedBombs, 28, "Пуски керованих авіабомб", 15, 7, 4, city = "Kramatorsk"),
        AreaAlert(ThreatType.TacticalAviation, 12, "Активність тактичної авіації (Су-34/Су-35) на південному напрямку", 30, 20, 0),
        AreaAlert(ThreatType.TacticalAviation, 28, "Активність тактичної авіації (Су-35)", 30, 18, 12),
        AreaAlert(ThreatType.StrategicAviation, 17, "Активність стратегічної авіації (Ту-95МС) — можливі пуски Х-101", 90, 25, 0),
        AreaAlert(ThreatType.StrategicAviation, 23, "Активність стратегічної авіації (Ту-22М3)", 90, 25, 0),
        AreaAlert(ThreatType.UnspecifiedMissiles, 19, "Ракетна небезпека", 45, 5, 20),
        AreaAlert(ThreatType.AirDefense, Regions.KYIV_CITY_UID, "Працює ППО", 20, 8, 3),
        AreaAlert(ThreatType.AirDefense, 9, "Працює ППО у Дніпрі", 35, 6, 14, city = "Dnipro", yellow = true),
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
        flights.flatMapIndexed { index, flight -> flightAlerts(index, flight, nowMs) } +
            areaAlerts.mapIndexedNotNull { index, area -> areaAlert(index, area, nowMs) } +
            backgroundAlerts(nowMs)

    /**
     * Replays the last [minutes] of the simulation into [tracker], one snapshot per minute, so tracks
     * start with a history instead of a single point.
     */
    fun warmUp(tracker: DroneTracker, nowMs: Long, minutes: Int = 90) {
        for (m in minutes downTo 1) {
            val t = nowMs - m * 60_000L
            tracker.update(ThreatMapper.toMarkers(alertsAt(t), dronesOnly = false), t)
        }
    }

    private fun flightAlerts(index: Int, flight: Flight, nowMs: Long): List<Alert> {
        val shifted = nowMs + flight.phaseMs
        val cycle = shifted / flight.cycleMs
        val cycleStart = nowMs - shifted % flight.cycleMs
        return routeAlerts(
            idBase = 800_000L + index * 10_000L + (cycle % 100) * 100,
            route = flight.cities,
            speedKmh = flight.speedKmh,
            departedAt = cycleStart,
            message = flight.message,
            threatType = flight.threatType,
            nowMs = nowMs,
        )
    }

    /**
     * Alerts at [nowMs] for a group that left the first settlement of [route] at [departedAt] and flies it
     * at [speedKmh]: each settlement it has reached is alerted from its arrival time until [OVERLAP_MS]
     * after the group moved on. Alert ids are [idBase] plus the settlement's index in the route.
     */
    fun routeAlerts(
        idBase: Long,
        route: List<City>,
        speedKmh: Double,
        departedAt: Long,
        message: String,
        threatType: ThreatType,
        nowMs: Long,
    ): List<Alert> {
        val elapsed = nowMs - departedAt
        if (elapsed < 0 || route.isEmpty()) return emptyList()
        val legs = route.zipWithNext { a, b -> GeoMath.distanceKm(a.point, b.point) }.runningFold(0.0) { acc, d -> acc + d }
        val flightMs = (legs.last() / speedKmh * 3_600_000).toLong()
        if (elapsed > flightMs + OVERLAP_MS) return emptyList()

        val flown = minOf(elapsed, flightMs) * speedKmh / 3_600_000
        val reached = legs.indexOfLast { it <= flown }
        return (0..reached).mapNotNull { i ->
            val arrival = departedAt + (legs[i] / speedKmh * 3_600_000).toLong()
            val departed = if (i < reached) departedAt + (legs[i + 1] / speedKmh * 3_600_000).toLong() else null
            val ended = if (i == reached && elapsed > flightMs) departedAt + flightMs else departed
            if (ended != null && nowMs - ended > OVERLAP_MS) return@mapNotNull null
            cityAlert(
                id = idBase + i,
                city = route[i],
                startedAt = Instant.fromEpochMilliseconds(arrival).toString(),
                message = message,
                threatType = threatType,
            )
        }
    }

    private fun areaAlert(index: Int, area: AreaAlert, nowMs: Long): Alert? {
        val periodMs = area.periodMin * 60_000L
        val shifted = nowMs + area.phaseMin * 60_000L
        if (shifted % periodMs >= area.durationMin * 60_000L) return null
        val startedAt = Instant.fromEpochMilliseconds(nowMs - shifted % periodMs).toString()
        val id = 960_000L + index * 100 + (shifted / periodMs) % 100
        area.city?.let { name ->
            return cityAlert(id, Regions.cities.first { it.name == name }, startedAt, area.message, area.type, area.yellow)
        }
        val oblast = Regions.oblast(area.oblastUid)!!
        val level = if (area.yellow) "yellow" else "red"
        return Alert(
            id = id,
            locationTitle = oblast.nameUk,
            locationTitleEn = oblast.nameEn,
            // Kyiv city is a region of its own in the API, reported as a city.
            locationType = if (area.oblastUid == Regions.KYIV_CITY_UID) "city" else "oblast",
            startedAt = startedAt,
            locationUid = oblast.uid.toString(),
            locationOblast = oblast.nameUk,
            locationOblastUid = oblast.uid.toString(),
            alertLevel = level,
            threats = listOf(Threat(area.type.apiValue, level, startedAt, area.message)),
        )
    }

    /** A red (or [yellow]) alert for the hromada of [city], as alerts.in.ua reports one. */
    fun cityAlert(
        id: Long,
        city: City,
        startedAt: String,
        message: String,
        threatType: ThreatType = ThreatType.Drones,
        yellow: Boolean = false,
    ): Alert {
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
            alertLevel = if (yellow) "yellow" else "red",
            threats = listOf(Threat(threatType.apiValue, if (yellow) "yellow" else "red", startedAt, message)),
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
